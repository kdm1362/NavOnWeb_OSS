import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import path from "node:path";
import test from "node:test";
import vm from "node:vm";

const cloudflareRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..", "..");
const repositoryRoot = path.resolve(cloudflareRoot, "..");
const appScript = readFileSync(
  path.join(repositoryRoot, "app", "src", "main", "assets", "tesla", "app.js"),
  "utf8",
);
const indexHtml = readFileSync(
  path.join(repositoryRoot, "app", "src", "main", "assets", "tesla", "index.html"),
  "utf8",
);

function sourceBetween(startMarker, endMarker) {
  const start = appScript.indexOf(startMarker);
  const end = appScript.indexOf(endMarker, start + startMarker.length);
  assert.ok(start >= 0, `missing source marker: ${startMarker}`);
  assert.ok(end > start, `missing source marker: ${endMarker}`);
  return appScript.slice(start, end);
}

test("foreground resumes JPEG and healthy WebRTC rendering without replacing either session", async () => {
  const frameResumeSource = sourceBetween(
    "  function resumeFramePollingAfterLifecyclePause()",
    "  function scheduleFramePoll(",
  );
  const mediaResumeSource = sourceBetween(
    "  function resumeProjectionMediaAfterLifecyclePause()",
    "  async function publishSelectedIcePair(",
  );
  let jpegPolls = 0;
  let videoPlayCalls = 0;
  let clearedTimer = 0;
  const context = vm.createContext({Promise});
  vm.runInContext(`
    const CLOUD_RELAY_MODE = false;
    const document = {hidden: false};
    const pad = {classList: {contains: () => false}};
    let browserCredential = "credential-a";
    let pageActive = true;
    let framePolling = true;
    let frameTimer = 17;
    let frameAbortController = null;
    const webRtcPeer = {connectionState: "connected"};
    const webRtcVideo = {
      srcObject: {id: "same-stream"},
      play() { globalThis.videoPlayCalls += 1; return Promise.resolve(); },
    };
    function clearTimeout(id) { globalThis.clearedTimer = id; }
    function pollFrame() { globalThis.jpegPolls += 1; }
    function startFramePolling() { throw new Error("existing reader must not be replaced"); }
    function isWebRtcConnected(peer) { return peer.connectionState === "connected"; }
    globalThis.jpegPolls = 0;
    globalThis.videoPlayCalls = 0;
    globalThis.clearedTimer = 0;
    ${frameResumeSource}
    ${mediaResumeSource}
    globalThis.resumeMedia = resumeProjectionMediaAfterLifecyclePause;
    globalThis.credential = () => browserCredential;
    globalThis.peer = () => webRtcPeer;
    globalThis.stream = () => webRtcVideo.srcObject;
  `, context);

  context.resumeMedia();
  await Promise.resolve();
  jpegPolls = context.jpegPolls;
  videoPlayCalls = context.videoPlayCalls;
  clearedTimer = context.clearedTimer;
  assert.equal(jpegPolls, 1);
  assert.equal(videoPlayCalls, 1);
  assert.equal(clearedTimer, 17);
  assert.equal(context.credential(), "credential-a");
  assert.equal(context.peer().connectionState, "connected");
  assert.equal(context.stream().id, "same-stream");
});

