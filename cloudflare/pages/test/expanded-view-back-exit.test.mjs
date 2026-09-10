import assert from "node:assert/strict";
import test from "node:test";
import { createHistory, createRuntime, settleHistory } from "./expanded-view-runtime.mjs";

// The browser's Back button (toolbar arrow, Alt+Left, the Android back gesture, a car browser's
// back key) leaves the expanded view instead of the page: entering pushes one history entry
// while the user's gesture is still fresh, popstate exits the view, and every other exit path
// pops the entry itself so the next Back behaves normally.

const MARKER = { navonwebExpandedView: true };
// The state object is created inside the vm realm, so compare by content rather than prototype.
const assertOwnedState = history => assert.equal(history.state && history.state.navonwebExpandedView, true);
const nativeGranted = (document, viewer) => { document.fullscreenElement = viewer; return Promise.resolve(); };
const nativeRejected = () => Promise.reject(new TypeError("not granted"));

test("entering pushes exactly one history entry, before the native request", async () => {
  let lengthAtRequest = 0;
  const rt = createRuntime({
    requestFullscreen: (document, viewer) => {
      lengthAtRequest = rt.history.length;
      return nativeGranted(document, viewer);
    },
  });
  await rt.context.setExpandedView(true);
  assert.equal(lengthAtRequest, 2, "the entry is pushed inside the gesture, ahead of requestFullscreen()");
  assertOwnedState(rt.history);
  assert.equal(rt.context.expandedViewHistoryEntry, true);
  await rt.context.setExpandedView(true);
  assert.equal(rt.history.length, 2, "re-entering an active view pushes nothing");
});

test("the browser's Back button leaves theater mode instead of the page", async () => {
  const rt = createRuntime({ requestFullscreen: nativeRejected });
  await rt.context.setExpandedView(true);
  assert.equal(rt.classes.has("theater-mode"), true);
  rt.history.userBack();
  await settleHistory();
  assert.equal(rt.classes.has("theater-mode"), false);
  assert.equal(rt.context.theaterMode, false);
  assert.equal(rt.context.expandedViewWanted, false);
  assert.equal(rt.context.expandedViewHistoryEntry, false);
  assert.equal(rt.history.index, 0, "the page stays on its original entry");
  assert.equal(rt.history.calls.back, 0, "no second pop that would leave the page");
  assert.equal(rt.calls.release, 1, "the expanded viewport is released like any other exit");
});

test("Back during native fullscreen exits fullscreen as well", async () => {
  const rt = createRuntime({ requestFullscreen: nativeGranted });
  await rt.context.setExpandedView(true);
  rt.listeners.fullscreenchange();
  assert.equal(rt.context.expandedViewActive(), true);
  rt.history.userBack();
  await settleHistory();
  assert.equal(rt.calls.exit, 1);
  rt.listeners.fullscreenchange();
  assert.equal(rt.context.expandedViewActive(), false);
  assert.equal(rt.history.calls.back, 0);
  assert.equal(rt.history.index, 0);
});

test("Back while the native request is still pending exits cleanly", async () => {
  const rt = createRuntime({ requestFullscreen: () => new Promise(() => {}), timeoutMs: 5000 });
  const entry = rt.context.setExpandedView(true);
  rt.history.userBack();
  await settleHistory();
  assert.equal(rt.context.theaterMode, false);
  assert.equal(rt.context.fullscreenEntryPendingGeneration, 0);
  assert.equal(rt.history.index, 0);
  entry.catch(() => null);
});

test("Esc and pinch exits pop the entry themselves so the next Back leaves the page", async () => {
  const rt = createRuntime({ requestFullscreen: nativeRejected });
  await rt.context.setExpandedView(true);
  await rt.context.setExpandedView(false);
  assert.equal(rt.history.calls.back, 1, "the page consumes its own entry");
  assert.equal(rt.context.expandedViewHistoryPopPending, true);
  const cancels = rt.calls.cancelPointer;
  await settleHistory();
  assert.equal(rt.context.expandedViewHistoryPopPending, false);
  assert.equal(rt.context.expandedViewHistoryEntry, false);
  assert.equal(rt.history.index, 0);
  assert.equal(rt.calls.cancelPointer, cancels, "the self-inflicted popstate must not run the exit path again");
  // Entering again pushes a fresh entry that Back honours.
  await rt.context.setExpandedView(true);
  assertOwnedState(rt.history);
  assert.equal(rt.context.expandedViewHistoryEntry, true);
  rt.history.userBack();
  await settleHistory();
  assert.equal(rt.context.theaterMode, false);
  assert.equal(rt.history.index, 0);
});

test("a native exit performed by the browser drops the entry too", async () => {
  const rt = createRuntime({ requestFullscreen: nativeGranted });
  await rt.context.setExpandedView(true);
  rt.listeners.fullscreenchange();
  // The user pressed Esc inside native fullscreen; the browser leaves it on its own.
  rt.document.fullscreenElement = null;
  rt.listeners.fullscreenchange();
  assert.equal(rt.history.calls.back, 1);
  await settleHistory();
  assert.equal(rt.context.expandedViewHistoryEntry, false);
  assert.equal(rt.history.index, 0);
});

test("re-entering before a self-pop lands keeps ownership straight", async () => {
  const rt = createRuntime({ requestFullscreen: nativeRejected });
  await rt.context.setExpandedView(true);
  await rt.context.setExpandedView(false);
  await rt.context.setExpandedView(true);
  assert.equal(rt.history.length, 3, "the re-entry pushed on top of the still-current entry");
  await settleHistory();
  assert.equal(rt.context.expandedViewHistoryEntry, true, "the pop landed on our own earlier entry");
  assert.equal(rt.context.theaterMode, true);
  rt.history.userBack();
  await settleHistory();
  assert.equal(rt.context.theaterMode, false);
  assert.equal(rt.history.index, 0);
});

test("a marker left behind by a reload is neutralized, not adopted", async () => {
  const history = createHistory();
  history.pushState(MARKER);
  const rt = createRuntime({ requestFullscreen: nativeRejected, history });
  rt.context.neutralizeStaleExpandedViewHistoryState();
  assert.equal(history.state, null);
  assert.equal(history.calls.replaceState, 1);
  assert.equal(rt.context.expandedViewHistoryEntry, false);
  // A Back on that neutral entry is an ordinary navigation: the view is not touched.
  history.userBack();
  await settleHistory();
  assert.equal(rt.calls.cancelPointer, 0);
  // And a fresh entry still works afterwards.
  await rt.context.setExpandedView(true);
  assertOwnedState(history);
});

test("pushState failures and missing History APIs never block the expanded view", async () => {
  const throwing = createHistory();
  throwing.pushState = () => { throw new Error("SecurityError"); };
  const sandboxed = createRuntime({ requestFullscreen: nativeRejected, history: throwing });
  await sandboxed.context.setExpandedView(true);
  assert.equal(sandboxed.context.theaterMode, true);
  assert.equal(sandboxed.context.expandedViewHistoryEntry, false);
  await sandboxed.context.setExpandedView(false);
  assert.equal(throwing.calls.back, 0);
  assert.equal(sandboxed.context.theaterMode, false);

  const historyless = createRuntime({ history: null });
  await historyless.context.setExpandedView(true);
  assert.equal(historyless.context.theaterMode, true);
  await historyless.context.setExpandedView(false);
  assert.equal(historyless.context.theaterMode, false);
});
