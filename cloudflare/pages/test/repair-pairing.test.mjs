import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import path from "node:path";
import test from "node:test";
import vm from "node:vm";

const cloudflareRoot = path.resolve(
  path.dirname(fileURLToPath(import.meta.url)),
  "..",
  "..",
);
const assetRoot = path.resolve(
  cloudflareRoot,
  "..",
  "app",
  "src",
  "main",
  "assets",
  "tesla",
);
const appScript = readFileSync(path.join(assetRoot, "app.js"), "utf8");
const indexHtml = readFileSync(path.join(assetRoot, "index.html"), "utf8");

function extractAppSource(startMarker, endMarker) {
  const start = appScript.indexOf(startMarker);
  assert.notEqual(start, -1, `missing app source marker: ${startMarker}`);
  const end = appScript.indexOf(endMarker, start);
  assert.notEqual(end, -1, `missing app source marker: ${endMarker}`);
  return appScript.slice(start, end);
}

function loadRepairCountdownRuntime() {
  const source = extractAppSource(
    "function cancelRepairPairingCountdown()",
    "function generateViewportClientId()",
  );
  const timers = new Map();
  const delays = [];
  let nextTimer = 1;
  const context = vm.createContext({
    document: { hidden: false },
    window: {
      clearTimeout(id) {
        timers.delete(id);
      },
      setTimeout(callback, delay) {
        const id = nextTimer++;
        timers.set(id, callback);
        delays.push(delay);
        return id;
      },
    },
  });
  vm.runInContext(`
    const REPAIR_PAIRING_DELAY_MILLIS = 10000;
    const repairPairingPanel = { hidden: true };
    let browserCredential = "credential-a";
    let pageActive = true;
    let repairPairingTimer = 0;
    ${source}
    globalThis.schedule = scheduleRepairPairingAction;
    globalThis.cancel = cancelRepairPairingCountdown;
    globalThis.panel = repairPairingPanel;
    globalThis.setCredential = value => { browserCredential = value; };
    globalThis.setPageActive = value => { pageActive = value; };
  `, context);
  return {
    context,
    delays,
    timers,
    fireOnlyTimer() {
      assert.equal(timers.size, 1);
      const [id, callback] = timers.entries().next().value;
      timers.delete(id);
      callback();
    },
  };
}

function loadStatusPollDelayPolicy() {
  const source = appScript.match(
    /function statusPollDelayMillis\(failureCount, randomSample = Math\.random\(\)\) \{[\s\S]*?\n  \}/u,
  )?.[0];
  assert.ok(source, "status polling backoff policy must remain testable");
  const context = vm.createContext({
    STATUS_HEALTHY_MIN_INTERVAL_MILLIS: 1500,
    STATUS_HEALTHY_MAX_INTERVAL_MILLIS: 2500,
    STATUS_FAILURE_BASE_INTERVAL_MILLIS: 2000,
    STATUS_FAILURE_MAX_INTERVAL_MILLIS: 5000,
  });
  return vm.runInContext(`(${source})`, context);
}

function loadStatusPollingRuntime() {
  const source = extractAppSource(
    "function statusPollingEligible()",
    "function pollStatus(options = {})",
  );
  const timers = new Map();
  const delays = [];
  let nextTimer = 1;
  const context = vm.createContext({
    document: { hidden: false },
    window: {
      clearTimeout(id) {
        timers.delete(id);
      },
      setTimeout(callback, delay) {
        const id = nextTimer++;
        timers.set(id, callback);
        delays.push(delay);
        return id;
      },
    },
  });
  vm.runInContext(`
    const STATUS_HEALTHY_MIN_INTERVAL_MILLIS = 1500;
    const STATUS_HEALTHY_MAX_INTERVAL_MILLIS = 2500;
    const STATUS_FAILURE_BASE_INTERVAL_MILLIS = 2000;
    const STATUS_FAILURE_MAX_INTERVAL_MILLIS = 5000;
    let browserCredential = "credential-a";
    let pageActive = true;
    let statusPolling = false;
    let statusGeneration = 0;
    let statusFailureCount = 0;
    let statusTimer = 0;
    let statusPollTask = null;
    let pollCalls = 0;
    let abortCalls = 0;
    const pendingPolls = [];
    ${source}
    function pollStatus() {
      if (statusPollTask) return statusPollTask.promise;
      pollCalls += 1;
      let resolvePoll;
      const task = {
        controller: { abort() { abortCalls += 1; } },
        promise: null
      };
      task.promise = new Promise(resolve => { resolvePoll = resolve; }).finally(() => {
        if (statusPollTask === task) statusPollTask = null;
      });
      statusPollTask = task;
      pendingPolls.push(() => resolvePoll(true));
      return task.promise;
    }
    globalThis.start = startStatusPolling;
    globalThis.stop = stopStatusPolling;
    globalThis.resolveNext = () => pendingPolls.shift()();
    globalThis.pollCallCount = () => pollCalls;
    globalThis.abortCallCount = () => abortCalls;
    globalThis.generation = () => statusGeneration;
  `, context);
  return { context, timers, delays };
}