test("hidden visibility and pagehide preserve credential, JPEG reader, peer, and relay", () => {
  const visibilitySource = sourceBetween(
    "  document.addEventListener('visibilitychange'",
    "  function resumeSameOriginConnection()",
  );
  const pageHideSource = sourceBetween(
    "  window.addEventListener('pagehide'",
    "  window.addEventListener('pageshow'",
  );
  const pageShowSource = sourceBetween(
    "  window.addEventListener('pageshow'",
    "  document.addEventListener('freeze'",
  );
  const handlers = {};
  const relay = {closeCalls: 0, close() { this.closeCalls += 1; }};
  const context = vm.createContext({
    Promise,
    document: {
      hidden: true,
      addEventListener(type, handler) { handlers[`document:${type}`] = handler; },
    },
    window: {
      addEventListener(type, handler) { handlers[`window:${type}`] = handler; },
    },
  });
  vm.runInContext(`
    let pageActive = true;
    let browserCredential = "credential-a";
    let framePolling = true;
    let webRtcPeer = {connectionState: "connected"};
    let cloudRelayTransport = globalThis.relay;
    let audioContext = null;
    let audioUnlocked = true;
    const androidAutoInteractive = true;
    const relay = globalThis.relay;
    function noop() {}
    const syncScreenWakeLock = noop;
    const cancelRepairPairingCountdown = noop;
    const clearRemoteTouchMarkers = noop;
    const stopStatusPolling = noop;
    const microphoneCaptureIsTerminal = () => true;
    const stopMicrophoneCapture = noop;
    const stopAudioStreams = noop;
    const resetTouchTransport = noop;
    const cancelWebRtcRecovery = noop;
    const cancelOutputAudioWebRtcRecovery = noop;
    const statusPollingEligible = () => false;
    const resumeProjectionMediaAfterLifecyclePause = () => { globalThis.resumeCalls += 1; };
    const requestViewportControlReclaim = noop;
    const startStatusPolling = noop;
    const refreshLocalNetworkPermission = () => Promise.resolve();
    const scheduleWebRtcRecovery = noop;
    const syncMediaPermissionPanel = noop;
    const pollStatus = () => Promise.resolve(false);
    const scheduleOutputAudioWebRtcRecovery = noop;
    const releaseScreenWakeLock = noop;
    const hidePremiumPrompt = noop;
    const cancelNoticeRequestForRetry = noop;
    const stopViewportReporting = noop;
    const stopDevelopmentTeslaCycle = noop;
    const hideFullscreenHint = noop;
    const hidePresentationGuide = () => { globalThis.guideHideCalls += 1; };
    const setTheaterMode = noop;
    const startDevelopmentTeslaCycle = noop;
    const syncFullscreenState = noop;
    const scheduleViewportLayoutSync = noop;
    globalThis.resumeCalls = 0;
    globalThis.guideHideCalls = 0;
    ${visibilitySource}
    ${pageHideSource}
    ${pageShowSource}
    globalThis.snapshot = () => ({
      pageActive,
      browserCredential,
      framePolling,
      webRtcPeer,
      cloudRelayTransport,
    });
  `, Object.assign(context, {relay}));

  handlers["document:visibilitychange"]();
  handlers["window:pagehide"]({persisted: true});
  const snapshot = context.snapshot();
  assert.equal(snapshot.pageActive, false);
  assert.equal(snapshot.browserCredential, "credential-a");
  assert.equal(snapshot.framePolling, true);
  assert.equal(snapshot.webRtcPeer.connectionState, "connected");
  assert.equal(snapshot.cloudRelayTransport, relay);
  assert.equal(relay.closeCalls, 0);
  assert.equal(context.guideHideCalls, 1);

  context.document.hidden = false;
  handlers["window:pageshow"]({persisted: true});
  const resumed = context.snapshot();
  assert.equal(resumed.pageActive, true);
  assert.equal(resumed.browserCredential, "credential-a");
  assert.equal(resumed.framePolling, true);
  assert.equal(resumed.webRtcPeer.connectionState, "connected");
  assert.equal(context.resumeCalls, 1);
});

test("freeze is non-destructive and resume reuses the same media state", () => {
  const lifecycleSource = sourceBetween(
    "  document.addEventListener('freeze'",
    "  applyDocumentLocale();",
  );
  const handlers = {};
  let mediaResumes = 0;
  let connectionResumes = 0;
  const context = vm.createContext({
    document: {
      hidden: false,
      addEventListener(type, handler) { handlers[type] = handler; },
    },
  });
  vm.runInContext(`
    let pageActive = true;
    let browserCredential = "credential-a";
    let framePolling = true;
    let webRtcPeer = {connectionState: "connected"};
    function releaseScreenWakeLock() {}
    function cancelPointerInteraction() {}
    function resumeProjectionMediaAfterLifecyclePause() { globalThis.mediaResumes += 1; }
    function resumeSameOriginConnection() { globalThis.connectionResumes += 1; }
    globalThis.mediaResumes = 0;
    globalThis.connectionResumes = 0;
    ${lifecycleSource}
    globalThis.snapshot = () => ({browserCredential, framePolling, webRtcPeer});
  `, context);

  handlers.freeze();
  handlers.resume();
  mediaResumes = context.mediaResumes;
  connectionResumes = context.connectionResumes;
  const snapshot = context.snapshot();
  assert.equal(mediaResumes, 1);
  assert.equal(connectionResumes, 1);
  assert.equal(snapshot.browserCredential, "credential-a");
  assert.equal(snapshot.framePolling, true);
  assert.equal(snapshot.webRtcPeer.connectionState, "connected");
});

