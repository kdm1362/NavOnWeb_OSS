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
const indexHtml = readFileSync(path.join(assetRoot, "index.html"), "utf8");

function extractAppSource(startMarker, endMarker) {
  const start = appScript.indexOf(startMarker);
  assert.notEqual(start, -1, `missing app source marker: ${startMarker}`);
  const end = appScript.indexOf(endMarker, start);
  assert.notEqual(end, -1, `missing app source marker: ${endMarker}`);
  return appScript.slice(start, end);
}

const expectedViewportSource = extractAppSource(
  "function expandedViewportInsets()",
  "function measureProjectionViewport()",
);

function loadExpectedViewportRuntime({nativeFullscreen = true} = {}) {
  const viewer = {hidden: false};
  if (nativeFullscreen) viewer.requestFullscreen = () => Promise.resolve();
  const context = vm.createContext({
    DEVELOPMENT_NARROW_WIDTH_SCALE: 0.68,
    PRESENTATION_ORIENTATIONS: Object.freeze(["auto", "landscape", "portrait"]),
    browserSessionOwnsViewport: () => true,
    browserDevicePixelRatio: () => 2,
    developmentNarrowDriving: false,
    document: {fullscreenEnabled: true},
    expandedViewportProbe: {},
    expandedViewportTarget: null,
    expandedViewportOrientationAxis: "",
    presentationOrientation: "auto",
    finiteCssPixels: value => Number.parseFloat(value) || 0,
    layoutViewportDimensions: () => ({width: 760, height: 1100, scale: 1}),
    theaterMode: false,
    viewportValueChanged: (previous, next) => !previous || !next ||
      Math.abs(previous.width - next.width) > 0.5 ||
      Math.abs(previous.height - next.height) > 0.5 ||
      Math.abs(previous.viewportScale - next.viewportScale) > 0.01,
    viewer,
    window: {
      screen: {
        width: 1280,
        height: 800,
        orientation: {type: "portrait-primary"},
      },
      getComputedStyle: () => ({
        paddingLeft: "12px",
        paddingRight: "12px",
        paddingTop: "12px",
        paddingBottom: "12px",
      }),
    },
  });
  vm.runInContext(`
    ${expectedViewportSource}
    globalThis.expectedViewport = expectedExpandedProjectionViewport;
    globalThis.lockViewport = lockExpandedProjectionViewport;
    globalThis.releaseViewport = releaseExpandedProjectionViewport;
    globalThis.refreshViewport = refreshExpandedProjectionViewport;
  `, context);
  return context;
}

test("portrait fullscreen is precomputed from the oriented display and safe insets", () => {
  const context = loadExpectedViewportRuntime();
  const viewport = context.expectedViewport();

  assert.equal(viewport.width, 776);
  assert.equal(viewport.height, 1256);
  assert.equal(viewport.source, "fullscreenPreview");
  assert.equal(viewport.devicePixelRatio, 2);
});

test("forced expanded orientation swaps the target after safe insets", () => {
  const context = loadExpectedViewportRuntime();
  context.presentationOrientation = "landscape";
  const viewport = context.expectedViewport();

  assert.equal(viewport.width, 1256);
  assert.equal(viewport.height, 776);
  assert.equal(viewport.presentationOrientation, "landscape");
  assert.equal(viewport.source, "fullscreenPreview");
});

test("viewer preference cannot force a non-main expanded target", () => {
  const context = loadExpectedViewportRuntime();
  context.presentationOrientation = "landscape";
  context.browserSessionOwnsViewport = () => false;
  const viewport = context.expectedViewport();

  assert.equal(viewport.width, 776);
  assert.equal(viewport.height, 1256);
  assert.equal(viewport.presentationOrientation, "auto");
});

test("theater fallback is precomputed from the current CSS viewport", () => {
  const context = loadExpectedViewportRuntime({nativeFullscreen: false});
  const viewport = context.expectedViewport();

  assert.equal(viewport.width, 736);
  assert.equal(viewport.height, 1076);
  assert.equal(viewport.source, "theaterPreview");
});

test("expanded session keeps its entry target when native fullscreen falls back to theater", () => {
  const context = loadExpectedViewportRuntime();
  const entryTarget = context.lockViewport();

  context.theaterMode = true;
  assert.equal(context.refreshViewport(), false);
  const theaterTarget = context.expectedViewport();
  assert.strictEqual(theaterTarget, entryTarget);
  assert.equal(theaterTarget.width, 776);
  assert.equal(theaterTarget.height, 1256);

  context.releaseViewport();
  const standardTarget = context.expectedViewport();
  assert.equal(standardTarget.width, 736);
  assert.equal(standardTarget.height, 1076);
  assert.equal(standardTarget.source, "theaterPreview");
});

test("expanded session refreshes its locked target after physical display rotation", () => {
  const context = loadExpectedViewportRuntime();
  const portraitTarget = context.lockViewport();
  assert.equal(portraitTarget.width, 776);
  assert.equal(portraitTarget.height, 1256);

  context.window.screen.orientation.type = "landscape-primary";
  assert.equal(context.refreshViewport(), true);

  const landscapeTarget = context.expectedViewport();
  assert.equal(landscapeTarget.width, 1256);
  assert.equal(landscapeTarget.height, 776);
  assert.notStrictEqual(landscapeTarget, portraitTarget);
  assert.equal(context.refreshViewport(), false);
});

