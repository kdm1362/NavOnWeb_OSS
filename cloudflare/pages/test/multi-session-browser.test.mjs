import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import path from "node:path";
import test from "node:test";
import vm from "node:vm";
import { fileURLToPath } from "node:url";

const assetRoot = path.resolve(
  path.dirname(fileURLToPath(import.meta.url)),
  "../../../app/src/main/assets/tesla",
);
const appScript = readFileSync(path.join(assetRoot, "app.js"), "utf8");
const indexHtml = readFileSync(path.join(assetRoot, "index.html"), "utf8");

function extract(startMarker, endMarker) {
  const start = appScript.indexOf(startMarker);
  assert.notEqual(start, -1, `missing start marker: ${startMarker}`);
  const end = appScript.indexOf(endMarker, start);
  assert.notEqual(end, -1, `missing end marker: ${endMarker}`);
  return appScript.slice(start, end);
}

test("browser session metadata accepts only bounded public values", () => {
  const context = vm.createContext({
    SESSION_DEVICE_ID_PATTERN: /^[A-Za-z0-9_-]{8,64}$/,
    Object,
    String,
    Number,
  });
  vm.runInContext(`
    ${extract("function normalizeBrowserSession(value)", "function cancelViewportAuthority()")}
    globalThis.normalize = normalizeBrowserSession;
  `, context);

  assert.deepEqual(
    JSON.parse(JSON.stringify(context.normalize({
      deviceId: "device_12345678",
      access: "read_only",
      role: "viewer",
      colorSlot: 2,
    }))),
    {deviceId: "device_12345678", access: "read_only", role: "viewer", colorSlot: 2},
  );
  assert.equal(context.normalize({
    deviceId: "short", access: "control", role: "main", colorSlot: 0,
  }), null);
  assert.equal(context.normalize({
    deviceId: "device_12345678", access: "control", role: "main", colorSlot: 3,
  }), null);
  assert.equal(context.normalize({
    deviceId: "device_12345678", access: "read_only", role: "main", colorSlot: 0,
  }), null);
});