test("repair pairing action appears after one non-resetting ten-second countdown", () => {
  const runtime = loadRepairCountdownRuntime();

  runtime.context.schedule();
  runtime.context.schedule();
  assert.equal(runtime.timers.size, 1, "repeated failure polls must share one timer");
  assert.deepEqual(runtime.delays, [10000]);

  runtime.fireOnlyTimer();
  assert.equal(runtime.context.panel.hidden, false);
  assert.equal(runtime.timers.size, 0);

  runtime.context.cancel();
  assert.equal(runtime.context.panel.hidden, true);
});

test("repair countdown cannot leak across credentials or hidden pages", () => {
  const runtime = loadRepairCountdownRuntime();

  runtime.context.schedule();
  runtime.context.setCredential("credential-b");
  runtime.fireOnlyTimer();
  assert.equal(runtime.context.panel.hidden, true);

  runtime.context.schedule();
  runtime.context.cancel();
  assert.equal(runtime.timers.size, 0);
  assert.equal(runtime.context.panel.hidden, true);

  runtime.context.setPageActive(false);
  runtime.context.schedule();
  assert.equal(runtime.timers.size, 0);
});

test("status polling arms before its request timeout and success always disarms", () => {
  const statusPoll = extractAppSource(
    "async function performStatusPoll(options, task)",
    "function invalidateCredential(message)",
  );
  assert.ok(
    statusPoll.indexOf("scheduleRepairPairingAction();") <
      statusPoll.indexOf("STATUS_REQUEST_TIMEOUT_MILLIS"),
    "the request timeout must be inside the ten-second recovery window",
  );
  assert.match(
    statusPoll,
    /if \(!response\.ok\) throw[^;]+;\s*\/\/[\s\S]*?cancelRepairPairingCountdown\(\);\s*const data = await response\.json\(\);/u,
  );
});

test("healthy status polling is jittered and failures use a bounded backoff", () => {
  const delay = loadStatusPollDelayPolicy();

  assert.equal(delay(0, 0), 1500);
  assert.equal(delay(0, 0.5), 2000);
  assert.equal(delay(0, 1), 2500);
  assert.equal(delay(1, 0), 2000);
  assert.equal(delay(1, 1), 4000);
  assert.equal(delay(2, 0), 2500);
  assert.equal(delay(2, 1), 5000);
  assert.equal(delay(4, 0), 2500);
  assert.equal(delay(4, 1), 5000);
  assert.equal(delay(100, 1), 5000);
  assert.equal(delay(Number.NaN, 0), 1500);
  assert.match(
    appScript,
    /statusPollDelayMillis\(statusFailureCount\)/u,
  );
});

test("status polling probes immediately, coalesces starts, and rejects stale generations", async () => {
  const runtime = loadStatusPollingRuntime();

  runtime.context.start();
  runtime.context.start();
  assert.equal(runtime.context.pollCallCount(), 1, "concurrent starts must share the first poll");
  assert.equal(runtime.timers.size, 0, "the initial poll must not wait for healthy jitter");
  assert.equal(runtime.context.generation(), 1);

  runtime.context.resolveNext();
  await new Promise(resolve => setImmediate(resolve));
  assert.equal(runtime.timers.size, 1, "one periodic timer follows the immediate poll");
  assert.equal(runtime.delays.length, 1);
  assert.ok(runtime.delays[0] >= 1500 && runtime.delays[0] <= 2500);

  const stale = loadStatusPollingRuntime();
  stale.context.start();
  stale.context.stop();
  assert.equal(stale.context.abortCallCount(), 1);
  assert.equal(stale.context.generation(), 2);
  stale.context.resolveNext();
  await new Promise(resolve => setImmediate(resolve));
  assert.equal(stale.timers.size, 0, "a stopped generation must not schedule another poll");
});

