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

function extractRecoverySource() {
  const startMarker = "function scheduleWebRtcRecovery()";
  const endMarker = "\n  async function ensureWebRtcCapabilities";
  const start = appScript.indexOf(startMarker);
  const end = appScript.indexOf(endMarker, start);
  assert.notEqual(start, -1, "missing WebRTC recovery scheduler");
  assert.notEqual(end, -1, "missing WebRTC recovery scheduler end marker");
  return appScript.slice(start, end);
}

function extractCapabilitiesSource() {
  const startMarker = "async function ensureWebRtcCapabilities(force = false)";
  const endMarker = "\n  function createControlWebRtcChannel";
  const start = appScript.indexOf(startMarker);
  const end = appScript.indexOf(endMarker, start);
  assert.notEqual(start, -1, "missing WebRTC capabilities loader");
  assert.notEqual(end, -1, "missing WebRTC capabilities loader end marker");
  return appScript.slice(start, end);
}

function extractPollStatusSource() {
  const startMarker = "function pollStatus(options = {})";
  const endMarker = "\n  function statusPollTaskIsCurrent";
  const start = appScript.indexOf(startMarker);
  const end = appScript.indexOf(endMarker, start);
  assert.notEqual(start, -1, "missing status poll coalescer");
  assert.notEqual(end, -1, "missing status poll coalescer end marker");
  return appScript.slice(start, end);
}

function loadProjectionPreflightPolicy() {
  const startMarker = "function normalizeProjectionViewport(value)";
  const endMarker = "\n  function zeroProjectionViewport";
  const start = appScript.indexOf(startMarker);
  const end = appScript.indexOf(endMarker, start);
  assert.notEqual(start, -1, "missing projection viewport normalizer");
  assert.notEqual(end, -1, "missing projection preflight policy end marker");
  const context = vm.createContext({Object});
  vm.runInContext(`
    ${appScript.slice(start, end)}
    globalThis.ready = projectionReadyForWebRtcPreflight;
  `, context);
  return context.ready;
}

function viewportLayout(overrides = {}) {
  return {
    encodedWidth: 1920,
    encodedHeight: 1080,
    totalMarginWidth: 0,
    totalMarginHeight: 0,
    contentLeft: 0,
    contentTop: 0,
    contentWidth: 1920,
    contentHeight: 1080,
    densityDpi: 140,
    ...overrides,
  };
}

function fakeTimers() {
  let nextId = 1;
  const pending = new Map();
  return {
    clearTimeout(id) {
      pending.delete(id);
    },
    setTimeout(callback, milliseconds) {
      const id = nextId++;
      pending.set(id, {callback, milliseconds});
      return id;
    },
    count() {
      return pending.size;
    },
    nextDelay() {
      const entry = pending.values().next().value;
      return entry && entry.milliseconds;
    },
    async runNext() {
      const entry = pending.entries().next().value;
      assert.ok(entry, "expected a pending recovery timer");
      const [id, timer] = entry;
      pending.delete(id);
      await timer.callback();
      return timer.milliseconds;
    },
  };
}