test("non-main viewport authority notice updates only when session state changes", () => {
  let hiddenWrites = 0;
  let textWrites = 0;
  let hidden = true;
  let textContent = "";
  const notice = {
    get hidden() { return hidden; },
    set hidden(value) { hidden = value; hiddenWrites += 1; },
    get textContent() { return textContent; },
    set textContent(value) { textContent = value; textWrites += 1; },
  };
  let layoutSchedules = 0;
  const context = vm.createContext({
    notice,
    viewer: {hidden: false},
    VIEWPORT_AUTHORITY_NOTICE_EXPANDED_MILLIS: 3000,
    window: {setTimeout() { throw new Error("standard view must not start a timer"); }, clearTimeout() {}},
    translated: "This device is not the main session. The projection aspect ratio and viewport follow the main session selected on your phone.",
    onSchedule: () => { layoutSchedules += 1; },
    expandedViewActive: () => false,
    Boolean,
  });
  vm.runInContext(`
    let browserCredential = "remembered";
    let browserSessionMetadataSupported = true;
    let browserSessionRole = "viewer";
    let viewportAuthorityRejected = false;
    let viewportAuthorityNoticeState = "hidden";
    let viewportAuthorityNoticeTimer = 0;
    let viewportAuthorityNoticeTimerGeneration = 0;
    let viewportAuthorityNoticeEligibleState = false;
    let viewportAuthorityNoticeExpandedState = false;
    const viewportAuthorityNotice = notice;
    function t() { return translated; }
    function scheduleViewportLayoutSync() { onSchedule(); }
    ${extract("function viewportAuthorityNoticeEligible()", "function normalizeBrowserSession(value)")}
    globalThis.syncNotice = syncViewportAuthorityNotice;
    globalThis.setRole = role => { browserSessionRole = role; };
    globalThis.setRejected = value => { viewportAuthorityRejected = value; };
    globalThis.setMetadataSupported = value => { browserSessionMetadataSupported = value; };
  `, context);

  assert.equal(context.syncNotice(), true);
  assert.equal(hidden, false);
  assert.equal(textContent, context.translated);
  assert.equal(context.syncNotice(), false);
  assert.equal(hiddenWrites, 1);
  assert.equal(textWrites, 1);
  assert.equal(layoutSchedules, 1);

  context.setRole("main");
  assert.equal(context.syncNotice(), true);
  assert.equal(hidden, true);
  assert.equal(context.syncNotice(), false);
  assert.equal(layoutSchedules, 2);

  context.setRejected(true);
  assert.equal(context.syncNotice(), false);
  assert.equal(hidden, true);
  assert.equal(context.syncNotice(), false);
  assert.equal(layoutSchedules, 2);

  context.setRole("viewer");
  assert.equal(context.syncNotice(), true);
  assert.equal(hidden, false);
  assert.equal(textWrites, 2);
  assert.equal(layoutSchedules, 3);

  context.setRejected(false);
  context.setMetadataSupported(false);
  assert.equal(context.syncNotice(), true);
  assert.equal(hidden, true);
  assert.equal(layoutSchedules, 4);

  assert.match(
    appScript,
    /viewportMainSessionOnly: 'This device is not the main session\. The projection aspect ratio and viewport follow the main session selected on your phone\.'/u,
  );
  assert.match(
    appScript,
    /viewportMainSessionOnly: '이 기기는 메인 세션이 아닙니다\. 프로젝션 종횡비와 화면 크기는 휴대전화에서 지정한 메인 세션을 따릅니다\.'/u,
  );
  assert.match(
    indexHtml,
    /id="viewport-authority-notice" role="status" aria-live="polite" aria-atomic="true" data-i18n="viewportMainSessionOnly" hidden/u,
  );
  assert.match(
    indexHtml,
    /#viewer:fullscreen #viewport-authority-notice,[\s\S]*body\.theater-mode #viewport-authority-notice \{[\s\S]*position: absolute;/u,
  );
  const applySession = extract("function applyBrowserSession(value)", "function resetBrowserSessionState()");
  assert.match(applySession, /browserSessionMetadataSupported = normalized !== null;/u);
  assert.match(
    applySession,
    /if \(normalized && normalized\.role === 'main'\) viewportAuthorityRejected = false;/u,
  );
  assert.match(applySession, /syncViewportAuthorityNotice\(\);/u);

  const viewportReport = extract(
    "async function reportProjectionViewport()",
    "function preapplyExpandedProjectionViewport()",
  );
  assert.match(
    viewportReport,
    /if \(conflict\.error === 'main_session_required'\) \{\s*viewportAuthorityRejected = true;\s*cancelViewportAuthority\(\);\s*syncViewportAuthorityNotice\(\);\s*void pollStatus\(\{automatic: true\}\);/u,
  );
});

test("touch-presence events are normalized, bounded, and versioned by sequence", () => {
  const context = vm.createContext({
    SESSION_DEVICE_ID_PATTERN: /^[A-Za-z0-9_-]{8,64}$/,
    Object,
    String,
    Number,
  });
  vm.runInContext(`
    ${extract("function normalizeTouchPresenceEnvelope(envelope)", "function renderTouchPresence(envelope)")}
    globalThis.normalize = normalizeTouchPresenceEnvelope;
  `, context);
  const valid = context.normalize({
    type: "session_event",
    event: "touch_presence",
    sourceDeviceId: "device_abcdefgh",
    colorSlot: 1,
    phase: "move",
    x: 0.25,
    y: 0.75,
    sequence: 42,
  });
  assert.equal(valid?.phase, "move");
  assert.equal(valid?.colorSlot, 1);
  for (const invalid of [
    {...valid, type: "rpc_response"},
    {...valid, x: -0.01},
    {...valid, y: 1.01},
    {...valid, phase: "tap"},
    {...valid, sequence: Number.MAX_SAFE_INTEGER + 1},
  ]) assert.equal(context.normalize(invalid), null);
});

test("touch-presence markers self-remove even when a remote session disconnects", () => {
  let nextTimerId = 1;
  const timers = new Map();
  const cleared = [];
  const appended = [];
  const context = vm.createContext({
    SESSION_DEVICE_ID_PATTERN: /^[A-Za-z0-9_-]{8,64}$/,
    SESSION_TOUCH_MARKER_RELEASE_MILLIS: 700,
    SESSION_TOUCH_MARKER_STALE_MILLIS: 12_000,
    browserSessionDeviceId: "device_local123",
    remoteTouchMarkers: new Map(),
    sessionTouchOverlay: {appendChild: (element) => appended.push(element)},
    document: {
      hidden: false,
      createElement: () => ({
        className: "",
        dataset: {},
        style: {},
        classList: {toggle() {}},
        removed: false,
        remove() { this.removed = true; },
      }),
    },
    window: {
      setTimeout(callback, delay) {
        const id = nextTimerId++;
        timers.set(id, {callback, delay});
        return id;
      },
      clearTimeout(id) {
        cleared.push(id);
        timers.delete(id);
      },
    },
    Object,
    String,
    Number,
  });
  vm.runInContext(`
    ${extract("function normalizeTouchPresenceEnvelope(envelope)", "function updateMicrophoneCaptureRequest(microphone)")}
    globalThis.render = renderTouchPresence;
    globalThis.markers = remoteTouchMarkers;
  `, context);
  const sourceDeviceId = "device_remote12";
  assert.equal(context.render({
    type: "session_event",
    event: "touch_presence",
    sourceDeviceId,
    colorSlot: 2,
    phase: "down",
    x: 0.2,
    y: 0.3,
    sequence: 1,
  }), true);
  assert.equal(appended.length, 1);
  const firstTimerId = context.markers.get(sourceDeviceId).timer;
  assert.equal(timers.get(firstTimerId).delay, 12_000);

  assert.equal(context.render({
    type: "session_event",
    event: "touch_presence",
    sourceDeviceId,
    colorSlot: 2,
    phase: "move",
    x: 0.4,
    y: 0.5,
    sequence: 2,
  }), true);
  assert.deepEqual(cleared, [firstTimerId]);
  const staleTimer = timers.get(context.markers.get(sourceDeviceId).timer);
  assert.equal(staleTimer.delay, 12_000);
  staleTimer.callback();
  assert.equal(context.markers.has(sourceDeviceId), false);
  assert.equal(appended[0].removed, true);
});

test("read-only viewers keep media but cannot emit input or own viewport", () => {
  const microphoneEligibility = extract(
    "function microphoneCaptureEligible()",
    "function microphoneCaptureIsBusy()",
  );
  const viewportScheduling = extract("function scheduleViewportReport(value)", "function viewportReportKey(value)");
  const touchQueue = extract("function enqueueTouch(phase, position, options = {})", "function nextTouchRequest()");
  assert.match(microphoneEligibility, /browserSessionCanControl\(\)/u);
  assert.match(viewportScheduling, /!browserSessionOwnsViewport\(\)\s*\? 'not-main-session'/u);
  assert.match(touchQueue, /!browserSessionCanControl\(\)/u);
  const start = extract("async function startWebRtc(userInitiated = false)", "function renderAndroidAutoStatus");
  assert.match(start, /const microphoneChannel = browserSessionCanControl\(\)/u);
  assert.match(start, /peer\.addTransceiver\('video', \{direction: 'recvonly'\}\)/u);
});

test("direct control channel consumes touch presence before RPC responses", () => {
  const receive = extract("    receive(data) {", "    fail(error) {");
  assert.match(receive, /if \(renderTouchPresence\(envelope\)\) return;/u);
  assert.ok(
    receive.indexOf("renderTouchPresence(envelope)") < receive.indexOf("envelope.type !== 'rpc_response'"),
  );
  assert.match(appScript, /type !== 'session_event'/u);
  assert.match(appScript, /envelope\.event !== 'touch_presence'/u);
});

test("touch overlay has three fixed session colors and never captures input", () => {
  assert.match(indexHtml, /id="session-touch-overlay" aria-hidden="true"/u);
  assert.match(indexHtml, /#session-touch-overlay\s*\{[\s\S]*pointer-events: none;/u);
  assert.match(indexHtml, /data-color-slot="1"/u);
  assert.match(indexHtml, /data-color-slot="2"/u);
  assert.match(indexHtml, /\.session-touch-marker\.released/u);
});

test("pairing sends only a bounded generic device label", () => {
  const pairing = extract("function pairingDeviceName()", "async function pairWithCode(candidate)");
  assert.match(pairing, /userAgentData/u);
  assert.match(pairing, /\.slice\(0, 64\)/u);
  assert.match(appScript, /'X-Browser-Device-Name': pairingDeviceName\(\)/u);
  assert.match(appScript, /'x-browser-device-name'/u);
});