test("manual repair is explicit, closes stale route state, and requires fresh bootstrap", () => {
  const resetFlow = extractAppSource(
    "function resetCloudRelayRouteStateForRepair()",
    "async function bootstrapCloudRelayRoute(pairingCode)",
  );
  assert.match(resetFlow, /cloudRelayTransport\.close\(\)/u);
  assert.match(resetFlow, /cloudRelayTransport = null/u);
  assert.match(resetFlow, /setFreshCloudRelayRouteRequirement\(true\)/u);
  assert.match(resetFlow, /invalidateCredential\(t\('repairPairingHint'\)\)/u);
  assert.doesNotMatch(resetFlow, /fetch\(|api\(/u, "the button must not remotely mint a code");
  assert.match(appScript, /sessionStorage\.setItem\(FRESH_CLOUD_ROUTE_REQUIRED_KEY, '1'\)/u);

  const bootstrap = extractAppSource(
    "async function bootstrapCloudRelayRoute(pairingCode)",
    "async function pairWithCode(candidate)",
  );
  assert.match(bootstrap, /if \(response\.ok\) \{\s*setFreshCloudRelayRouteRequirement\(false\)/u);
  assert.match(appScript, /bootstrapResponse && freshCloudRelayRouteRequired/u);
});

test("repair action is localized, hidden by default, and compact in expanded view", () => {
  assert.match(indexHtml, /id="repair-pairing-panel"[^>]*hidden/u);
  assert.match(indexHtml, /id="repair-pairing"[^>]*data-i18n="repairPairing"/u);
  assert.match(indexHtml, /#viewer:fullscreen #repair-pairing-panel/u);
  assert.match(indexHtml, /body\.theater-mode #repair-pairing-panel/u);
  assert.match(appScript, /repairPairing: 'Get a new pairing code'/u);
  assert.match(appScript, /repairPairing: '페어링 코드 다시 받기'/u);
});

test("bootstrap 404 explains registration and network state without retrying", () => {
  const pairingFlow = extractAppSource(
    "async function pairWithCode(candidate)",
    "function projectionContentRect()",
  );
  assert.equal(
    pairingFlow.match(/bootstrapCloudRelayRoute\(pairingCode\)/gu)?.length,
    1,
    "one user action must spend at most one bootstrap attempt",
  );
  assert.match(
    pairingFlow,
    /bootstrapRouteMissing[\s\S]*?t\('codeNotRegistered'\)/u,
  );
  assert.match(
    pairingFlow,
    /if \(response\.status === 401\) \{[\s\S]*?t\('invalidCode'\)/u,
  );
  assert.match(appScript, /codeNotRegistered: 'The phone has not registered this code yet\./u);
  assert.match(appScript, /codeNotRegistered: '휴대전화가 이 코드를 아직 등록하지 않았습니다\./u);
});

test("remembered route check separates invalid cookies from transient outages", () => {
  const routeCheck = extractAppSource(
    "async function checkRememberedCloudRelayRoute()",
    "function normalizePairingCode(candidate)",
  );
  const remembered = extractAppSource(
    "async function connectRemembered(credential)",
    "function projectionContentRect()",
  );
  assert.match(routeCheck, /response\.status === 204[\s\S]*?return 'valid'/u);
  assert.match(routeCheck, /response\.status === 401[\s\S]*?return 'invalid'/u);
  assert.match(routeCheck, /return 'transient'/u);
  assert.match(routeCheck, /credentials: 'include'/u);
  assert.match(routeCheck, /mode: 'same-origin'/u);
  assert.match(appScript, /routeStatusUrl: `\$\{location\.origin\}\$\{pathPrefix\}\/bootstrap\/route`/u);
  assert.match(routeCheck, /CLOUD_ROUTE_STATUS_TIMEOUT_MILLIS/u);
  assert.match(
    remembered,
    /routeState === 'invalid'[\s\S]*?resetCloudRelayRouteStateForRepair\(\);[\s\S]*?invalidateCredential/u,
  );
  assert.match(
    remembered,
    /routeState === 'valid'[\s\S]*?setFreshCloudRelayRouteRequirement\(false\)/u,
  );
  assert.doesNotMatch(
    remembered,
    /routeState === 'transient'[\s\S]*?invalidateCredential/u,
  );
});

test("pairing, signaling, and WebRTC expose bounded timing diagnostics", () => {
  assert.match(appScript, /NAVONWEB_TIMING scope=\$\{safeScope\.toLowerCase\(\)\}/u);
  assert.match(appScript, /recordConnectionTiming\('Pairing', 'bootstrap_start'/u);
  assert.match(appScript, /recordConnectionTiming\('Signal', 'websocket_open'/u);
  assert.match(appScript, /recordConnectionTiming\('WebRtc', 'connected'/u);
  assert.match(appScript, /pad\.dataset\[`navonweb\$\{safeScope\}Stage`\]/u);
  assert.doesNotMatch(
    extractAppSource("function recordConnectionTiming", "function loadFreshCloudRelayRouteRequirement"),
    /credential|pairingCode|roomId|routeNonce/u,
  );
});
