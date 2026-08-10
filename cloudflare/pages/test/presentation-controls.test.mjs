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
  const end = appScript.indexOf(endMarker, start + startMarker.length);
  assert.notEqual(end, -1, `missing end marker: ${endMarker}`);
  return appScript.slice(start, end);
}

function plain(value) {
  return JSON.parse(JSON.stringify(value));
}

test("forced presentation orientation swaps only a mismatched expanded target", () => {
  const context = vm.createContext({
    PRESENTATION_ORIENTATIONS: Object.freeze(["auto", "landscape", "portrait"]),
    Object,
    Number,
  });
  vm.runInContext(`
    ${extract("function orientPresentationViewport", "function calculateExpandedProjectionViewport")}
    ${extract("function projectionOrientationAxis", "function physicalViewportOrientationAxis")}
    globalThis.orient = orientPresentationViewport;
  `, context);

  assert.deepEqual(plain(context.orient(1200, 700, "auto")), {
    width: 1200, height: 700, orientation: "auto",
  });
  assert.deepEqual(plain(context.orient(700, 1200, "landscape")), {
    width: 1200, height: 700, orientation: "landscape",
  });
  assert.deepEqual(plain(context.orient(1200, 700, "portrait")), {
    width: 700, height: 1200, orientation: "portrait",
  });
  assert.deepEqual(plain(context.orient(1200, 700, "landscape")), {
    width: 1200, height: 700, orientation: "landscape",
  });
});

function loadPresentationGeometry() {
  const context = vm.createContext({
    PRESENTATION_SNAP_ENTER_CSS_PIXELS: 24,
    PRESENTATION_SNAP_EXIT_CSS_PIXELS: 40,
    Object,
    Number,
    Math,
    boundedPresentationUnit(value) {
      const numeric = Number(value);
      if (!Number.isFinite(numeric)) return 0;
      return Math.max(-1, Math.min(1, numeric));
    },
  });
  vm.runInContext(`
    ${extract("function presentationAlignmentOffset", "function syncPresentationControls")}
    globalThis.align = presentationAlignmentOffset;
    globalThis.layout = calculatePresentationLayout;
    globalThis.drag = dragPresentationOffset;
  `, context);
  return context;
}

test("restored custom offsets derive snap ownership per axis", () => {
  const context = vm.createContext({Object, Number, Math});
  vm.runInContext(`
    ${extract("function boundedPresentationUnit", "function normalizePresentationPreferences")}
    globalThis.snapState = presentationSnapStateForOffset;
  `, context);

  assert.deepEqual(plain(context.snapState({x: 0.5, y: 0.005})), {x: false, y: true});
  assert.deepEqual(plain(context.snapState({x: -0.25, y: 0.4})), {x: false, y: false});
  assert.match(
    appScript,
    /let presentationSnapState = presentationSnapStateForOffset\(presentationOffset\);/u,
  );
});

test("letterbox alignment and two-finger offsets stay inside available margins", () => {
  const context = loadPresentationGeometry();
  const centered = context.layout(1000, 600, 1, context.align("center"));
  assert.deepEqual(plain(centered), {
    width: 600,
    height: 600,
    marginX: 200,
    marginY: 0,
    left: 200,
    top: 0,
    offsetX: 0,
    offsetY: 0,
    normalizedOffset: {x: 0, y: 0},
  });

  assert.equal(context.layout(1000, 600, 1, context.align("left")).left, 0);
  assert.equal(context.layout(1000, 600, 1, context.align("right")).left, 400);

  const clampedRight = context.drag(centered, {x: 0, y: 0}, 500, 80, {x: false, y: false});
  assert.deepEqual(plain(clampedRight.offset), {x: 1, y: 0});
  const moved = context.layout(1000, 600, 1, clampedRight.offset);
  assert.equal(moved.left, 400);
  assert.equal(moved.top, 0);
});

test("center-axis snapping uses enter and release hysteresis", () => {
  const context = loadPresentationGeometry();
  const layout = context.layout(1000, 600, 1, {x: 0, y: 0});

  assert.match(appScript, /const PRESENTATION_SNAP_ENTER_CSS_PIXELS = 24;/u);
  assert.match(appScript, /const PRESENTATION_SNAP_EXIT_CSS_PIXELS = 40;/u);

  const heldAtCenter = context.drag(layout, {x: 0, y: 0}, 36, 0, {x: true, y: true});
  assert.deepEqual(plain(heldAtCenter), {
    offset: {x: 0, y: 0},
    snap: {x: true, y: true},
  });

  const released = context.drag(layout, {x: 0, y: 0}, 41, 0, {x: true, y: true});
  assert.equal(released.snap.x, false);
  assert.equal(released.offset.x, 41 / 200);

  const outsideEnterThreshold = context.drag(
    layout,
    released.offset,
    -16,
    0,
    released.snap,
  );
  assert.equal(outsideEnterThreshold.snap.x, false);
  assert.equal(outsideEnterThreshold.offset.x, 25 / 200);

  const entered = context.drag(layout, released.offset, -17, 0, released.snap);
  assert.equal(entered.snap.x, true);
  assert.equal(entered.offset.x, 0);
});

