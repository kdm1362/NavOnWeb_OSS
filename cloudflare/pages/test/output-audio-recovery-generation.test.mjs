import assert from "node:assert/strict";
import {readFileSync} from "node:fs";
import {fileURLToPath} from "node:url";
import path from "node:path";
import test from "node:test";
import vm from "node:vm";

const cloudflareRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..", "..");
const appScript = readFileSync(
  path.resolve(cloudflareRoot, "..", "app", "src", "main", "assets", "tesla", "app.js"),
  "utf8",
);

function extractOutputAudioRecoverySource() {
  const startMarker = "function disposeOutputAudioWebRtcChannels()";
  const endMarker = "\n  function parseOutputAudioWebRtcFrame";
  const start = appScript.indexOf(startMarker);
  const end = appScript.indexOf(endMarker, start);
  assert.notEqual(start, -1, "missing output-audio channel lifecycle");
  assert.notEqual(end, -1, "missing output-audio channel lifecycle end marker");
  return appScript.slice(start, end);
}

function fakeTimers() {
  let nextId = 1;
  const active = new Map();
  const archived = new Map();
  return {
    clearTimeout(id) {
      active.delete(id);
    },
    setTimeout(callback, milliseconds) {
      const id = nextId++;
      const timer = {callback, milliseconds};
      active.set(id, timer);
      archived.set(id, timer);
      return id;
    },
    count() {
      return active.size;
    },
    ids() {
      return [...active.keys()];
    },
    milliseconds(id) {
      return (active.get(id) ?? archived.get(id))?.milliseconds;
    },
    run(id) {
      const timer = active.get(id);
      assert.ok(timer, `expected active timer ${id}`);
      active.delete(id);
      timer.callback();
    },
    runArchived(id) {
      const timer = archived.get(id);
      assert.ok(timer, `expected archived timer ${id}`);
      timer.callback();
    },
  };
}

function createAudioChannel(readyState = "connecting") {
  return {
    readyState,
    closeCalls: 0,
    onopen: null,
    onclose: null,
    onerror: null,
    onmessage: null,
    close() {
      this.closeCalls += 1;
      this.readyState = "closed";
      if (this.onclose) this.onclose();
    },
  };
}

function loadRuntime() {
  const timers = fakeTimers();
  const context = vm.createContext({
    AUDIO_WEBRTC_OPEN_TIMEOUT_MILLIS: 5000,
    AUDIO_WEBRTC_RECOVERY_BASE_DELAY_MILLIS: 2000,
    AUDIO_WEBRTC_RECOVERY_MAX_DELAY_MILLIS: 30000,
    CLOUD_RELAY_MODE: true,
    console: {warn() {}},
    document: {hidden: false},
    pad: {dataset: {}},
    window: {
      clearTimeout: timers.clearTimeout,
      setTimeout: timers.setTimeout,
    },
  });

  vm.runInContext(`
    let outputAudioWebRtcRecoveryRequired = false;
    let outputAudioWebRtcUnsupported = false;
    let outputAudioWebRtcRecoveryTimer = 0;
    let outputAudioWebRtcRecoveryGeneration = 0;
    let outputAudioWebRtcRecoveryAttempts = 0;
    let webRtcGeneration = 1;
    let webRtcPeer = {id: "old"};
    let webRtcStarting = false;
    let pageActive = true;
    let browserCredential = "credential";
    let androidAutoInteractive = true;
    let fullRestartCalls = 0;
    let webRtcRecoveryCalls = 0;
    const webRtcAudioChannels = new Map();
    const webRtcAudioOpenTimers = new Map();
    const audioStreams = new Map();

    function scheduleWebRtcRecovery() { webRtcRecoveryCalls += 1; }

    ${extractOutputAudioRecoverySource()}

    function resetPeer() {
      clearOutputAudioWebRtcRecovery(false);
      webRtcGeneration += 1;
      disposeOutputAudioWebRtcChannels();
      webRtcPeer = null;
      webRtcStarting = false;
    }

    function failWebRtc() {
      fullRestartCalls += 1;
      resetPeer();
    }

    globalThis.attach = (track, channel) => {
      channel.onclose = () => failOutputAudioWebRtcChannel(
        channel, webRtcGeneration, track, "closed"
      );
      channel.onerror = () => failOutputAudioWebRtcChannel(
        channel, webRtcGeneration, track, "failed"
      );
      channel.onopen = () => {
        const openTimer = webRtcAudioOpenTimers.get(track);
        if (openTimer !== undefined) window.clearTimeout(openTimer);
        webRtcAudioOpenTimers.delete(track);
        confirmOutputAudioWebRtcChannelsOpen();
      };
      webRtcAudioChannels.set(track, channel);
    };
    globalThis.armOpenTimers = () => armOutputAudioWebRtcOpenTimers(webRtcGeneration);
    globalThis.confirmOpen = confirmOutputAudioWebRtcChannelsOpen;
    globalThis.dispose = disposeOutputAudioWebRtcChannels;
    globalThis.fail = (track, channel, reason = "closed") =>
      failOutputAudioWebRtcChannel(channel, webRtcGeneration, track, reason);
    globalThis.reset = resetPeer;
    globalThis.schedule = scheduleOutputAudioWebRtcRecovery;
    globalThis.startReplacement = () => {
      webRtcPeer = {id: "replacement"};
      webRtcStarting = true;
    };
    globalThis.state = () => ({
      attempts: outputAudioWebRtcRecoveryAttempts,
      fullRestartCalls,
      generation: webRtcGeneration,
      recoveryGeneration: outputAudioWebRtcRecoveryGeneration,
      recoveryRequired: outputAudioWebRtcRecoveryRequired,
      webRtcRecoveryCalls,
    });
  `, context);

  return {
    armOpenTimers: context.armOpenTimers,
    attach: context.attach,
    confirmOpen: context.confirmOpen,
    dispose: context.dispose,
    fail: context.fail,
    reset: context.reset,
    schedule: context.schedule,
    startReplacement: context.startReplacement,
    state: context.state,
    timers,
  };
}