function loadRecoveryRuntime(statusResults) {
  const timers = fakeTimers();
  const events = [];
  const context = vm.createContext({
    document: {hidden: false},
    WEBRTC_RECOVERY_BASE_DELAY_MILLIS: 1,
    WEBRTC_RECOVERY_CLOSE_GRACE_MILLIS: 0,
    WEBRTC_RECOVERY_MAX_DELAY_MILLIS: 8,
    clearTimeout: timers.clearTimeout,
    setTimeout: timers.setTimeout,
    statusResults: [...statusResults],
    events,
  });

  vm.runInContext(`
    let pageActive = true;
    let browserCredential = "credential";
    let androidAutoInteractive = true;
    let webRtcRecoveryTimer = 0;
    let webRtcRecoveryAttempts = 0;
    let webRtcRecoveryInFlight = false;
    let webRtcSessionCloseBarrier = null;
    let webRtcStarting = false;
    let webRtcPeer = null;
    let activeGeometry = "old";
    let attemptShouldFail = false;

    function isWebRtcConnected(peer) { return peer && peer.connectionState === "connected"; }
    function cancelWebRtcRecovery(resetAttempts = false) {
      if (webRtcRecoveryTimer) clearTimeout(webRtcRecoveryTimer);
      webRtcRecoveryTimer = 0;
      if (resetAttempts) webRtcRecoveryAttempts = 0;
    }
    function resetWebRtc() { webRtcPeer = null; webRtcStarting = false; }
    function delay() { return Promise.resolve(); }
    async function awaitWebRtcSessionCloseBarrier() {
      events.push("close-barrier");
      return true;
    }
    async function pollStatus(options) {
      events.push("status:" + String(options && options.webRtcPreflight === true));
      const result = statusResults.shift() || {ok: false};
      if (result.geometry) {
        activeGeometry = result.geometry;
        events.push("geometry:" + activeGeometry);
      }
      if (typeof result.interactive === "boolean") {
        androidAutoInteractive = result.interactive;
      }
      attemptShouldFail = result.attemptFails === true;
      return result.ok === true;
    }
    async function startWebRtc() {
      if (!await awaitWebRtcSessionCloseBarrier()) return false;
      const statusCurrent = await pollStatus({automatic: true, webRtcPreflight: true});
      if (!statusCurrent || !androidAutoInteractive) return false;
      events.push("offer:" + activeGeometry);
      if (attemptShouldFail) return true;
      webRtcPeer = {connectionState: "connected"};
      return true;
    }

    ${extractRecoverySource()}

    globalThis.schedule = scheduleWebRtcRecovery;
    globalThis.attempts = () => webRtcRecoveryAttempts;
  `, context);

  return {
    attempts: context.attempts,
    events,
    schedule: context.schedule,
    timers,
  };
}

test("recovery applies fresh source geometry before creating one offer", async () => {
  const runtime = loadRecoveryRuntime([{ok: true, geometry: "1920x1080", interactive: true}]);
  runtime.schedule();
  assert.equal(runtime.timers.count(), 1);

  await runtime.timers.runNext();

  assert.deepEqual(runtime.events, [
    "close-barrier",
    "status:true",
    "geometry:1920x1080",
    "offer:1920x1080",
  ]);
  assert.equal(runtime.timers.count(), 0);
  assert.equal(runtime.attempts(), 0);
});

test("failed or reconfiguring status defers offer to bounded recovery", async (t) => {
  for (const [name, result] of [
    ["503", {ok: false}],
    ["AA connected while viewport applying", {ok: false, interactive: true}],
    ["AA reconfiguring", {ok: true, interactive: false}],
  ]) {
    await t.test(name, async () => {
      const runtime = loadRecoveryRuntime([result]);
      runtime.schedule();
      await runtime.timers.runNext();

      assert.equal(runtime.events.some(event => event.startsWith("offer:")), false);
      assert.equal(runtime.timers.count(), result.ok === false ? 1 : 0);
      assert.equal(runtime.attempts(), 0);
      if (result.ok === false) assert.equal(runtime.timers.nextDelay(), 1);
    });
  }
});

test("only an admitted capability or signaling attempt consumes recovery backoff", async () => {
  const runtime = loadRecoveryRuntime([
    {ok: true, geometry: "1920x1080", interactive: true, attemptFails: true},
  ]);
  runtime.schedule();

  await runtime.timers.runNext();

  assert.equal(runtime.events.includes("offer:1920x1080"), true);
  assert.equal(runtime.attempts(), 1);
  assert.equal(runtime.timers.count(), 1);
  assert.equal(runtime.timers.nextDelay(), 2);
});

test("a transient null capabilities result is not cached across recovery attempts", async () => {
  const context = vm.createContext({});
  vm.runInContext(`
    let browserCredential = "credential";
    let projectionProfileRevision = 1;
    let webRtcCapabilitiesPromise = null;
    let webRtcServerCapabilities = null;
    let outputAudioWebRtcUnsupported = false;
    let apiCalls = 0;
    const RTCPeerConnection = function() {};
    const CODEC_NAMES = ["h264"];
    const AUDIO_TRACKS = ["media"];
    const CLOUD_RELAY_MODE = true;
    const pad = {dataset: {}};
    function normalizeIceServers() { return []; }
    function clearOutputAudioWebRtcRecovery() {}
    function invalidateCredential() {}
    function t() { return "expired"; }
    async function api() {
      apiCalls += 1;
      throw new Error("temporary status failure");
    }
    ${extractCapabilitiesSource()}
    globalThis.ensure = ensureWebRtcCapabilities;
    globalThis.calls = () => apiCalls;
  `, context);

  assert.equal(await context.ensure(), null);
  assert.equal(await context.ensure(), null);
  assert.equal(context.calls(), 2);
});

