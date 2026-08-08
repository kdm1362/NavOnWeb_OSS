import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import path from "node:path";
import test from "node:test";
import vm from "node:vm";

const appScript = readFileSync(
  path.resolve(
    path.dirname(fileURLToPath(import.meta.url)),
    "..",
    "..",
    "..",
    "app",
    "src",
    "main",
    "assets",
    "tesla",
    "app.js",
  ),
  "utf8",
);

function extractAppSource(startMarker, endMarker) {
  const start = appScript.indexOf(startMarker);
  assert.notEqual(start, -1, `missing app source marker: ${startMarker}`);
  const end = appScript.indexOf(endMarker, start);
  assert.notEqual(end, -1, `missing app source marker: ${endMarker}`);
  return appScript.slice(start, end);
}

const comparisonSource = extractAppSource(
  "function sameProjectionMediaIdentity(left, right)",
  "function normalizeProjectionViewport(value)",
);

function loadComparisonRuntime() {
  const context = vm.createContext({});
  vm.runInContext(`
    ${comparisonSource}
    globalThis.sameMedia = sameProjectionMediaIdentity;
    globalThis.sameProfile = sameProjectionProfile;
  `, context);
  return context;
}

function profile(overrides = {}) {
  return {
    id: "premium-1080p",
    width: 1080,
    height: 1920,
    androidAutoFramesPerSecond: 60,
    webRtcFramesPerSecond: 30,
    densityDpi: 140,
    sourceAspectWidth: 9,
    sourceAspectHeight: 16,
    ...overrides,
  };
}

test("DPI-only profile metadata does not change WebRTC media identity", () => {
  const context = loadComparisonRuntime();
  const current = profile();
  const customDpi = profile({densityDpi: 180});

  assert.equal(context.sameMedia(current, customDpi), true);
  assert.equal(context.sameProfile(current, customDpi), false);
});

test("resolution and frame-rate changes still change WebRTC media identity", () => {
  const context = loadComparisonRuntime();
  const current = profile();

  assert.equal(context.sameMedia(current, profile({width: 720, height: 1280})), false);
  assert.equal(context.sameMedia(current, profile({webRtcFramesPerSecond: 60})), false);
});

test("DPI-only apply branch preserves frames and the WebRTC peer", () => {
  const applySource = extractAppSource(
    "function applyProjectionGeometry(value)",
    "function clearFrame()",
  );
  const metadataBranch = applySource.slice(
    applySource.indexOf("if (!mediaIdentityChanged)"),
    applySource.indexOf("activeProjectionViewport = zeroProjectionViewport(next)"),
  );

  assert.doesNotMatch(metadataBranch, /clearFrame\(/u);
  assert.doesNotMatch(metadataBranch, /resetWebRtc\(/u);
  assert.doesNotMatch(metadataBranch, /projectionProfileRevision\s*\+=/u);
  assert.match(metadataBranch, /densityDpi: next\.densityDpi/u);
});