test("24/40px snap boundaries apply independently to x, y, and zero-margin axes", () => {
  const context = loadPresentationGeometry();
  const bothAxes = {marginX: 100, marginY: 100};

  const heldAtExitBoundary = context.drag(
    bothAxes,
    {x: 0, y: 0},
    40,
    -40,
    {x: true, y: true},
  );
  assert.deepEqual(plain(heldAtExitBoundary), {
    offset: {x: 0, y: 0},
    snap: {x: true, y: true},
  });

  const releasedPastExitBoundary = context.drag(
    bothAxes,
    {x: 0, y: 0},
    40.001,
    -40.001,
    {x: true, y: true},
  );
  assert.equal(releasedPastExitBoundary.snap.x, false);
  assert.equal(releasedPastExitBoundary.snap.y, false);

  const enteredAtBoundary = context.drag(
    bothAxes,
    {x: 0, y: 0},
    24,
    -24,
    {x: false, y: false},
  );
  assert.deepEqual(plain(enteredAtBoundary), {
    offset: {x: 0, y: 0},
    snap: {x: true, y: true},
  });

  const outsideEnterBoundary = context.drag(
    bothAxes,
    {x: 0, y: 0},
    24.001,
    -24.001,
    {x: false, y: false},
  );
  assert.equal(outsideEnterBoundary.snap.x, false);
  assert.equal(outsideEnterBoundary.snap.y, false);

  assert.deepEqual(
    plain(context.drag(
      {marginX: 0, marginY: 100},
      {x: 1, y: 0},
      999,
      41,
      {x: false, y: true},
    )),
    {offset: {x: 0, y: 0.41}, snap: {x: true, y: false}},
  );
  assert.deepEqual(
    plain(context.drag(
      {marginX: 100, marginY: 0},
      {x: 0, y: -1},
      -41,
      -999,
      {x: true, y: false},
    )),
    {offset: {x: -0.41, y: 0}, snap: {x: false, y: true}},
  );
});

function loadGestureClassifier(initialGesture, points) {
  const context = vm.createContext({
    PRESENTATION_PAN_LOCK_CSS_PIXELS: 10,
    PRESENTATION_PAN_DIRECTION_RATIO: 1.35,
    PINCH_EXPAND_SCALE: 1.18,
    PINCH_COLLAPSE_SCALE: 0.82,
    touchPointers: new Map(points),
    presentationAlignment: "center",
    presentationOffset: Object.freeze({x: 0, y: 0}),
    presentationSnapState: Object.freeze({x: true, y: true}),
    Object,
    Number,
    Math,
    dragPresentationOffset(layout, initialOffset, deltaX, deltaY) {
      const x = layout.marginX > 0
        ? Math.max(-1, Math.min(1, initialOffset.x + deltaX / layout.marginX))
        : 0;
      const y = layout.marginY > 0
        ? Math.max(-1, Math.min(1, initialOffset.y + deltaY / layout.marginY))
        : 0;
      return Object.freeze({
        offset: Object.freeze({x, y}),
        snap: Object.freeze({x: x === 0, y: y === 0}),
      });
    },
    syncPresentationControls() {},
    syncProjectionContentLayout() {},
  });
  context.initialGesture = initialGesture;
  vm.runInContext(`
    let pinchGesture = initialGesture;
    ${extract("function pointerDistance", "function applyPinchIntentFromGesture")}
    globalThis.update = updatePinchGesture;
    globalThis.snapshot = () => ({
      mode: pinchGesture.mode,
      intent: pinchGesture.intent,
      presentationChanged: pinchGesture.presentationChanged,
    });
    globalThis.presentationPosition = () => ({
      offset: presentationOffset,
      snap: presentationSnapState,
    });
  `, context);
  return context;
}

function expandedGesture() {
  return {
    pointerIds: [1, 2],
    initialDistance: 100,
    initialCentroid: {x: 50, y: 0},
    initiallyExpanded: true,
    initialLayout: {marginX: 100, marginY: 0},
    initialOffset: {x: 0, y: 0},
    initialSnapState: {x: true, y: true},
    mode: "pending",
    presentationChanged: false,
    intent: null,
    applied: false,
  };
}

test("expanded parallel drag locks pan while a clear pre-lock pinch exits", () => {
  const pan = loadGestureClassifier(expandedGesture(), [
    [1, {x: 20, y: 0}],
    [2, {x: 120, y: 0}],
  ]);
  pan.update();
  assert.deepEqual(plain(pan.snapshot()), {
    mode: "pan",
    intent: null,
    presentationChanged: true,
  });

  pan.touchPointers.set(1, {x: 30, y: 0});
  pan.touchPointers.set(2, {x: 110, y: 0});
  pan.update();
  assert.equal(pan.snapshot().mode, "pan");
  assert.equal(pan.snapshot().intent, null);

  pan.touchPointers.set(1, {x: 0, y: 0});
  pan.touchPointers.set(2, {x: 100, y: 0});
  pan.update();
  assert.deepEqual(plain(pan.presentationPosition()), {
    offset: {x: 0, y: 0},
    snap: {x: true, y: true},
  });

  const pinch = loadGestureClassifier(expandedGesture(), [
    [1, {x: 10, y: 0}],
    [2, {x: 90, y: 0}],
  ]);
  pinch.update();
  assert.deepEqual(plain(pinch.snapshot()), {
    mode: "pinch",
    intent: "collapse",
    presentationChanged: false,
  });
});

