import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import path from "node:path";
import test from "node:test";
import vm from "node:vm";

const assetRoot = path.resolve(
  path.dirname(fileURLToPath(import.meta.url)),
  "..",
  "..",
  "..",
  "app",
  "src",
  "main",
  "assets",
  "web",
);
const appScript = readFileSync(path.join(assetRoot, "app.js"), "utf8");

function extractAppSource(startMarker, endMarker) {
  const start = appScript.indexOf(startMarker);
  assert.notEqual(start, -1, `missing app source marker: ${startMarker}`);
  const end = appScript.indexOf(endMarker, start);
  assert.notEqual(end, -1, `missing app source marker: ${endMarker}`);
  return appScript.slice(start, end);
}

const viewportReportSource = extractAppSource(
  "function clearViewportReportTimer()",
  "function refreshDevelopmentViewportWidth()",
);

class FakeClock {
  constructor() {
    this.now = 0;
    this.nextId = 1;
    this.timers = new Map();
  }

  setTimeout(callback, delay = 0) {
    const id = this.nextId++;
    this.timers.set(id, {at: this.now + Math.max(0, Number(delay) || 0), callback});
    return id;
  }

  clearTimeout(id) {
    this.timers.delete(id);
  }

  async advance(milliseconds) {
    const target = this.now + milliseconds;
    while (true) {
      const next = Array.from(this.timers.entries())
        .filter(([, timer]) => timer.at <= target)
        .sort((left, right) => left[1].at - right[1].at || left[0] - right[0])[0];
      if (!next) break;
      const [id, timer] = next;
      this.timers.delete(id);
      this.now = timer.at;
      timer.callback();
      await flushMicrotasks();
    }
    this.now = target;
    await flushMicrotasks();
  }
}

async function flushMicrotasks() {
  for (let index = 0; index < 8; index += 1) await Promise.resolve();
}

function abortError() {
  const error = new Error("aborted");
  error.name = "AbortError";
  return error;
}

function loadViewportReportRuntime() {
  const clock = new FakeClock();
  const calls = [];
  const diagnostics = [];
  const fakeFetch = (requestPath, options = {}) => {
    let resolvePromise;
    let rejectPromise;
    const call = {
      requestPath,
      options,
      ignoreAbort: false,
      resolve(status, body = {}) {
        resolvePromise({
          status,
          ok: status >= 200 && status < 300,
          json: async () => body,
        });
      },
      reject(error = new Error("network")) {
        rejectPromise(error);
      },
    };
    const promise = new Promise((resolve, reject) => {
      resolvePromise = resolve;
      rejectPromise = reject;
    });
    options.signal?.addEventListener("abort", () => {
      if (!call.ignoreAbort) rejectPromise(abortError());
    }, {once: true});
    calls.push(call);
    return promise;
  };
  const context = vm.createContext({
    AbortController,
    DYNAMIC_ASPECT_BODY_CLASS: "dynamic",
    URLSearchParams,
    VIEWPORT_CONTROLLER_BUSY_RETRY_MILLIS: 5_000,
    VIEWPORT_REPORT_MAX_TIMEOUT_RETRIES: 2,
    VIEWPORT_REPORT_RETRY_MILLIS: 1_500,
    VIEWPORT_REPORT_SETTLE_MILLIS: 850,
    VIEWPORT_REPORT_TIMEOUT_MILLIS: 5_000,
    activeViewportValue: null,
    api: fakeFetch,
    browserCredential: "credential",
    browserSessionOwnsViewport: () => true,
    document: {
      hidden: false,
      body: {classList: {contains: value => value === "dynamic"}},
    },
    expectedExpandedProjectionViewport: () => null,
    invalidateCredential: () => {},
    lastViewportReportKey: "",
    pageActive: true,
    pendingViewportReport: null,
    pendingViewportReportGeneration: 0,
    pendingViewportReportTimeoutAttempts: 0,
    setDevelopmentViewportDiagnostic: (name, value) => diagnostics.push({name, value}),
    t: key => key,
    viewportReportAbortController: null,
    viewportReportGeneration: 0,
    viewportReportTask: null,
    viewportReportTimer: 0,
    window: {
      clearTimeout: id => clock.clearTimeout(id),
      setTimeout: (callback, delay) => clock.setTimeout(callback, delay),
    },
  });
  vm.runInContext(`
    ${viewportReportSource}
    globalThis.scheduleReport = scheduleViewportReport;
    globalThis.stopReports = stopViewportReporting;
    globalThis.reportState = () => ({
      generation: viewportReportGeneration,
      lastKey: lastViewportReportKey,
      pendingKey: pendingViewportReport ? viewportReportKey(pendingViewportReport) : "",
      pendingGeneration: pendingViewportReportGeneration,
      pendingTimeoutAttempts: pendingViewportReportTimeoutAttempts,
      timerActive: Boolean(viewportReportTimer),
      taskKey: viewportReportTask ? viewportReportTask.key : "",
    });
  `, context);
  return {clock, calls, context, diagnostics};
}

