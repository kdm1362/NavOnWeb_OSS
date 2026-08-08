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

const expectedViewportSource = extractAppSource(
  "function expandedViewportInsets()",
  "function measureProjectionViewport()",
);

function loadExpectedViewportRuntime({nativeFullscreen = true} = {}) {
  const viewer = {hidden: false};
  if (nativeFullscreen) viewer.requestFullscreen = () => Promise.resolve();
  const context = vm.createContext({
    DEVELOPMENT_TESLA_WIDTH_SCALE: 0.68,
    browserDevicePixelRatio: () => 2,
    developmentTeslaDriving: false,
    document: {fullscreenEnabled: true},
    expandedViewportProbe: {},
    expandedViewportTarget: null,
    finiteCssPixels: value => Number.parseFloat(value) || 0,
    layoutViewportDimensions: () => ({width: 760, height: 1100, scale: 1}),
    theaterMode: false,
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