test("AA CONNECTED is not offer-ready until profile and viewport activation settle", () => {
  const ready = loadProjectionPreflightPolicy();
  const activeLayout = viewportLayout();
  const projection = {
    activationState: "active",
    viewport: {
      activationState: "active",
      activeLayout,
      requestedLayout: viewportLayout(),
    },
  };

  assert.equal(ready(projection), true);
  assert.equal(ready({...projection, activationState: "applying"}), false);
  assert.equal(ready({
    ...projection,
    viewport: {...projection.viewport, activationState: "applying"},
  }), false);
  assert.equal(ready({
    ...projection,
    viewport: {
      ...projection.viewport,
      requestedLayout: viewportLayout({
        totalMarginWidth: 200,
        contentLeft: 100,
        contentWidth: 1720,
      }),
    },
  }), false);
});

test("concurrent recovery requests coalesce into one status gate and one offer", async () => {
  const runtime = loadRecoveryRuntime([{ok: true, geometry: "1080x1920", interactive: true}]);
  runtime.schedule();
  runtime.schedule();
  assert.equal(runtime.timers.count(), 1);

  await runtime.timers.runNext();

  assert.equal(runtime.events.filter(event => event.startsWith("status:")).length, 1);
  assert.equal(runtime.events.filter(event => event.startsWith("offer:")).length, 1);
});

test("regular in-flight status cannot bypass applying projection readiness for preflight", async () => {
  const responses = [];
  const optionsSeen = [];
  const context = vm.createContext({
    AbortController,
    dispatchStatus(options) {
      optionsSeen.push(options);
      return responses.shift();
    },
  });
  vm.runInContext(`
    let browserCredential = "credential";
    let statusGeneration = 1;
    let statusPollTask = null;
    function performStatusPoll(options) { return dispatchStatus(options); }
    ${extractPollStatusSource()}
    globalThis.poll = pollStatus;
  `, context);

  const applying = deferredStatus();
  responses.push(applying.promise);
  const regularApplying = context.poll({automatic: true});
  const preflightApplying = context.poll({automatic: true, webRtcPreflight: true});
  assert.equal(regularApplying, preflightApplying);
  assert.equal(optionsSeen.length, 1);
  assert.equal(optionsSeen[0].webRtcPreflight, undefined);
  applying.resolve(false);
  assert.deepEqual(await Promise.all([regularApplying, preflightApplying]), [false, false]);
  let offers = 0;
  if (await preflightApplying) offers += 1;
  assert.equal(offers, 0);

  const active = deferredStatus();
  responses.push(active.promise);
  const regularActive = context.poll({automatic: true});
  const preflightActive = context.poll({automatic: true, webRtcPreflight: true});
  assert.equal(regularActive, preflightActive);
  active.resolve(true);
  if (await preflightActive) offers += 1;
  await regularActive;
  assert.equal(offers, 1);
  assert.equal(optionsSeen.length, 2);
});

function deferredStatus() {
  let resolve;
  const promise = new Promise(resolvePromise => {
    resolve = resolvePromise;
  });
  return {promise, resolve};
}

test("status gate is ordered before startWebRtc in the production recovery scheduler", () => {
  const source = extractRecoverySource();
  const startSource = appScript.match(/async function startWebRtc[\s\S]*?\n  \}/u)?.[0] ?? "";
  const admissionSource = appScript.match(
    /async function awaitWebRtcOfferAdmission[\s\S]*?\n  \}/u,
  )?.[0] ?? "";
  assert.match(source, /await startWebRtc\(\)/u);
  assert.ok(
    admissionSource.indexOf("await awaitWebRtcSessionCloseBarrier(") <
      admissionSource.indexOf("await pollStatus("),
  );
  assert.ok(
    startSource.indexOf("await awaitWebRtcOfferAdmission(") <
      startSource.indexOf("await ensureWebRtcCapabilities()"),
  );
  assert.match(source, /webRtcRecoveryInFlight = true;[\s\S]*await startWebRtc\(\)/u);
  assert.match(admissionSource, /statusCurrent &&[\s\S]*androidAutoInteractive/u);
  assert.match(
    appScript,
    /if \(!projectionReadyForWebRtcPreflight\(data\.projection\)\)/u,
  );
  assert.doesNotMatch(
    appScript,
    /options\.webRtcPreflight && !projectionReadyForWebRtcPreflight/u,
  );
});