test("movement on a zero-margin axis does not lock pan or consume later pinch exit", () => {
  const gesture = expandedGesture();
  gesture.initialLayout = {marginX: 0, marginY: 100};
  const context = loadGestureClassifier(gesture, [
    [1, {x: 20, y: 0}],
    [2, {x: 120, y: 0}],
  ]);

  context.update();
  assert.equal(context.snapshot().mode, "pending");
  assert.equal(context.snapshot().presentationChanged, false);

  context.touchPointers.set(1, {x: 10, y: 0});
  context.touchPointers.set(2, {x: 90, y: 0});
  context.update();
  assert.equal(context.snapshot().mode, "pinch");
  assert.equal(context.snapshot().intent, "collapse");
});

test("standard pinch-to-enter remains and three-finger position reset is expanded-only", () => {
  const gesture = expandedGesture();
  gesture.initiallyExpanded = false;
  const standard = loadGestureClassifier(gesture, [
    [1, {x: -10, y: 0}],
    [2, {x: 110, y: 0}],
  ]);
  standard.update();
  assert.equal(standard.snapshot().mode, "pinch");
  assert.equal(standard.snapshot().intent, "expand");

  const threeFingerSource = extract(
    "function beginThreeFingerPresentationReset",
    "function applyPresentationPan",
  );
  assert.match(threeFingerSource, /touchPointers\.size < 3 \|\| !expandedViewActive\(\)/u);
  assert.match(threeFingerSource, /resetPresentationPosition\(\)/u);
});

