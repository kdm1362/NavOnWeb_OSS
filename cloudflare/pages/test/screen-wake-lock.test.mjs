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

function extractAppSource(startMarker, endMarker) {
  const start = appScript.indexOf(startMarker);
  assert.notEqual(start, -1, `missing app source marker: ${startMarker}`);
  const end = appScript.indexOf(endMarker, start);
  assert.notEqual(end, -1, `missing app source marker: ${endMarker}`);
  return appScript.slice(start, end);
}

const wakeLockSource = extractAppSource(
  "function screenWakeLockEligible()",
  "function hideFullscreenHint()",
);

function loadWakeLockRuntime({request} = {}) {
  const context = vm.createContext({
    document: {hidden: false},
    navigator: request ? {wakeLock: {request}} : {},
    Promise,
  });
  vm.runInContext(`
    let browserCredential = "credential-a";
    let pageActive = true;
    let expanded = true;
    let screenWakeLock = null;
    let screenWakeLockRequest = null;
    let screenWakeLockGeneration = 0;
    function expandedViewActive() { return expanded; }
    ${wakeLockSource}
    globalThis.syncWakeLock = syncScreenWakeLock;
    globalThis.releaseWakeLock = releaseScreenWakeLock;
    globalThis.setCredential = value => { browserCredential = value; };
    globalThis.setPageActive = value => { pageActive = value; };
    globalThis.setExpanded = value => { expanded = value; };
  `, context);
  return context;
}

function createLock() {
  return {
    released: false,
    releaseCount: 0,
    release() {
      this.released = true;
      this.releaseCount += 1;
      return Promise.resolve();
    },
    addEventListener() {},
  };
}

async function flushPromises() {
  await Promise.resolve();
  await Promise.resolve();
}

test("expanded authenticated views hold a screen wake lock and reacquire it after visibility returns", async () => {
  const locks = [];
  const context = loadWakeLockRuntime({
    request(kind) {
      assert.equal(kind, "screen");
      const lock = createLock();
      locks.push(lock);
      return Promise.resolve(lock);
    },
  });

  context.syncWakeLock();
  await flushPromises();
  context.syncWakeLock();
  assert.equal(locks.length, 1, "an active lock must not be requested twice");

  context.document.hidden = true;
  context.syncWakeLock();
  assert.equal(locks[0].releaseCount, 1);

  context.document.hidden = false;
  context.syncWakeLock();
  await flushPromises();
  assert.equal(locks.length, 2, "visibility restoration must acquire a fresh lock");

  context.setCredential("");
  context.syncWakeLock();
  assert.equal(locks[1].releaseCount, 1, "authentication loss must release the lock");
});

test("a wake lock that resolves after fullscreen exit is immediately released", async () => {
  let resolveRequest;
  const pending = new Promise(resolve => { resolveRequest = resolve; });
  const context = loadWakeLockRuntime({request: () => pending});
  const lock = createLock();

  context.syncWakeLock();
  context.setExpanded(false);
  context.syncWakeLock();
  resolveRequest(lock);
  await flushPromises();

  assert.equal(lock.releaseCount, 1);
});

test("unsupported Wake Lock API is ignored without changing projection state", () => {
  const context = loadWakeLockRuntime();
  assert.doesNotThrow(() => context.syncWakeLock());
  context.setPageActive(false);
  assert.doesNotThrow(() => context.syncWakeLock());
});

test("fullscreen, authentication and page lifecycle events synchronize the wake lock", () => {
  const fullscreenState = extractAppSource(
    "function syncFullscreenState()",
    "function setTheaterMode(enabled)",
  );
  const authenticatedView = extractAppSource(
    "function showAuthenticatedView(authenticated)",
    "function projectionSourceAspectRatio()",
  );
  const visibilityHandler = extractAppSource(
    "document.addEventListener('visibilitychange'",
    "function resumeSameOriginConnection()",
  );
  const pageHideHandler = extractAppSource(
    "window.addEventListener('pagehide'",
    "window.addEventListener('pageshow'",
  );

  assert.match(fullscreenState, /syncScreenWakeLock\(\)/u);
  assert.match(authenticatedView, /if \(authenticated\) syncScreenWakeLock\(\);\s*else releaseScreenWakeLock\(\);/u);
  assert.match(visibilityHandler, /syncScreenWakeLock\(\)/u);
  assert.match(pageHideHandler, /pageActive = false;\s*releaseScreenWakeLock\(\);/u);
});
