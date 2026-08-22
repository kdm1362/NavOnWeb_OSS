import assert from "node:assert/strict";
import {readFileSync} from "node:fs";
import {fileURLToPath} from "node:url";
import path from "node:path";
import test from "node:test";
import vm from "node:vm";

const cloudflareRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..", "..");
const appScript = readFileSync(
  path.resolve(cloudflareRoot, "..", "app", "src", "main", "assets", "web", "app.js"),
  "utf8",
);

function extractCloseBarrierSource() {
  const startMarker = "function enqueueWebRtcSessionClose";
  const endMarker = "\n  function resetWebRtc";
  const start = appScript.indexOf(startMarker);
  const end = appScript.indexOf(endMarker, start);
  assert.notEqual(start, -1, "missing WebRTC session close barrier");
  assert.notEqual(end, -1, "missing WebRTC session close barrier end marker");
  return appScript.slice(start, end);
}

function deferred() {
  let resolve;
  let reject;
  const promise = new Promise((resolvePromise, rejectPromise) => {
    resolve = resolvePromise;
    reject = rejectPromise;
  });
  return {promise, reject, resolve};
}

function fakeTimers() {
  let nextId = 1;
  const active = new Map();
  return {
    clearTimeout(id) {
      active.delete(id);
    },
    setTimeout(callback, milliseconds) {
      const id = nextId++;
      active.set(id, {callback, milliseconds});
      return id;
    },
    count() {
      return active.size;
    },
    milliseconds(id) {
      return active.get(id)?.milliseconds;
    },
    run(id) {
      const timer = active.get(id);
      assert.ok(timer, `expected active timer ${id}`);
      active.delete(id);
      timer.callback();
    },
    ids() {
      return [...active.keys()];
    },
  };
}

function loadRuntime(respond) {
  const timers = fakeTimers();
  const calls = [];
  const context = vm.createContext({
    AbortController,
    document: {hidden: false},
    STATUS_REQUEST_TIMEOUT_MILLIS: 5000,
    WEBRTC_SESSION_PATTERN: /^[A-Za-z0-9_-]{16,64}$/u,
    api(pathname, options, credential) {
      const call = {credential, options, pathname};
      calls.push(call);
      return respond(call, calls.length - 1);
    },
    window: {
      clearTimeout: timers.clearTimeout,
      setTimeout: timers.setTimeout,
    },
  });

  vm.runInContext(`
    let webRtcSessionCloseBarrier = null;
    let pageActive = true;
    let browserCredential = "credential";
    let androidAutoInteractive = true;
    let webRtcStarting = false;
    let webRtcPeer = null;
    let projectionStatusReady = true;
    function pollStatus() { return Promise.resolve(projectionStatusReady); }
    ${extractCloseBarrierSource()}
    globalThis.enqueue = enqueueWebRtcSessionClose;
    globalThis.awaitBarrier = awaitWebRtcSessionCloseBarrier;
    globalThis.admitOffer = awaitWebRtcOfferAdmission;
    globalThis.clearBarriers = clearWebRtcSessionCloseBarriers;
    globalThis.hasBarrier = () => webRtcSessionCloseBarrier !== null;
    globalThis.setCredential = value => { browserCredential = value; };
    globalThis.setProjectionStatusReady = value => { projectionStatusReady = value; };
  `, context);

  return {
    awaitBarrier: context.awaitBarrier,
    admitOffer: context.admitOffer,
    calls,
    clearBarriers: context.clearBarriers,
    enqueue: context.enqueue,
    hasBarrier: context.hasBarrier,
    setCredential: context.setCredential,
    setProjectionStatusReady: context.setProjectionStatusReady,
    timers,
  };
}

const firstSession = "session_abcdefghijkl";
const secondSession = "session_bcdefghijklm";
const credential = "credential";

test("replacement admission waits for the matching DELETE acknowledgement", async () => {
  const response = deferred();
  const runtime = loadRuntime(() => response.promise);
  runtime.enqueue(firstSession, credential);

  let admitted = false;
  const admission = runtime.awaitBarrier(credential).then(value => {
    admitted = value;
  });
  await Promise.resolve();
  assert.equal(admitted, false);
  assert.equal(runtime.calls.length, 1);

  response.resolve({status: 204});
  await admission;
  assert.equal(admitted, true);
  assert.equal(runtime.hasBarrier(), false);
});

test("every direct offer entry remains blocked until matching close admission", async () => {
  const response = deferred();
  const runtime = loadRuntime(() => response.promise);
  runtime.enqueue(firstSession, credential);
  let offers = 0;
  const directStart = (async () => {
    if (await runtime.admitOffer(credential)) offers += 1;
  })();
  await Promise.resolve();
  assert.equal(offers, 0);

  response.resolve({status: 404});
  await directStart;
  assert.equal(offers, 1);
});

