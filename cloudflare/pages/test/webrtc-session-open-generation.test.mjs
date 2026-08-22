import assert from "node:assert/strict";
import {readFileSync} from "node:fs";
import {fileURLToPath} from "node:url";
import path from "node:path";
import test from "node:test";
import vm from "node:vm";

const cloudflareRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..", "..");
const appScript = readFileSync(
  path.resolve(cloudflareRoot, "..", "app", "src", "main", "assets", "web", "app.js"),
  "utf8",
);

function extractOpenAdmissionSource() {
  const startMarker = "function webRtcStartIsCurrent";
  const endMarker = "\n  async function waitForWebRtcAnswer";
  const start = appScript.indexOf(startMarker);
  const end = appScript.indexOf(endMarker, start);
  assert.notEqual(start, -1, "missing WebRTC start generation predicate");
  assert.notEqual(end, -1, "missing WebRTC session response admission end marker");
  return appScript.slice(start, end);
}

function deferred() {
  let resolve;
  const promise = new Promise(resolvePromise => {
    resolve = resolvePromise;
  });
  return {promise, resolve};
}

function loadRuntime() {
  const cleanup = [];
  const context = vm.createContext({cleanup});
  vm.runInContext(`
    const WEBRTC_SESSION_PATTERN = /^[A-Za-z0-9_-]{16,64}$/;
    let webRtcGeneration = 1;
    let webRtcPeer = {id: "old-peer"};
    let browserCredential = "old-credential";
    let webRtcSessionId = "";
    let invalidations = 0;
    function enqueueWebRtcSessionClose(sessionId, credential) {
      cleanup.push({sessionId, credential});
    }
    function invalidateCredential() { invalidations += 1; }
    function t(value) { return value; }
    ${extractOpenAdmissionSource()}
    const originalPeer = webRtcPeer;
    globalThis.consume = response => consumeWebRtcSessionOpenResponse(
      response,
      1,
      originalPeer,
      "old-credential"
    );
    globalThis.reset = (credential = "old-credential") => {
      webRtcGeneration += 1;
      webRtcPeer = {id: "replacement-peer"};
      browserCredential = credential;
    };
    globalThis.setSessionId = value => { webRtcSessionId = value; };
    globalThis.state = () => ({
      browserCredential,
      invalidations,
      sessionId: webRtcSessionId,
    });
  `, context);
  return {
    cleanup,
    consume: context.consume,
    reset: context.reset,
    setSessionId: context.setSessionId,
    state: context.state,
  };
}

const acceptedSessionId = "session_abcdefghijkl";
const replacementSessionId = "session_bcdefghijklm";

test("current accepted response alone publishes the global session id", async () => {
  const runtime = loadRuntime();
  const opened = await runtime.consume({
    status: 202,
    ok: true,
    json: () => Promise.resolve({sessionId: acceptedSessionId, state: "pending"}),
  });

  assert.equal(opened.sessionId, acceptedSessionId);
  assert.equal(runtime.state().sessionId, acceptedSessionId);
  assert.equal(runtime.cleanup.length, 0);
});

test("reset during accepted response parsing cleans the stale session without publishing it", async () => {
  const runtime = loadRuntime();
  runtime.setSessionId(replacementSessionId);
  const body = deferred();
  const pending = runtime.consume({status: 202, ok: true, json: () => body.promise});

  runtime.reset();
  body.resolve({sessionId: acceptedSessionId, state: "pending"});

  assert.equal(await pending, null);
  assert.equal(runtime.state().sessionId, replacementSessionId);
  assert.equal(runtime.cleanup.length, 1);
  assert.equal(runtime.cleanup[0].sessionId, acceptedSessionId);
  assert.equal(runtime.cleanup[0].credential, "old-credential");
});

test("stale 401 cannot invalidate the current credential", async () => {
  const runtime = loadRuntime();
  runtime.reset("new-credential");

  assert.equal(await runtime.consume({status: 401, ok: false}), null);
  assert.equal(runtime.state().invalidations, 0);
  assert.equal(runtime.state().browserCredential, "new-credential");
});

test("current 401 still invalidates the matching credential", async () => {
  const runtime = loadRuntime();

  assert.equal(await runtime.consume({status: 401, ok: false}), null);
  assert.equal(runtime.state().invalidations, 1);
});

test("stale non-success response is ignored by the replacement generation", async () => {
  const runtime = loadRuntime();
  runtime.reset();

  assert.equal(await runtime.consume({status: 429, ok: false}), null);
  assert.equal(runtime.state().sessionId, "");
  assert.equal(runtime.cleanup.length, 0);
});

test("production signaling binds POST and answer polling to local generation state", () => {
  const startSource = appScript.match(/async function startWebRtc[\s\S]*?\n  \}/u)?.[0] ?? "";
  const answerStart = appScript.indexOf("async function waitForWebRtcAnswer");
  const answerEnd = appScript.indexOf("\n  async function startWebRtc", answerStart);
  const answerSource = appScript.slice(answerStart, answerEnd);

  assert.match(
    startSource,
    /api\('\/api\/webrtc\/session\?codec=auto',[\s\S]*?\}, requestedCredential\)/u,
  );
  assert.match(startSource, /await consumeWebRtcSessionOpenResponse\(/u);
  assert.match(
    startSource,
    /waitForWebRtcAnswer\([\s\S]*acceptedSessionId,[\s\S]*requestedCredential/u,
  );
  assert.match(answerSource, /encodeURIComponent\(sessionId\)/u);
  assert.doesNotMatch(answerSource, /encodeURIComponent\(webRtcSessionId\)/u);
  assert.match(answerSource, /\{\},\s+credential\s+\)/u);
});
