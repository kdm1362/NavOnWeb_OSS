import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import path from "node:path";
import vm from "node:vm";

// Shared stand-in runtime for the expanded view (theater mode / native fullscreen). It runs the
// real setExpandedView source slices from app.js against a minimal DOM, Fullscreen API, and
// session history, so the entry / exit / Back-button behaviour is tested on the shipped code.
// Not a test file itself (no ".test." in the name).

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
export const appScript = readFileSync(path.join(assetRoot, "app.js"), "utf8");

export function extractAppSource(startMarker, endMarker) {
  const start = appScript.indexOf(startMarker);
  assert.notEqual(start, -1, `missing app source marker: ${startMarker}`);
  const end = appScript.indexOf(endMarker, start);
  assert.notEqual(end, -1, `missing app source marker: ${endMarker}`);
  return appScript.slice(start, end);
}

const ownershipSource = extractAppSource(
  "function viewerOwnsFullscreen()",
  "function screenWakeLockEligible()",
);
const expandedViewSource = extractAppSource(
  "function setTheaterMode(enabled)",
  "function cloudRelayReconnectDelayMillis(",
);
const fullscreenListenerSource = extractAppSource(
  "document.addEventListener('fullscreenchange'",
  "document.addEventListener('keydown'",
);

// A session history that behaves like the browser's: pushState/replaceState apply at once,
// traversals (back/go, or the user's Back button) land asynchronously and dispatch popstate.
export function createHistory() {
  const entries = [{ state: null }];
  let index = 0;
  const popstateListeners = [];
  const calls = { back: 0, pushState: 0, replaceState: 0 };
  const history = {
    entries,
    calls,
    get length() { return entries.length; },
    get index() { return index; },
    get state() { return entries[index].state; },
    pushState(state) {
      calls.pushState += 1;
      entries.splice(index + 1);
      entries.push({ state });
      index += 1;
    },
    replaceState(state) {
      calls.replaceState += 1;
      entries[index] = { state };
    },
    back() {
      calls.back += 1;
      history.go(-1);
    },
    go(delta) {
      setTimeout(() => {
        const next = index + delta;
        if (next < 0 || next >= entries.length) return;
        index = next;
        for (const listener of popstateListeners) listener({ state: entries[index].state });
      }, 0);
    },
    // Test-only: the browser's own Back button / back gesture (not counted as a page call).
    userBack() { history.go(-1); },
    onPopstate(listener) { popstateListeners.push(listener); },
  };
  return history;
}

export function createRuntime({ requestFullscreen, timeoutMs = 40, history = createHistory() } = {}) {
  const listeners = {};
  const calls = { sync: 0, lock: 0, release: 0, preapply: 0, exit: 0, cancelPointer: 0 };
  const classes = new Set();
  const viewer = {};
  const document = {
    fullscreenEnabled: true,
    fullscreenElement: null,
    body: {
      classList: {
        toggle: (name, force) => { if (force) classes.add(name); else classes.delete(name); return classes.has(name); },
        remove: name => classes.delete(name),
        add: name => classes.add(name),
        contains: name => classes.has(name),
      },
    },
    addEventListener: (type, fn) => { listeners[type] = fn; },
    exitFullscreen: () => { calls.exit += 1; document.fullscreenElement = null; return Promise.resolve(); },
  };
  if (requestFullscreen) viewer.requestFullscreen = () => requestFullscreen(document, viewer);
  const window = {
    setTimeout: globalThis.setTimeout,
    clearTimeout: globalThis.clearTimeout,
    addEventListener: (type, fn) => {
      if (type === "popstate" && history) history.onPopstate(fn);
      listeners[`window:${type}`] = fn;
    },
  };
  // history === null models a browser without a History API.
  if (history) window.history = history;
  const context = vm.createContext({
    NATIVE_FULLSCREEN_TIMEOUT_MS: timeoutMs,
    document,
    viewer,
    window,
    cancelActivePointer: () => { calls.cancelPointer += 1; },
    cancelPointerInteraction: () => {},
    lockExpandedProjectionViewport: () => { calls.lock += 1; return {}; },
    releaseExpandedProjectionViewport: () => { calls.release += 1; },
    preapplyExpandedProjectionViewport: () => { calls.preapply += 1; return null; },
    syncFullscreenState: () => { calls.sync += 1; },
    theaterMode: false,
    expandedViewRequestGeneration: 0,
    fullscreenEntryPendingGeneration: 0,
    expandedViewWanted: false,
    expandedViewHistoryEntry: false,
    expandedViewHistoryPopPending: false,
  });
  vm.runInContext(`
    ${ownershipSource}
    ${expandedViewSource}
    ${fullscreenListenerSource}
    globalThis.setExpandedView = setExpandedView;
    globalThis.expandedViewActive = expandedViewActive;
    globalThis.neutralizeStaleExpandedViewHistoryState = neutralizeStaleExpandedViewHistoryState;
  `, context);
  return { context, document, viewer, classes, calls, listeners, history };
}

export const wait = ms => new Promise(resolve => setTimeout(resolve, ms));

// Lets queued history traversals land (they are one macrotask away, like real popstate).
export const settleHistory = () => wait(5);