test("theater rotation waits for Safari-style delayed layout viewport geometry", () => {
  const context = loadExpectedViewportRuntime();
  const portraitTarget = context.lockViewport();
  context.theaterMode = true;

  context.window.screen.orientation.type = "landscape-primary";
  assert.equal(context.refreshViewport(), false);
  assert.strictEqual(context.expectedViewport(), portraitTarget);

  context.layoutViewportDimensions = () => ({width: 1100, height: 760, scale: 1});
  assert.equal(context.refreshViewport(), true);
  assert.equal(context.expectedViewport().width, 1076);
  assert.equal(context.expectedViewport().height, 736);
});

test("standard view reports expanded geometry before fullscreen changes CSS", () => {
  const measureSource = extractAppSource(
    "function measureProjectionViewport()",
    "function viewportValueChanged(previous, next)",
  );
  assert.match(
    measureSource,
    /return expectedExpandedProjectionViewport\(\);/u,
  );
});

test("standard view preserves projection ratio while reserving visible controls and notice summary", () => {
  const styleValues = new Map();
  const padStyle = {
    getPropertyValue(name) { return styleValues.get(name) || ""; },
    setProperty(name, value) { styleValues.set(name, String(value)); },
    removeProperty(name) { styleValues.delete(name); },
  };
  const state = {
    expanded: false,
    padTop: 80,
  };
  const visualViewport = {
    width: 1000,
    height: 600,
    offsetTop: 60,
    scale: 2,
  };
  const sizedElement = (height, hidden = false) => ({
    hidden,
    getBoundingClientRect: () => ({height}),
  });
  const localNetworkPanel = sizedElement(40, true);
  const mediaPermissionPanel = sizedElement(40, true);
  const repairPairingPanel = sizedElement(40, true);
  const viewportAuthorityNotice = sizedElement(36);
  const viewerControls = sizedElement(48);
  const noticeSummary = sizedElement(32);
  const noticePanel = {...sizedElement(240), open: false};
  const main = {};
  const viewer = {hidden: false, clientWidth: 1000};
  const pad = {
    clientWidth: 1000,
    dataset: {},
    parentElement: {clientWidth: 1000},
    style: padStyle,
    getBoundingClientRect: () => ({top: state.padTop}),
  };
  const computedStyle = element => {
    if (element === viewer) return {display: "grid", rowGap: "12px", gap: "12px"};
    if (element === main) return {display: "grid", rowGap: "18px", gap: "18px", paddingBottom: "12px"};
    if (element === noticePanel) {
      return {
        display: "block",
        paddingTop: "14px",
        paddingBottom: "14px",
        borderTopWidth: "1px",
        borderBottomWidth: "1px",
      };
    }
    return {display: element.hidden ? "none" : "block"};
  };
  const context = vm.createContext({
    DYNAMIC_ASPECT_BODY_CLASS: "navonweb-dynamic-aspect",
    Object,
    Number,
    Math,
    document: {body: {classList: {contains: () => true}}},
    window: {visualViewport, getComputedStyle: computedStyle},
    main,
    viewer,
    pad,
    localNetworkPanel,
    mediaPermissionPanel,
    repairPairingPanel,
    viewportAuthorityNotice,
    viewerControls,
    noticePanel,
    noticeSummary,
    expandedViewActive: () => state.expanded,
    browserSessionOwnsViewport: () => true,
    expectedExpandedProjectionViewport: () => ({aspectRatio: 2}),
    projectionSourceAspectRatio: () => 5 / 3,
    layoutViewportDimensions: () => ({width: 1000, height: 900, top: 0, bottom: 900}),
    finiteCssPixels: value => Number.parseFloat(value) || 0,
  });
  vm.runInContext(`
    ${extractAppSource("function visibleViewportBounds()", "function browserDevicePixelRatio()")}
    globalThis.syncBounds = syncStandardProjectionBounds;
  `, context);

  const bounded = context.syncBounds();
  assert.equal(bounded.availableHeight, 380);
  assert.equal(bounded.width, 760);
  assert.equal(bounded.height, 380);
  assert.equal(bounded.width / bounded.height, 2);
  assert.equal(styleValues.get("width"), "760px");
  assert.equal(styleValues.get("height"), "380px");

  noticePanel.open = true;
  const withOpenNotice = context.syncBounds();
  assert.equal(withOpenNotice.availableHeight, 380);
  assert.equal(withOpenNotice.height, 380);

  state.padTop = 40;
  const clippedAboveVisualViewport = context.syncBounds();
  assert.equal(clippedAboveVisualViewport.availableHeight, 400);
  assert.equal(clippedAboveVisualViewport.width, 800);
  assert.equal(clippedAboveVisualViewport.height, 400);

  visualViewport.width = 500;
  visualViewport.height = 800;
  state.padTop = 80;
  const pinchZoomed = context.syncBounds();
  assert.equal(pinchZoomed.availableHeight, 580);
  assert.equal(pinchZoomed.width, 500);
  assert.equal(pinchZoomed.height, 250);

  visualViewport.width = 1000;
  visualViewport.height = 180;
  const tiny = context.syncBounds();
  assert.equal(tiny.availableHeight, 1);
  assert.equal(tiny.width, 2);
  assert.equal(tiny.height, 1);

  state.expanded = true;
  assert.equal(context.syncBounds(), null);
  for (const property of ["width", "height", "aspect-ratio", "justify-self"]) {
    assert.equal(styleValues.has(property), false);
  }
});