test("three-finger reset centers position while preserving orientation and viewport target", () => {
  const resetSource = extract(
    "function resetPresentationPosition",
    "function applyProjectionGeometry",
  );
  const context = vm.createContext({Object});
  vm.runInContext(`
    let presentationOrientation = 'portrait';
    let presentationAlignment = 'custom';
    let presentationOffset = Object.freeze({x: 0.65, y: -0.4});
    let presentationSnapState = Object.freeze({x: false, y: false});
    const expandedViewportTarget = Object.freeze({width: 700, height: 1200});
    const expandedViewportOrientationAxis = 'portrait';
    let storeCalls = 0;
    let controlSyncs = 0;
    let layoutSyncs = 0;
    let viewportRefreshes = 0;
    let storedPreferences = null;
    function rememberPresentationPreferences() {
      storeCalls += 1;
      storedPreferences = {
        orientation: presentationOrientation,
        alignment: presentationAlignment,
        offset: presentationOffset,
      };
    }
    function syncPresentationControls() { controlSyncs += 1; }
    function syncProjectionContentLayout() { layoutSyncs += 1; }
    function refreshPresentationViewportPreference() { viewportRefreshes += 1; }
    ${resetSource}
    globalThis.resetPosition = resetPresentationPosition;
    globalThis.snapshot = () => ({
      orientation: presentationOrientation,
      alignment: presentationAlignment,
      offset: presentationOffset,
      snap: presentationSnapState,
      expandedViewportTarget,
      expandedViewportOrientationAxis,
      storeCalls,
      controlSyncs,
      layoutSyncs,
      viewportRefreshes,
      storedPreferences,
    });
  `, context);

  context.resetPosition();
  assert.deepEqual(plain(context.snapshot()), {
    orientation: "portrait",
    alignment: "center",
    offset: {x: 0, y: 0},
    snap: {x: true, y: true},
    expandedViewportTarget: {width: 700, height: 1200},
    expandedViewportOrientationAxis: "portrait",
    storeCalls: 1,
    controlSyncs: 1,
    layoutSyncs: 1,
    viewportRefreshes: 0,
    storedPreferences: {
      orientation: "portrait",
      alignment: "center",
      offset: {x: 0, y: 0},
    },
  });
  assert.doesNotMatch(resetSource, /presentationOrientation\s*=|refreshPresentationViewportPreference|scheduleViewportReport|api\(/u);
});

test("presentation gestures claim touch ownership before Android Auto forwarding", () => {
  const ownershipSource = extract("function beginPinchGesture", "function beginThreeFingerPresentationReset");
  const pointerHandlers = extract(
    "pad.addEventListener('pointerdown'",
    "window.addEventListener('blur'",
  );
  assert.match(
    ownershipSource,
    /function beginPinchGesture\(\)[\s\S]*cancelPendingSingleTouch\(\);[\s\S]*cancelActivePointer\(\);[\s\S]*suppressAndroidAutoTouch = true;/u,
  );
  assert.match(pointerHandlers, /touchPointers\.size >= 3[\s\S]*beginThreeFingerPresentationReset\(\)/u);
  assert.match(pointerHandlers, /touchPointers\.size >= 2[\s\S]*beginPinchGesture\(\)/u);
  assert.match(pointerHandlers, /stagePendingSingleTouch\(event\);\s*return;/u);
  assert.match(pointerHandlers, /finishGesturePointer\(event, true\)/u);
  assert.match(pointerHandlers, /finishSingleTouchPointer\(event, true\)/u);
});

test("orientation can request a viewport while alignment and drag remain presentation-only", () => {
  const orientationSource = extract(
    "function refreshPresentationViewportPreference",
    "function setPresentationAlignment",
  );
  const alignmentSource = extract(
    "function setPresentationAlignment(value)",
    "function resetPresentationPosition",
  );
  const reportGate = extract("function scheduleViewportReport", "function viewportReportKey");

  assert.match(orientationSource, /expandedViewportTarget = calculateExpandedProjectionViewport\(\)/u);
  assert.match(orientationSource, /scheduleViewportLayoutSync\(\)/u);
  assert.doesNotMatch(orientationSource, /api\(/u);
  assert.match(alignmentSource, /syncProjectionContentLayout\(\)/u);
  assert.doesNotMatch(alignmentSource, /refreshPresentationViewportPreference|scheduleViewportReport|api\(/u);
  assert.match(reportGate, /!browserSessionOwnsViewport\(\) \? 'not-main-session'/u);
});

test("non-main standard layout follows the received main projection aspect", () => {
  const context = vm.createContext({
    DYNAMIC_ASPECT_BODY_CLASS: "navonweb-dynamic-aspect",
    document: {body: {classList: {contains: () => true}}},
    browserSessionOwnsViewport: () => false,
    expectedExpandedProjectionViewport: () => ({aspectRatio: 9 / 16}),
    projectionSourceAspectRatio: () => 5 / 3,
    Number,
  });
  vm.runInContext(`
    ${extract("function standardProjectionAspectRatio", "function syncStandardProjectionBounds")}
    globalThis.aspect = standardProjectionAspectRatio;
  `, context);

  assert.equal(context.aspect(), 5 / 3);
  context.browserSessionOwnsViewport = () => true;
  assert.equal(context.aspect(), 9 / 16);
});

test("viewer authority notice is persistent in standard view and timed per expanded entry", () => {
  const source = extract(
    "function viewportAuthorityNoticeEligible",
    "function normalizeBrowserSession",
  );
  const timers = new Map();
  let nextTimerId = 1;
  let expanded = false;
  const notice = {hidden: true, textContent: ""};
  const context = vm.createContext({
    VIEWPORT_AUTHORITY_NOTICE_EXPANDED_MILLIS: 3000,
    window: {
      setTimeout(callback, delay) {
        const id = nextTimerId++;
        timers.set(id, {callback, delay});
        return id;
      },
      clearTimeout(id) { timers.delete(id); },
    },
    browserCredential: "credential-a",
    viewer: {hidden: false},
    browserSessionMetadataSupported: true,
    browserSessionRole: "viewer",
    viewportAuthorityNotice: notice,
    expandedViewActive: () => expanded,
    scheduleViewportLayoutSync() {},
    t: () => "The main session controls the aspect ratio.",
    Boolean,
  });
  vm.runInContext(`
    let viewportAuthorityNoticeState = 'hidden';
    let viewportAuthorityNoticeTimer = 0;
    let viewportAuthorityNoticeTimerGeneration = 0;
    let viewportAuthorityNoticeEligibleState = false;
    let viewportAuthorityNoticeExpandedState = false;
    ${source}
    globalThis.syncNotice = syncViewportAuthorityNotice;
    globalThis.snapshot = () => ({
      state: viewportAuthorityNoticeState,
      timer: viewportAuthorityNoticeTimer,
      generation: viewportAuthorityNoticeTimerGeneration,
      eligible: viewportAuthorityNoticeEligibleState,
      expanded: viewportAuthorityNoticeExpandedState,
    });
  `, context);

  context.syncNotice();
  assert.equal(notice.hidden, false);
  assert.equal(timers.size, 0);
  context.syncNotice();
  assert.equal(timers.size, 0, "repeated viewer status must not add a standard-view timer");

  expanded = true;
  context.syncNotice();
  assert.equal(notice.hidden, false);
  assert.equal(timers.size, 1);
  const firstExpandedTimer = [...timers.values()][0];
  assert.equal(firstExpandedTimer.delay, 3000);
  const firstTimerId = context.snapshot().timer;
  context.syncNotice();
  assert.equal(context.snapshot().timer, firstTimerId, "repeated viewer status must not restart the timer");

  timers.delete(firstTimerId);
  firstExpandedTimer.callback();
  assert.equal(notice.hidden, true);
  assert.equal(context.snapshot().timer, 0);
  context.syncNotice();
  assert.equal(notice.hidden, true, "repeated expanded status must not reshow an expired notice");
  assert.equal(timers.size, 0);

  expanded = false;
  context.syncNotice();
  assert.equal(notice.hidden, false, "exiting to standard view restores the persistent notice");

  expanded = true;
  context.syncNotice();
  const staleTimerId = context.snapshot().timer;
  const staleTimer = timers.get(staleTimerId);
  expanded = false;
  context.syncNotice();
  expanded = true;
  context.syncNotice();
  const currentTimerId = context.snapshot().timer;
  assert.notEqual(currentTimerId, staleTimerId);
  staleTimer.callback();
  assert.equal(notice.hidden, false, "a stale generation cannot hide a re-entered notice");
  assert.equal(context.snapshot().timer, currentTimerId);

  context.browserSessionRole = "main";
  context.syncNotice();
  assert.equal(notice.hidden, true);
  assert.equal(timers.size, 0, "main status cancels the expanded timer immediately");

  context.browserSessionRole = "viewer";
  context.syncNotice();
  assert.equal(notice.hidden, false);
  assert.equal(timers.size, 1, "viewer status appearing in fullscreen starts one fresh window");
  context.browserSessionMetadataSupported = false;
  context.syncNotice();
  assert.equal(notice.hidden, true);
  assert.equal(timers.size, 0, "unknown session status cancels the timer");

  context.browserSessionMetadataSupported = true;
  context.browserCredential = "";
  context.syncNotice();
  assert.equal(notice.hidden, true);
  assert.equal(timers.size, 0, "credential reset keeps the notice hidden without a timer");
});

test("one selector transition recomputes and schedules once while repeats are no-ops", () => {
  let calculations = 0;
  let schedules = 0;
  let stores = 0;
  const context = vm.createContext({
    PRESENTATION_ORIENTATIONS: Object.freeze(["auto", "landscape", "portrait"]),
    presentationOrientation: "auto",
    expandedViewportTarget: Object.freeze({width: 700, height: 1200}),
    expandedViewportOrientationAxis: "portrait",
    calculateExpandedProjectionViewport() {
      calculations += 1;
      return Object.freeze({width: 1200, height: 700});
    },
    physicalViewportOrientationAxis: () => "portrait",
    scheduleViewportLayoutSync() { schedules += 1; },
    cancelPointerInteraction() {},
    rememberPresentationPreferences() { stores += 1; },
    syncPresentationControls() {},
  });
  vm.runInContext(`
    ${extract("function refreshPresentationViewportPreference", "function setPresentationAlignment")}
    globalThis.setOrientation = setPresentationOrientation;
  `, context);

  assert.equal(context.setOrientation("landscape"), true);
  assert.equal(context.presentationOrientation, "landscape");
  assert.equal(calculations, 1);
  assert.equal(schedules, 1);
  assert.equal(stores, 1);

  assert.equal(context.setOrientation("landscape"), false);
  assert.equal(calculations, 1);
  assert.equal(schedules, 1);
  assert.equal(stores, 1);
});

test("becoming main measures the latest preference before its single reclaim", () => {
  const latest = Object.freeze({width: 700, height: 1200, viewportScale: 1});
  const scheduled = [];
  const context = vm.createContext({
    DYNAMIC_ASPECT_BODY_CLASS: "navonweb-dynamic-aspect",
    activeViewportValue: Object.freeze({width: 1200, height: 700, viewportScale: 1}),
    lastViewportReportKey: "1200x700",
    browserSessionOwnsViewport: () => true,
    document: {body: {classList: {contains: () => true}}},
    measureProjectionViewport: () => latest,
    scheduleViewportReport: value => scheduled.push(value),
  });
  vm.runInContext(`
    ${extract("function requestViewportControlReclaim", "async function reportProjectionViewport")}
    globalThis.reclaim = requestViewportControlReclaim;
    globalThis.state = () => ({activeViewportValue, lastViewportReportKey});
  `, context);

  context.reclaim();
  assert.equal(scheduled.length, 1);
  assert.strictEqual(scheduled[0], latest);
  assert.strictEqual(context.state().activeViewportValue, latest);
  assert.equal(context.state().lastViewportReportKey, "");
});

test("display controls hide in expanded view while the first-entry guide remains accessible", () => {
  assert.match(indexHtml, /id="viewer-controls" role="toolbar"[^>]*data-i18n-aria-label="presentationControlsLabel"/u);
  assert.match(indexHtml, /name="presentation-orientation" value="auto" checked/u);
  assert.match(indexHtml, /name="presentation-orientation" value="landscape"/u);
  assert.match(indexHtml, /name="presentation-orientation" value="portrait"/u);
  assert.match(indexHtml, /id="presentation-alignment-label"[^>]*hidden/u);
  assert.doesNotMatch(indexHtml, /<fieldset id="presentation-alignment-label"/u);
  assert.match(
    indexHtml,
    /id="presentation-alignment-trigger" type="button" aria-expanded="false" aria-controls="presentation-alignment-picker"[^>]*data-presentation-alignment="center"/u,
  );
  assert.match(indexHtml, /id="presentation-alignment-picker" role="radiogroup"[^>]*hidden/u);
  assert.equal(
    (indexHtml.match(/name="presentation-alignment-choice" value="(?:top-left|top|top-right|left|center|right|bottom-left|bottom|bottom-right)"/gu) || []).length,
    9,
  );
  assert.match(indexHtml, /id="presentation-alignment" hidden aria-hidden="true" tabindex="-1"/u);
  assert.match(indexHtml, /#presentation-alignment-trigger \{[\s\S]*width: 44px;[\s\S]*height: 44px;/u);
  assert.match(indexHtml, /#presentation-alignment-picker \{[\s\S]*position: absolute;[\s\S]*grid-template-columns: repeat\(3, 44px\);/u);
  assert.match(indexHtml, /\.presentation-alignment-choice \{[\s\S]*width: 44px;[\s\S]*height: 44px;/u);
  assert.match(indexHtml, /\.presentation-alignment-preview::after \{[\s\S]*grid-row: var\(--anchor-row, 2\);/u);
  assert.match(indexHtml, /\[data-presentation-alignment='custom'\] \.presentation-alignment-preview::after \{[\s\S]*border-radius: 50%;/u);
  assert.match(appScript, /querySelectorAll\('input\[name="presentation-alignment-choice"\]'\)/u);
  assert.match(appScript, /for \(const input of presentationAlignmentInputs\) \{\s*input\.checked = input\.value === presentationAlignment;/u);
  assert.match(indexHtml, /id="presentation-guide" role="dialog" aria-modal="false"/u);
  assert.match(indexHtml, /id="presentation-guide-dismiss-forever" type="checkbox"/u);
  assert.match(indexHtml, /id="presentation-guide-dismiss" type="button"/u);
  assert.match(indexHtml, /id="presentation-guide-countdown" role="timer" aria-live="off" hidden/u);
  assert.match(indexHtml, /id="presentation-guide-progress" max="10000" value="10000"/u);
  assert.match(indexHtml, /#viewer:fullscreen #viewer-controls,[\s\S]*body\.theater-mode #viewer-controls \{ display: none !important; \}/u);
  assert.match(indexHtml, /#presentation-guide \{[\s\S]*pointer-events: none;/u);
  assert.match(indexHtml, /#presentation-guide-card \{[\s\S]*pointer-events: auto;/u);
  const fullscreenState = extract("function syncFullscreenState", "function setTheaterMode");
  assert.match(fullscreenState, /fullscreenButton\.hidden = expanded/u);
  assert.match(fullscreenState, /viewerControls\.hidden = expanded/u);
  assert.match(fullscreenState, /if \(expanded\) setPresentationAlignmentPickerOpen\(false, false\)/u);
  assert.match(fullscreenState, /t\(expanded \? 'fullscreenExit' : 'fullscreenEnter'\)/u);
  assert.match(fullscreenState, /maybeShowPresentationGuide\(\)/u);
  assert.match(
    appScript,
    /pad\.addEventListener\('pointerdown',[\s\S]*if \(presentationGuideOpen\) \{\s*event\.preventDefault\(\);\s*return;/u,
  );
  assert.match(appScript, /PRESENTATION_GUIDE_DISMISSED_KEY = 'navonweb\.presentationGuideDismissed\.v1'/u);
  assert.match(appScript, /presentationGuideTitle: 'Fullscreen display controls'/u);
  assert.match(appScript, /presentationGuideMove: 'Move two fingers together to reposition video inside the available margins\.'/u);
  assert.match(appScript, /presentationGuideReset: 'Press with three fingers to reset the video position\.'/u);
  assert.match(appScript, /presentationReset: 'The video position was reset\.'/u);
  assert.match(appScript, /presentationGuideDismiss: 'OK'/u);
  assert.match(appScript, /presentationGuideDismiss: '확인'/u);
  assert.match(appScript, /presentationGuideTitle: '전체 화면 표시 안내'/u);
  assert.match(appScript, /presentationGuideMove: '전체 화면에서 두 손가락을 나란히 움직이면 여백 안에서 영상 위치가 이동합니다\.'/u);
  assert.match(
    appScript,
    /presentationGuideDismiss\.addEventListener\('click',[\s\S]*presentationGuideDismissForever\.checked[\s\S]*rememberPresentationGuideDismissal\(\)[\s\S]*hidePresentationGuide\(true\)/u,
  );
  assert.match(appScript, /presentationAlignmentTrigger\.addEventListener\('click',[\s\S]*setPresentationAlignmentPickerOpen\(!presentationAlignmentPickerOpen, false\)/u);
  assert.match(appScript, /setPresentationAlignment\(input\.value\);\s*setPresentationAlignmentPickerOpen\(false, true\)/u);
  assert.match(appScript, /document\.addEventListener\('pointerdown',[\s\S]*!presentationAlignmentPickerOpen[\s\S]*presentationAlignmentLabel\.contains\(event\.target\)[\s\S]*setPresentationAlignmentPickerOpen\(false, false\)/u);
  assert.match(appScript, /event\.key === 'Escape' && presentationAlignmentPickerOpen[\s\S]*setPresentationAlignmentPickerOpen\(false, true\)/u);
});

test("alignment dropdown mirrors preset and custom state with safe focus restoration", () => {
  const pickerSource = extract(
    "function presentationAlignmentI18nKey",
    "function syncProjectionContentLayout",
  );
  const triggerAttributes = new Map();
  let triggerFocuses = 0;
  let focusedPreset = "";
  let expanded = false;
  const values = [
    "top-left", "top", "top-right",
    "left", "center", "right",
    "bottom-left", "bottom", "bottom-right",
  ];
  const presentationAlignmentInputs = values.map(value => ({
    value,
    checked: false,
    focus() { focusedPreset = value; },
  }));
  const presentationAlignmentLabel = {hidden: false};
  const presentationAlignmentPicker = {hidden: true};
  const presentationAlignmentTrigger = {
    dataset: {},
    setAttribute(name, value) { triggerAttributes.set(name, value); },
    focus() { triggerFocuses += 1; },
  };
  const context = vm.createContext({
    presentationOrientationInputs: [
      {value: "auto", checked: false},
      {value: "landscape", checked: false},
      {value: "portrait", checked: false},
    ],
    presentationAlignmentInputs,
    presentationAlignmentLabel,
    presentationAlignmentPicker,
    presentationAlignmentTrigger,
    presentationAlignmentSelect: {value: ""},
    pad: {dataset: {}},
    expandedViewActive: () => expanded,
    window: {requestAnimationFrame(callback) { callback(); }},
    String,
    t(key) {
      const valuesByKey = {
        presentationAlignmentTrigger: "Alignment: {alignment}. Open position picker.",
        presentationAlignCenter: "Center",
        presentationAlignTopLeft: "Top left",
        presentationAlignCustom: "Custom position",
      };
      return valuesByKey[key] || key;
    },
  });
  vm.runInContext(`
    let presentationOrientation = 'landscape';
    let presentationAlignment = 'custom';
    let presentationAlignmentPickerOpen = false;
    ${pickerSource}
    globalThis.syncControls = syncPresentationControls;
    globalThis.setPickerOpen = setPresentationAlignmentPickerOpen;
    globalThis.setState = (orientation, alignment) => {
      presentationOrientation = orientation;
      presentationAlignment = alignment;
    };
    globalThis.pickerOpen = () => presentationAlignmentPickerOpen;
  `, context);

  context.syncControls();
  assert.equal(presentationAlignmentLabel.hidden, false);
  assert.equal(presentationAlignmentTrigger.dataset.presentationAlignment, "custom");
  assert.equal(
    triggerAttributes.get("aria-label"),
    "Alignment: Custom position. Open position picker.",
  );
  assert.equal(presentationAlignmentInputs.some(input => input.checked), false);

  assert.equal(context.setPickerOpen(true), true);
  assert.equal(presentationAlignmentPicker.hidden, false);
  assert.equal(triggerAttributes.get("aria-expanded"), "true");
  assert.equal(focusedPreset, "center", "custom state opens on the neutral preset");
  assert.equal(context.setPickerOpen(false, true), true);
  assert.equal(triggerFocuses, 1);
  assert.equal(triggerAttributes.get("aria-expanded"), "false");

  context.setState("landscape", "top-left");
  context.syncControls();
  assert.equal(presentationAlignmentInputs.filter(input => input.checked).length, 1);
  assert.equal(presentationAlignmentInputs.find(input => input.checked).value, "top-left");
  assert.equal(presentationAlignmentTrigger.dataset.presentationAlignment, "top-left");

  context.setPickerOpen(true);
  context.setState("auto", "top-left");
  context.syncControls();
  assert.equal(presentationAlignmentLabel.hidden, true);
  assert.equal(context.pickerOpen(), false);
  assert.equal(presentationAlignmentPicker.hidden, true);

  expanded = false;
  context.setState("landscape", "center");
  context.syncControls();
  context.setPickerOpen(true);
  expanded = true;
  context.syncControls();
  assert.equal(context.pickerOpen(), false);
  assert.equal(presentationAlignmentPicker.hidden, true);
});

test("fullscreen guide counts down, interaction holds it open, and expiry cleans up", () => {
  const guideSource = extract(
    "function resetPresentationGuideAutoDismiss",
    "function syncFullscreenState",
  );
  const timeouts = new Map();
  const intervals = new Map();
  const clock = {now: 1000};
  let nextTimerId = 1;
  let focusCount = 0;
  const progressAttributes = new Map();
  const presentationGuide = {hidden: true, contains: () => false};
  const presentationGuideDismiss = {
    contains(target) { return target === this; },
    focus() { focusCount += 1; },
  };
  const presentationGuideCountdown = {hidden: true};
  const presentationGuideCountdownText = {textContent: ""};
  const presentationGuideProgress = {
    hidden: false,
    max: 0,
    value: 0,
    setAttribute(name, value) { progressAttributes.set(name, value); },
    removeAttribute(name) { progressAttributes.delete(name); },
  };
  const window = {
    setTimeout(callback, delay) {
      const id = nextTimerId++;
      timeouts.set(id, {callback, delay});
      return id;
    },
    clearTimeout(id) { timeouts.delete(id); },
    setInterval(callback, delay) {
      const id = nextTimerId++;
      intervals.set(id, {callback, delay});
      return id;
    },
    clearInterval(id) { intervals.delete(id); },
    requestAnimationFrame(callback) { callback(); },
  };
  const context = vm.createContext({
    PRESENTATION_GUIDE_AUTO_DISMISS_MILLIS: 10000,
    PRESENTATION_GUIDE_COUNTDOWN_INTERVAL_MILLIS: 250,
    Date: {now: () => clock.now},
    String,
    Math,
    window,
    presentationGuide,
    presentationGuideDismiss,
    presentationGuideDismissForever: {checked: false},
    presentationGuideCountdown,
    presentationGuideCountdownText,
    presentationGuideProgress,
    document: {activeElement: null},
    expandedViewActive: () => true,
    presentationGuideWasDismissed: () => false,
    hideFullscreenHint() {},
    viewer: {focus() {}},
    fullscreenButton: {focus() {}},
    t(key) {
      if (key === "presentationGuideAutoDismiss") return "Closes in {seconds}s";
      if (key === "presentationGuideAutoDismissStopped") return "Stopped";
      return key;
    },
  });
  vm.runInContext(`
    let presentationGuideOpen = false;
    let presentationGuideAutoDismissTimer = 0;
    let presentationGuideCountdownTimer = 0;
    let presentationGuideAutoDismissDeadline = 0;
    let presentationGuideTimerGeneration = 0;
    ${guideSource}
    globalThis.showGuide = maybeShowPresentationGuide;
    globalThis.hideGuide = hidePresentationGuide;
    globalThis.interactWithGuide = presentationGuideInteraction;
    globalThis.snapshot = () => ({
      open: presentationGuideOpen,
      autoTimer: presentationGuideAutoDismissTimer,
      countdownTimer: presentationGuideCountdownTimer,
      deadline: presentationGuideAutoDismissDeadline,
      generation: presentationGuideTimerGeneration,
    });
  `, context);

  assert.equal(context.showGuide(), true);
  assert.equal(presentationGuide.hidden, false);
  assert.equal(presentationGuideCountdown.hidden, false);
  assert.equal(presentationGuideCountdownText.textContent, "Closes in 10s");
  assert.equal(presentationGuideProgress.value, 10000);
  assert.equal(timeouts.size, 1);
  assert.equal(intervals.size, 1);
  assert.equal(focusCount, 1);

  clock.now = 5500;
  [...intervals.values()][0].callback();
  assert.equal(presentationGuideCountdownText.textContent, "Closes in 6s");
  assert.equal(presentationGuideProgress.value, 5500);

  context.interactWithGuide({type: "pointerdown", target: {}});
  assert.equal(presentationGuide.hidden, false);
  assert.equal(presentationGuideCountdownText.textContent, "Stopped");
  assert.equal(presentationGuideProgress.hidden, true);
  assert.equal(timeouts.size, 0);
  assert.equal(intervals.size, 0);
  assert.equal(context.snapshot().open, true);
  assert.equal(context.snapshot().autoTimer, 0);
  assert.equal(context.snapshot().countdownTimer, 0);
  assert.equal(context.snapshot().deadline, 0);

  context.hideGuide();
  assert.equal(presentationGuide.hidden, true);
  assert.equal(presentationGuideCountdown.hidden, true);
  clock.now = 10000;
  assert.equal(context.showGuide(), true);
  context.interactWithGuide({type: "pointerdown", target: presentationGuideDismiss});
  assert.equal(timeouts.size, 1, "the explicit dismiss button should close via its click handler");
  context.interactWithGuide({type: "keydown", key: "Tab", target: presentationGuideDismiss});
  assert.equal(timeouts.size, 0, "keyboard navigation should hold the guide open");
  assert.equal(intervals.size, 0);

  context.hideGuide();
  clock.now = 20000;
  assert.equal(context.showGuide(), true);
  const [earlyTimeoutId, earlyTimeout] = [...timeouts.entries()][0];
  clock.now = 29999;
  timeouts.delete(earlyTimeoutId);
  earlyTimeout.callback();
  assert.equal(presentationGuide.hidden, false, "an early timeout cannot beat its deadline");
  assert.equal(timeouts.size, 1);
  assert.equal([...timeouts.values()][0].delay, 1);

  const [dueTimeoutId, dueTimeout] = [...timeouts.entries()][0];
  clock.now = 30000;
  timeouts.delete(dueTimeoutId);
  dueTimeout.callback();
  assert.equal(presentationGuide.hidden, true);
  assert.equal(intervals.size, 0);
  assert.equal(context.snapshot().open, false);
  assert.equal(context.snapshot().autoTimer, 0);
  assert.equal(context.snapshot().countdownTimer, 0);
  assert.equal(context.snapshot().deadline, 0);

  clock.now = 40000;
  assert.equal(context.showGuide(), true);
  const staleTimeout = [...timeouts.values()][0];
  const staleInterval = [...intervals.values()][0];
  const staleGeneration = context.snapshot().generation;
  context.hideGuide();
  clock.now = 50000;
  assert.equal(context.showGuide(), true);
  const currentTimerId = context.snapshot().autoTimer;
  const currentGeneration = context.snapshot().generation;
  assert.notEqual(currentGeneration, staleGeneration);
  clock.now = 60000;
  staleInterval.callback();
  staleTimeout.callback();
  assert.equal(presentationGuide.hidden, false, "stale timer callbacks cannot hide a newer guide");
  assert.equal(context.snapshot().generation, currentGeneration);
  assert.equal(context.snapshot().autoTimer, currentTimerId);

  const currentTimeout = timeouts.get(currentTimerId);
  timeouts.delete(currentTimerId);
  currentTimeout.callback();
  assert.equal(presentationGuide.hidden, true);
  assert.equal(intervals.size, 0);
});

test("standard, native fullscreen, theater, and exit transitions toggle the toolbar", () => {
  const context = vm.createContext({
    fullscreenButton: {
      hidden: null,
      textContent: "",
      setAttribute() {},
    },
    viewerControls: {hidden: null},
    fullscreenState: {textContent: ""},
    expandedViewWasActive: false,
    theaterMode: false,
    viewerOwnsFullscreen: () => false,
    t: value => value,
    maybeShowPresentationGuide: () => true,
    showFullscreenHint() {},
    hideFullscreenHint() {},
    hidePresentationGuide() {},
    clearStandardProjectionBounds() {},
    syncViewportAuthorityNotice() {},
    syncScreenWakeLock() {},
    scheduleViewportLayoutSync() {},
    setPresentationAlignmentPickerOpen() {},
  });
  vm.runInContext(`
    ${extract("function syncFullscreenState", "function setTheaterMode")}
    globalThis.syncState = syncFullscreenState;
    globalThis.hiddenState = () => ({
      controls: viewerControls.hidden,
      button: fullscreenButton.hidden,
    });
  `, context);

  context.syncState();
  assert.deepEqual(plain(context.hiddenState()), {controls: false, button: false});

  context.viewerOwnsFullscreen = () => true;
  context.syncState();
  assert.deepEqual(plain(context.hiddenState()), {controls: true, button: true});

  context.viewerOwnsFullscreen = () => false;
  context.theaterMode = true;
  context.syncState();
  assert.deepEqual(plain(context.hiddenState()), {controls: true, button: true});

  context.theaterMode = false;
  context.syncState();
  assert.deepEqual(plain(context.hiddenState()), {controls: false, button: false});
});