test("non-BFCache pagehide explicitly releases media and signaling session slots", () => {
  const pageHideSource = sourceBetween(
    "  window.addEventListener('pagehide'",
    "  window.addEventListener('pageshow'",
  );
  const handlers = {};
  const relay = {closeCalls: 0, close() { this.closeCalls += 1; }};
  const context = vm.createContext({
    window: {addEventListener(type, handler) { handlers[type] = handler; }},
    relay,
  });
  vm.runInContext(`
    let pageActive = true;
    let browserCredential = "credential-a";
    let framePolling = true;
    let webRtcPeer = {connectionState: "connected"};
    let cloudRelayTransport = globalThis.relay;
    let audioContext = null;
    let audioUnlocked = true;
    function noop() {}
    const releaseScreenWakeLock = noop;
    const cancelRepairPairingCountdown = noop;
    const hidePremiumPrompt = noop;
    const cancelNoticeRequestForRetry = noop;
    const resetTouchTransport = noop;
    const stopViewportReporting = noop;
    const stopDevelopmentTeslaCycle = noop;
    const stopStatusPolling = noop;
    const microphoneCaptureIsTerminal = () => true;
    const stopMicrophoneCapture = noop;
    const stopAudioStreams = noop;
    const syncMediaPermissionPanel = noop;
    const hideFullscreenHint = noop;
    const hidePresentationGuide = () => { globalThis.guideHideCalls += 1; };
    const setTheaterMode = noop;
    const cancelWebRtcRecovery = noop;
    function stopFramePolling() { framePolling = false; }
    function resetWebRtc() { webRtcPeer = null; }
    globalThis.guideHideCalls = 0;
    ${pageHideSource}
    globalThis.snapshot = () => ({
      pageActive,
      browserCredential,
      framePolling,
      webRtcPeer,
      cloudRelayTransport,
    });
  `, context);

  handlers.pagehide({persisted: false});
  const snapshot = context.snapshot();
  assert.equal(snapshot.pageActive, false);
  assert.equal(snapshot.browserCredential, "credential-a");
  assert.equal(snapshot.framePolling, false);
  assert.equal(snapshot.webRtcPeer, null);
  assert.equal(snapshot.cloudRelayTransport, null);
  assert.equal(relay.closeCalls, 1);
  assert.equal(context.guideHideCalls, 1);
});

test("background lifecycle preserves media while explicit unload cleans it up", () => {
  const backgroundSource = sourceBetween(
    "  document.addEventListener('visibilitychange'",
    "  window.addEventListener('pagehide'",
  );
  const freezeSource = sourceBetween(
    "  document.addEventListener('freeze'",
    "  applyDocumentLocale();",
  );
  const pageHideSource = sourceBetween(
    "  window.addEventListener('pagehide'",
    "  window.addEventListener('pageshow'",
  );
  for (const source of [backgroundSource, freezeSource]) {
    assert.doesNotMatch(source, /stopFramePolling\(/u);
    assert.doesNotMatch(source, /resetWebRtc\(/u);
    assert.doesNotMatch(source, /cloudRelayTransport\.close\(/u);
    assert.doesNotMatch(source, /browserCredential\s*=(?!=)/u);
  }
  assert.match(pageHideSource, /if \(!event\.persisted\)/u);
  assert.match(pageHideSource, /stopFramePolling\(false\)/u);
  assert.match(pageHideSource, /resetWebRtc\(true\)/u);
  assert.match(pageHideSource, /cloudRelayTransport\.close\(\)/u);
  assert.match(indexHtml, /<video id="webrtc-video" autoplay muted playsinline><\/video>/u);
});
