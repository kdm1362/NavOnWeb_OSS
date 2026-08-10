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

function extract(startMarker, endMarker) {
  const start = appScript.indexOf(startMarker);
  const end = appScript.indexOf(endMarker, start);
  assert.notEqual(start, -1, `missing start marker: ${startMarker}`);
  assert.notEqual(end, -1, `missing end marker: ${endMarker}`);
  return appScript.slice(start, end);
}

function loadStatusFailureRuntime({connected = true} = {}) {
  const peer = {connectionState: connected ? "connected" : "disconnected"};
  const control = {readyState: "open"};
  const microphone = {readyState: "open"};
  const output = {readyState: "open"};
  const video = {id: "video-stream"};
  const events = [];
  const context = vm.createContext({
    CLOUD_RELAY_MODE: true,
    webRtcPeer: peer,
    webRtcControlTransport: {isOpen: () => control.readyState === "open"},
    webRtcAudioChannels: new Map([["media", output]]),
    outputAudioWebRtcRecoveryRequired: false,
    pad: {dataset: {}},
    statusFailureCount: 0,
    video,
    control,
    microphone,
    output,
    events,
    isWebRtcConnected: value => value && value.connectionState === "connected",
    browserSessionCanControl: () => true,
    currentMicrophoneWebRtcChannel: () =>
      microphone.readyState === "open" ? microphone : null,
    showAuthenticatedView: () => events.push("authenticated"),
    scheduleRepairPairingAction: () => events.push("repair_scheduled"),
    setStreamState: state => events.push(`state:${state}`),
    renderAndroidAutoStatus: value => {
      events.push(`render:${String(value)}`);
      context.webRtcPeer = null;
      control.readyState = "closed";
      microphone.readyState = "closed";
      output.readyState = "closed";
    },
  });
  vm.runInContext(`
    ${extract(
      "function liveCloudDirectSessionSurvivesStatusOutage()",
      "async function performStatusPoll(options, task)",
    )}
    globalThis.failStatus = handleStatusPollFailure;
    globalThis.recoverStatus = consumeRecoveredStatusFailure;
    globalThis.failureCount = () => statusFailureCount;
  `, context);
  return {context, control, events, microphone, output, peer, video};
}

test("repeated signaling failures preserve a healthy direct media session and recovery", () => {
  const runtime = loadStatusFailureRuntime();

  assert.equal(runtime.context.failStatus(), true);
  assert.equal(runtime.context.failStatus(), true);
  assert.equal(runtime.context.failureCount(), 2);
  assert.equal(runtime.context.webRtcPeer, runtime.peer);
  assert.equal(runtime.context.video, runtime.video);
  assert.equal(runtime.control.readyState, "open");
  assert.equal(runtime.microphone.readyState, "open");
  assert.equal(runtime.output.readyState, "open");
  assert.equal(runtime.context.pad.dataset.navonwebSignalingState, "degraded");
  assert.equal(runtime.events.some(event => event.startsWith("render:")), false);

  assert.equal(runtime.context.recoverStatus(), true);
  assert.equal(runtime.context.failureCount(), 0);
  assert.equal("navonwebSignalingState" in runtime.context.pad.dataset, false);
  assert.equal(runtime.context.webRtcPeer, runtime.peer);
});

test("a non-live peer keeps the existing two-failure teardown policy", () => {
  const runtime = loadStatusFailureRuntime({connected: false});

  assert.equal(runtime.context.failStatus(), false);
  assert.equal(runtime.context.webRtcPeer, runtime.peer);
  assert.equal(runtime.context.failStatus(), false);
  assert.equal(runtime.context.webRtcPeer, null);
  assert.equal(runtime.control.readyState, "closed");
  assert.equal(runtime.microphone.readyState, "closed");
  assert.equal(runtime.output.readyState, "closed");
  assert.deepEqual(
    runtime.events.filter(event => event.startsWith("render:")),
    ["render:null"],
  );
});
