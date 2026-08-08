import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import path from "node:path";
import test from "node:test";

const cloudflareRoot = path.resolve(
  path.dirname(fileURLToPath(import.meta.url)),
  "..",
  "..",
);
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

function fakeElement() {
  const attributes = new Map();
  return {
    hidden: false,
    textContent: "",
    attributes,
    setAttribute(name, value) {
      attributes.set(name, String(value));
    },
    removeAttribute(name) {
      attributes.delete(name);
    },
  };
}

function loadWaitingRuntime(locale = "en") {
  const source = sourceBetween(
    "  function formatVideoWaitingDuration",
    "  function clearFrame",
  );
  const classes = new Set();
  const streamState = {
    classList: {
      add(name) { classes.add(name); },
      remove(name) { classes.delete(name); },
      contains(name) { return classes.has(name); },
    },
  };
  const streamStateMessage = fakeElement();
  const streamWaitTime = fakeElement();
  let now = 100;
  let nextTimerId = 1;
  let intervalStarts = 0;
  const intervalCallbacks = new Map();
  const clearedIntervals = [];
  const window = {
    setInterval(callback) {
      const id = nextTimerId++;
      intervalStarts += 1;
      intervalCallbacks.set(id, callback);
      return id;
    },
    clearInterval(id) {
      clearedIntervals.push(id);
      intervalCallbacks.delete(id);
    },
  };
  const translations = locale === "ko"
    ? {
      androidAutoWaiting: "Android Auto 연결 대기",
      videoWaiting: "영상 연결 대기",
      videoWaitingElapsed: "대기 시간 {time}",
      serverWaiting: "서버 연결 대기",
    }
    : {
      androidAutoWaiting: "Waiting for Android Auto",
      videoWaiting: "Waiting for video",
      videoWaitingElapsed: "Waiting time {time}",
      serverWaiting: "Waiting for server",
    };
  const runtime = Function(
    "window",
    "performance",
    "streamState",
    "streamStateMessage",
    "streamWaitTime",
    "t",
    `"use strict";
let streamStateKey = "androidAutoWaiting";
let videoWaitingStartedAt = null;
let videoWaitingTimer = 0;
${source}
return {
  formatVideoWaitingDuration,
  setStreamState,
  renderVideoWaitingElapsed,
  getState: () => ({ streamStateKey, videoWaitingStartedAt, videoWaitingTimer }),
};`,
  )(
    window,
    { now: () => now },
    streamState,
    streamStateMessage,
    streamWaitTime,
    key => translations[key] || key,
  );

  return {
    ...runtime,
    classes,
    streamStateMessage,
    streamWaitTime,
    setNow(value) { now = value; },
    tick() {
      for (const callback of intervalCallbacks.values()) callback();
    },
    intervalStarts: () => intervalStarts,
    clearedIntervals,
  };
}

test("video waiting UI is compact, accessible, and respects reduced motion", () => {
  assert.match(indexHtml, /id="stream-state-content"/u);
  assert.match(indexHtml, /id="stream-state-spinner" aria-hidden="true"/u);
  assert.match(
    indexHtml,
    /id="stream-state-message" role="status" aria-live="polite" aria-atomic="true"/u,
  );
  assert.match(indexHtml, /id="stream-wait-time" aria-live="off" hidden/u);
  assert.match(indexHtml, /#stream-state-content \{[\s\S]*?max-width: min\(88%, 520px\);/u);
  assert.match(indexHtml, /@media \(prefers-reduced-motion: reduce\)/u);
  assert.match(indexHtml, /#stream-state-spinner \{ animation: none; \}/u);
});

test("video waiting timer starts once, advances, and resets on every exit state", () => {
  const runtime = loadWaitingRuntime("en");

  runtime.setStreamState("videoWaiting");
  assert.equal(runtime.streamStateMessage.textContent, "Waiting for video");
  assert.equal(runtime.streamWaitTime.textContent, "Waiting time 0:00");
  assert.equal(runtime.streamWaitTime.attributes.get("datetime"), "PT0S");
  assert.equal(runtime.streamWaitTime.hidden, false);
  assert.equal(runtime.classes.has("video-waiting"), true);
  assert.equal(runtime.intervalStarts(), 1);

  runtime.setNow(5_499);
  runtime.tick();
  assert.equal(runtime.streamWaitTime.textContent, "Waiting time 0:05");
  runtime.setStreamState("videoWaiting");
  assert.equal(runtime.intervalStarts(), 1, "status polling must not restart the timer");

  runtime.setNow(65_499);
  runtime.tick();
  assert.equal(runtime.streamWaitTime.textContent, "Waiting time 1:05");
  runtime.setStreamState("serverWaiting");
  assert.equal(runtime.streamStateMessage.textContent, "Waiting for server");
  assert.equal(runtime.streamWaitTime.hidden, true);
  assert.equal(runtime.streamWaitTime.textContent, "");
  assert.equal(runtime.streamWaitTime.attributes.has("datetime"), false);
  assert.equal(runtime.classes.has("video-waiting"), false);
  assert.deepEqual(runtime.getState(), {
    streamStateKey: "serverWaiting",
    videoWaitingStartedAt: null,
    videoWaitingTimer: 0,
  });
  assert.equal(runtime.clearedIntervals.length, 1);

  runtime.setNow(70_000);
  runtime.setStreamState("videoWaiting");
  assert.equal(runtime.streamWaitTime.textContent, "Waiting time 0:00");
  assert.equal(runtime.intervalStarts(), 2);
  runtime.setStreamState("");
  assert.equal(runtime.streamStateMessage.textContent, "");
  assert.equal(runtime.streamStateMessage.attributes.has("data-i18n"), false);
  assert.equal(runtime.streamWaitTime.hidden, true);
  assert.equal(runtime.clearedIntervals.length, 2);
});

test("waiting duration and timer label support long waits and Korean", () => {
  const runtime = loadWaitingRuntime("ko");
  assert.equal(runtime.formatVideoWaitingDuration(-1), "0:00");
  assert.equal(runtime.formatVideoWaitingDuration(65), "1:05");
  assert.equal(runtime.formatVideoWaitingDuration(3_661), "1:01:01");

  runtime.setStreamState("videoWaiting");
  runtime.setNow(2_100);
  runtime.tick();
  assert.equal(runtime.streamStateMessage.textContent, "영상 연결 대기");
  assert.equal(runtime.streamWaitTime.textContent, "대기 시간 0:02");
});

test("all stream status transitions use the timer-aware renderer", () => {
  assert.doesNotMatch(appScript, /streamState\.textContent/u);
  assert.match(appScript, /pad\.classList\.add\('frame-ready'\);\s*setStreamState\(''\);/u);
  assert.match(appScript, /pad\.classList\.add\('webrtc-ready'\);\s*setStreamState\(''\);/u);
  assert.match(appScript, /setStreamState\('videoWaiting'\)/u);
  assert.match(appScript, /setStreamState\('serverWaiting'\)/u);
  assert.match(appScript, /videoWaitingElapsed: 'Waiting time \{time\}'/u);
  assert.match(appScript, /videoWaitingElapsed: '대기 시간 \{time\}'/u);
});