test("standard layout observers cover every reserved panel and safe-area padding", () => {
  const observerSource = extractAppSource(
    "function installViewportObservers()",
    "function normalizeLocalNetworkPermissionState(value)",
  );
  for (const element of [
    "main",
    "localNetworkPanel",
    "mediaPermissionPanel",
    "repairPairingPanel",
    "viewerControls",
    "viewportAuthorityNotice",
    "noticePanel",
    "noticeSummary",
  ]) {
    assert.match(observerSource, new RegExp(`viewportResizeObserver\\.observe\\(${element}\\);`, "u"));
  }
  assert.match(
    indexHtml,
    /body\.navonweb-authenticated main \{[\s\S]*padding-bottom: max\(clamp\(12px, 3vw, 32px\), env\(safe-area-inset-bottom\)\);/u,
  );
});

test("all expanded-view entry paths preapply the viewport before native or theater fullscreen", () => {
  const expandedSource = extractAppSource(
    "async function setExpandedView(enabled)",
    "async function toggleFullscreen()",
  );
  const preapplyIndex = expandedSource.indexOf("preapplyExpandedProjectionViewport()");
  const lockIndex = expandedSource.indexOf("lockExpandedProjectionViewport()");
  const nativeFullscreenIndex = expandedSource.indexOf("viewer.requestFullscreen()");
  const theaterIndex = expandedSource.indexOf("setTheaterMode(true)");

  assert.ok(lockIndex >= 0);
  assert.ok(preapplyIndex > lockIndex);
  assert.ok(nativeFullscreenIndex > preapplyIndex);
  assert.ok(theaterIndex > preapplyIndex);
  assert.doesNotMatch(expandedSource, /await preparation/u);
  assert.match(expandedSource, /if \(preparation\) preparation\.catch\(\(\) => null\);/u);
  assert.match(
    appScript,
    /fullscreenButton\.addEventListener\('click',[\s\S]*toggleFullscreen\(\);/u,
  );
  assert.match(
    appScript,
    /setExpandedView\(pinchGesture\.intent === 'expand'\)/u,
  );
  assert.match(
    expandedSource,
    /if \(!expandedViewActive\(\)\) releaseExpandedProjectionViewport\(\);\s*syncFullscreenState\(\);/u,
  );
  const fullscreenChangeSource = extractAppSource(
    "document.addEventListener('fullscreenchange'",
    "document.addEventListener('fullscreenerror'",
  );
  assert.match(
    fullscreenChangeSource,
    /if \(!expandedViewActive\(\) && fullscreenEntryPendingGeneration === 0\) \{\s*releaseExpandedProjectionViewport\(\);/u,
  );
});

test("expanded viewport probe uses the exact fullscreen safe-area padding", () => {
  assert.match(
    indexHtml,
    /#expanded-viewport-probe \{[\s\S]*padding: max\(12px, env\(safe-area-inset-top\)\)[\s\S]*max\(12px, env\(safe-area-inset-left\)\);/u,
  );
  assert.match(indexHtml, /<div id="expanded-viewport-probe" aria-hidden="true"><\/div>/u);
  assert.match(
    indexHtml,
    /body\.navonweb-dynamic-aspect #pad \{\s*aspect-ratio: var\(--navonweb-available-aspect-ratio/u,
  );
});

test("all browser rotation signals refresh the expanded target before layout sync", () => {
  const observerSource = extractAppSource(
    "function handleViewportGeometryChange()",
    "function normalizeLocalNetworkPermissionState(value)",
  );
  const windowResizeSource = extractAppSource(
    "window.addEventListener('resize'",
    "document.addEventListener('fullscreenchange'",
  );

  assert.match(
    observerSource,
    /refreshExpandedProjectionViewport\(\);\s*scheduleViewportLayoutSync\(\);/u,
  );
  assert.match(
    observerSource,
    /window\.visualViewport\.addEventListener\('resize', handleViewportGeometryChange\);/u,
  );
  assert.match(
    observerSource,
    /window\.addEventListener\('orientationchange', handleViewportGeometryChange\);/u,
  );
  assert.match(
    observerSource,
    /window\.screen\.orientation\.addEventListener\('change', handleViewportGeometryChange\);/u,
  );
  assert.match(
    windowResizeSource,
    /window\.addEventListener\('resize', handleViewportGeometryChange\);/u,
  );
});