test("rotation reset consumes old audio recovery before the replacement offer", () => {
  const runtime = loadRuntime();
  const oldChannel = createAudioChannel("open");
  runtime.attach("media", oldChannel);
  runtime.fail("media", oldChannel);
  const [oldTimer] = runtime.timers.ids();
  assert.equal(runtime.timers.milliseconds(oldTimer), 2000);

  runtime.reset();
  runtime.startReplacement();
  runtime.schedule();

  assert.equal(runtime.timers.count(), 0, "CONNECTED status must not re-arm old generation audio retry");
  const state = runtime.state();
  assert.equal(state.attempts, 0);
  assert.equal(state.fullRestartCalls, 0);
  assert.equal(state.generation, 2);
  assert.equal(state.recoveryGeneration, 0);
  assert.equal(state.recoveryRequired, false);
  assert.equal(state.webRtcRecoveryCalls, 0);
});

test("already queued old-generation recovery callback cannot close a replacement peer", () => {
  const runtime = loadRuntime();
  const oldChannel = createAudioChannel("open");
  runtime.attach("media", oldChannel);
  runtime.fail("media", oldChannel);
  const [oldTimer] = runtime.timers.ids();

  runtime.reset();
  runtime.startReplacement();
  runtime.timers.runArchived(oldTimer);

  assert.equal(runtime.state().fullRestartCalls, 0);
  assert.equal(runtime.state().generation, 2);
});

test("same-generation explicit close schedules exactly one bounded full restart", () => {
  const runtime = loadRuntime();
  const channel = createAudioChannel("open");
  runtime.attach("media", channel);

  runtime.fail("media", channel);
  runtime.fail("media", channel);
  assert.equal(runtime.timers.count(), 1);
  const [timer] = runtime.timers.ids();
  runtime.timers.run(timer);

  assert.equal(runtime.state().fullRestartCalls, 1);
  assert.equal(runtime.timers.count(), 0);
});

test("current generation unopened channel uses post-connect open timeout then bounded restart", () => {
  const runtime = loadRuntime();
  const channel = createAudioChannel("connecting");
  runtime.attach("media", channel);

  runtime.armOpenTimers();
  const [openTimer] = runtime.timers.ids();
  assert.equal(runtime.timers.milliseconds(openTimer), 5000);
  runtime.timers.run(openTimer);

  assert.equal(runtime.state().recoveryRequired, true);
  assert.equal(runtime.state().recoveryGeneration, 1);
  assert.equal(runtime.timers.count(), 1);
  const [recoveryTimer] = runtime.timers.ids();
  assert.equal(runtime.timers.milliseconds(recoveryTimer), 2000);
  runtime.timers.run(recoveryTimer);
  assert.equal(runtime.state().fullRestartCalls, 1);
});

test("all current channels open clears recovery generation, timer, and attempts", () => {
  const runtime = loadRuntime();
  const failed = createAudioChannel("open");
  runtime.attach("media", failed);
  runtime.fail("media", failed);
  assert.equal(runtime.state().recoveryRequired, true);

  runtime.reset();
  runtime.startReplacement();
  const media = createAudioChannel("open");
  const speech = createAudioChannel("open");
  runtime.attach("media", media);
  runtime.attach("speech", speech);
  runtime.confirmOpen();

  assert.equal(runtime.state().recoveryRequired, false);
  assert.equal(runtime.state().recoveryGeneration, 0);
  assert.equal(runtime.state().attempts, 0);
  assert.equal(runtime.timers.count(), 0);
});

test("one failed track cannot be hidden when the remaining tracks open", () => {
  const runtime = loadRuntime();
  const media = createAudioChannel("open");
  const speech = createAudioChannel("connecting");
  const system = createAudioChannel("connecting");
  runtime.attach("media", media);
  runtime.attach("speech", speech);
  runtime.attach("system", system);

  runtime.fail("media", media);
  speech.readyState = "open";
  speech.onopen();
  system.readyState = "open";
  system.onopen();

  assert.equal(runtime.state().recoveryRequired, true);
  assert.equal(runtime.state().recoveryGeneration, 1);
  assert.equal(runtime.timers.count(), 1);
  runtime.timers.run(runtime.timers.ids()[0]);
  assert.equal(runtime.state().fullRestartCalls, 1);
});

test("disposing old channels detaches close handlers before closing them", () => {
  const runtime = loadRuntime();
  const channel = createAudioChannel("open");
  runtime.attach("media", channel);

  runtime.dispose();

  assert.equal(channel.closeCalls, 1);
  assert.equal(runtime.state().recoveryRequired, false);
  assert.equal(runtime.state().fullRestartCalls, 0);
  assert.equal(runtime.timers.count(), 0);
});

test("runtime reset and channel creation consume stale output-audio recovery", () => {
  const resetSource = appScript.match(/function resetWebRtc[\s\S]*?\n  \}/u)?.[0] ?? "";
  const createSource = appScript.match(
    /function createOutputAudioWebRtcChannels[\s\S]*?\n  \}/u,
  )?.[0] ?? "";
  assert.match(resetSource, /clearOutputAudioWebRtcRecovery\(false\)/u);
  assert.match(createSource, /clearOutputAudioWebRtcRecovery\(false\)/u);
  assert.match(
    appScript,
    /outputAudioWebRtcRecoveryGeneration !== recoveryGeneration[\s\S]*recoveryGeneration !== webRtcGeneration/u,
  );
});