test("direct user offer entry is rejected while projection activation is applying", async () => {
  const runtime = loadRuntime(() => Promise.resolve({status: 404}));
  runtime.setProjectionStatusReady(false);
  let offers = 0;

  if (await runtime.admitOffer(credential)) offers += 1;

  assert.equal(offers, 0);
  runtime.setProjectionStatusReady(true);
  if (await runtime.admitOffer(credential)) offers += 1;
  assert.equal(offers, 1);
});

test("already-closed 404 releases the barrier immediately", async () => {
  const runtime = loadRuntime(() => Promise.resolve({status: 404}));
  runtime.enqueue(firstSession, credential);

  assert.equal(await runtime.awaitBarrier(credential), true);
  assert.equal(runtime.hasBarrier(), false);
});

test("hung DELETE is aborted at the bounded five-second admission limit", async () => {
  const runtime = loadRuntime(call => new Promise((resolve, reject) => {
    call.options.signal.addEventListener("abort", () => reject(new Error("aborted")), {once: true});
  }));
  runtime.enqueue(firstSession, credential);
  let offers = 0;
  const admission = runtime.admitOffer(credential).then(admitted => {
    if (admitted) offers += 1;
    return admitted;
  });
  const [timeout] = runtime.timers.ids();
  assert.equal(runtime.timers.milliseconds(timeout), 5000);

  runtime.timers.run(timeout);

  assert.equal(await admission, true);
  assert.equal(offers, 1);
  assert.equal(runtime.calls[0].options.signal.aborted, true);
  assert.equal(runtime.hasBarrier(), false);
});

test("different credential is never delayed by a stale close barrier", async () => {
  const response = deferred();
  const runtime = loadRuntime(() => response.promise);
  runtime.enqueue(firstSession, "old-credential");
  runtime.setCredential("new-credential");

  assert.equal(await runtime.awaitBarrier("new-credential"), true);
  assert.equal(await runtime.admitOffer("new-credential"), true);
  assert.equal(await runtime.admitOffer("old-credential"), false);
  assert.equal(runtime.hasBarrier(), true);

  runtime.clearBarriers();
  response.resolve({status: 204});
});

test("completion of an older close cannot clear a newer close entry", async () => {
  const first = deferred();
  const second = deferred();
  const runtime = loadRuntime((_call, index) => index === 0 ? first.promise : second.promise);
  runtime.enqueue(firstSession, credential);
  runtime.enqueue(secondSession, credential);
  const admission = runtime.awaitBarrier(credential);
  assert.equal(runtime.calls.length, 1, "same-credential DELETE requests remain ordered");

  first.resolve({status: 204});
  await new Promise(resolve => setImmediate(resolve));
  assert.equal(runtime.calls.length, 2);
  assert.equal(runtime.hasBarrier(), true);

  second.resolve({status: 204});
  assert.equal(await admission, true);
  assert.equal(runtime.hasBarrier(), false);
});

test("credential invalidation aborts and clears every pending close", async () => {
  const runtime = loadRuntime(call => new Promise((resolve, reject) => {
    call.options.signal.addEventListener("abort", () => reject(new Error("aborted")), {once: true});
  }));
  runtime.enqueue(firstSession, credential);
  runtime.enqueue(secondSession, credential);

  runtime.clearBarriers();
  await Promise.resolve();

  assert.equal(runtime.hasBarrier(), false);
  assert.equal(runtime.calls[0].options.signal.aborted, true);
  assert.equal(runtime.calls.length, 1, "aborted predecessor prevents the queued DELETE from starting");
});

test("production offer admission waits for transport-independent DELETE and stable status", () => {
  const closeSource = extractCloseBarrierSource();
  const recoverySource = appScript.match(/function scheduleWebRtcRecovery[\s\S]*?\n  \}/u)?.[0] ?? "";
  const startSource = appScript.match(/async function startWebRtc[\s\S]*?\n  \}/u)?.[0] ?? "";
  assert.match(closeSource, /await api\(/u);
  assert.doesNotMatch(closeSource, /cloudRequest|cloudRelayTransport/u);
  assert.match(closeSource, /STATUS_REQUEST_TIMEOUT_MILLIS/u);
  assert.match(recoverySource, /await startWebRtc\(\)/u);
  assert.match(startSource, /const requestedCredential = browserCredential;/u);
  assert.ok(
    startSource.indexOf("await awaitWebRtcOfferAdmission(requestedCredential)") <
      startSource.indexOf("await ensureWebRtcCapabilities()"),
  );
  assert.match(closeSource, /await pollStatus\(\{automatic: true, webRtcPreflight: true\}\)/u);
  assert.doesNotMatch(appScript, /queueMicrotask\(\(\) => startWebRtc\(\)\)/u);
});
