import assert from "node:assert/strict";
import test from "node:test";
import { createRuntime, wait } from "./expanded-view-runtime.mjs";

// The expanded view must look fullscreen before the Fullscreen API is consulted, so browsers
// that reject, ignore, or never settle requestFullscreen() still end up in theater mode, and a
// stalled native request can never lock the toggle. These tests drive the real setExpandedView
// source with a minimal DOM stand-in (see expanded-view-runtime.mjs).

test("theater mode is engaged before the native request is made", async () => {
  let theaterAtRequest = null;
  const rt = createRuntime({
    requestFullscreen: (document, viewer) => {
      theaterAtRequest = document.body.classList.contains("theater-mode");
      document.fullscreenElement = viewer;
      return Promise.resolve();
    },
  });
  await rt.context.setExpandedView(true);
  assert.equal(theaterAtRequest, true, "theater mode must be visible before requestFullscreen()");
  assert.equal(rt.calls.lock, 1);
  assert.equal(rt.calls.preapply, 1);
  assert.equal(rt.context.fullscreenEntryPendingGeneration, 0);
});

test("a native entry upgrades theater mode instead of stacking on it", async () => {
  const rt = createRuntime({
    requestFullscreen: (document, viewer) => { document.fullscreenElement = viewer; return Promise.resolve(); },
  });
  await rt.context.setExpandedView(true);
  rt.listeners.fullscreenchange();
  assert.equal(rt.classes.has("theater-mode"), false, "theater class must drop once native fullscreen owns the view");
  assert.equal(rt.context.theaterMode, false);
  assert.equal(rt.context.expandedViewActive(), true);
  assert.equal(rt.calls.release, 0, "the locked entry viewport survives the theater -> native swap");
});

test("a never-settling request leaves theater mode on and releases the toggle", async () => {
  const rt = createRuntime({ requestFullscreen: () => new Promise(() => {}), timeoutMs: 30 });
  const entry = rt.context.setExpandedView(true);
  assert.equal(rt.classes.has("theater-mode"), true, "theater mode is immediate");
  await entry;
  assert.equal(rt.context.fullscreenEntryPendingGeneration, 0, "the stalled request must not keep the lock");
  assert.equal(rt.context.theaterMode, true);
  await rt.context.setExpandedView(false);
  assert.equal(rt.classes.has("theater-mode"), false, "exit still works after a stalled request");
  await rt.context.setExpandedView(true);
  assert.equal(rt.classes.has("theater-mode"), true, "re-entry still works after a stalled request");
});

test("a rejected request keeps theater mode", async () => {
  const rt = createRuntime({ requestFullscreen: () => Promise.reject(new TypeError("not granted")) });
  await rt.context.setExpandedView(true);
  assert.equal(rt.context.theaterMode, true);
  assert.equal(rt.context.fullscreenEntryPendingGeneration, 0);
  assert.equal(rt.document.fullscreenElement, null);
});

test("browsers without the Fullscreen API get theater mode directly", async () => {
  const rt = createRuntime();
  await rt.context.setExpandedView(true);
  assert.equal(rt.context.theaterMode, true);
  assert.equal(rt.context.expandedViewActive(), true);
});

test("leaving while a request is in flight undoes a late native entry", async () => {
  let settle;
  const rt = createRuntime({
    requestFullscreen: () => new Promise(resolve => { settle = resolve; }),
    timeoutMs: 5000,
  });
  const entry = rt.context.setExpandedView(true);
  await rt.context.setExpandedView(false);
  assert.equal(rt.classes.has("theater-mode"), false, "exit is not blocked by the pending request");
  assert.equal(rt.context.fullscreenEntryPendingGeneration, 0);
  // The browser finally enters fullscreen after the user already left.
  rt.document.fullscreenElement = rt.viewer;
  settle();
  await entry;
  rt.listeners.fullscreenchange();
  await wait(0);
  assert.equal(rt.calls.exit, 1, "the late native entry must be exited again");
});