function viewport(width, height) {
  return Object.freeze({
    width,
    height,
    viewportScale: 1,
    devicePixelRatio: 2,
  });
}

test("latest viewport timeout retries and then commits the same target", async () => {
  const runtime = loadViewportReportRuntime();
  runtime.context.scheduleReport(viewport(720, 1280));
  await runtime.clock.advance(850);
  assert.equal(runtime.calls.length, 1);

  await runtime.clock.advance(5_000);
  assert.deepEqual(
    {
      key: runtime.context.reportState().pendingKey,
      attempts: runtime.context.reportState().pendingTimeoutAttempts,
    },
    {key: "720x1280", attempts: 1},
  );

  await runtime.clock.advance(1_500);
  assert.equal(runtime.calls.length, 2);
  runtime.calls[1].resolve(200);
  await flushMicrotasks();
  assert.equal(runtime.context.reportState().lastKey, "720x1280");
  assert.equal(runtime.context.reportState().timerActive, false);
});

test("viewport timeout retry count is bounded", async () => {
  const runtime = loadViewportReportRuntime();
  runtime.context.scheduleReport(viewport(720, 1280));
  await runtime.clock.advance(850);

  for (let attempt = 0; attempt < 3; attempt += 1) {
    await runtime.clock.advance(5_000);
    if (attempt < 2) await runtime.clock.advance(1_500);
  }

  assert.equal(runtime.calls.length, 3);
  assert.equal(runtime.context.reportState().timerActive, false);
  assert.equal(runtime.context.reportState().pendingKey, "");
  assert.ok(runtime.diagnostics.some(entry => entry.value === "failed:timeout:720x1280"));
});

test("superseded abort never retries the old viewport", async () => {
  const runtime = loadViewportReportRuntime();
  runtime.context.scheduleReport(viewport(720, 1280));
  await runtime.clock.advance(850);
  assert.equal(runtime.calls.length, 1);

  runtime.context.scheduleReport(viewport(1280, 720));
  await flushMicrotasks();
  assert.equal(runtime.context.reportState().pendingKey, "1280x720");
  assert.equal(runtime.context.reportState().pendingTimeoutAttempts, 0);

  await runtime.clock.advance(850);
  assert.equal(runtime.calls.length, 2);
  runtime.calls[1].resolve(200);
  await flushMicrotasks();
  await runtime.clock.advance(6_500);
  assert.equal(runtime.calls.length, 2);
  assert.equal(runtime.context.reportState().lastKey, "1280x720");
});

test("stale 5xx completion cannot overwrite a rapid round-trip target", async () => {
  const runtime = loadViewportReportRuntime();
  runtime.context.scheduleReport(viewport(720, 1280));
  await runtime.clock.advance(850);
  runtime.calls[0].ignoreAbort = true;

  runtime.context.scheduleReport(viewport(1280, 720));
  runtime.calls[0].resolve(503);
  await flushMicrotasks();
  assert.equal(runtime.context.reportState().pendingKey, "1280x720");

  await runtime.clock.advance(850);
  assert.equal(runtime.calls.length, 2);
  runtime.calls[1].resolve(200);
  await flushMicrotasks();
  await runtime.clock.advance(5_000);
  assert.equal(runtime.calls.length, 2);
  assert.equal(runtime.context.reportState().lastKey, "1280x720");
});
