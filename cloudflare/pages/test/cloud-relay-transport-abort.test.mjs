import assert from "node:assert/strict";
import {readFileSync} from "node:fs";
import {fileURLToPath} from "node:url";
import path from "node:path";
import test from "node:test";
import vm from "node:vm";

const cloudflareRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..", "..");
const appScript = readFileSync(
  path.resolve(cloudflareRoot, "..", "app", "src", "main", "assets", "tesla", "app.js"),
  "utf8",
);

function extractCloudRelayTransportSource() {
  const startMarker = "class CloudRelayTransport";
  const endMarker = "\n  function relayHeaders";
  const start = appScript.indexOf(startMarker);
  const end = appScript.indexOf(endMarker, start);
  assert.notEqual(start, -1, "missing cloud relay transport");
  assert.notEqual(end, -1, "missing cloud relay transport end marker");
  return appScript.slice(start, end);
}

function deferred() {
  let resolve;
  const promise = new Promise(resolvePromise => {
    resolve = resolvePromise;
  });
  return {promise, resolve};
}

function loadTransport() {
  const context = vm.createContext({
    CLOUD_RELAY_CONNECT_TIMEOUT_MILLIS: 1000,
    CLOUD_RELAY_MAX_BODY_BYTES: 128 * 1024,
    CLOUD_RELAY_MAX_RESPONSE_BYTES: 160 * 1024,
    CLOUD_RELAY_REQUEST_ID_PATTERN: /^[A-Za-z0-9_-]{16,64}$/u,
    CLOUD_RELAY_REQUEST_TIMEOUT_MILLIS: 1000,
    Date,
    DOMException,
    JSON,
    Response,
    String,
    Uint8Array,
    WebSocket: {OPEN: 1, CLOSING: 2},
    abortError() {
      return new DOMException("The operation was aborted", "AbortError");
    },
    base64NoWrap() {
      return "";
    },
    cloudRelayReconnectDelayMillis() {
      return 1;
    },
    decodeBase64() {
      return new Uint8Array(0);
    },
    diagnosticClockMillis() {
      return 0;
    },
    raceAbort(promise) {
      return promise;
    },
    recordConnectionTiming() {},
    relayHeaders() {
      return {};
    },
    relayRequestBody() {
      return new Uint8Array(0);
    },
    relayRequestId() {
      return "abcdefghijklmnop";
    },
    window: {clearTimeout, setTimeout},
  });
  vm.runInContext(`
    ${extractCloudRelayTransportSource()}
    globalThis.createTransport = () => new CloudRelayTransport({webSocketUrl: "wss://example.test"});
  `, context);
  return context.createTransport();
}

function openSocket() {
  return {
    readyState: 1,
    sent: [],
    send(value) {
      this.sent.push(value);
    },
  };
}

test("abort during reconnect is rechecked before any RPC envelope is sent", async () => {
  const transport = loadTransport();
  const socket = openSocket();
  const connected = deferred();
  const controller = new AbortController();
  transport.socket = socket;
  transport.connect = () => connected.promise;

  const request = transport.request("/api/projection/viewport", {
    method: "POST",
    signal: controller.signal,
  });
  controller.abort();
  connected.resolve();

  await assert.rejects(request, error => error && error.name === "AbortError");
  assert.equal(socket.sent.length, 0);
  assert.equal(transport.pending.size, 0);
});

test("abort observed while installing the listener is rechecked immediately before send", async () => {
  const transport = loadTransport();
  const socket = openSocket();
  transport.socket = socket;
  transport.connect = () => Promise.resolve();
  const signal = {
    aborted: false,
    addEventListener() {
      this.aborted = true;
    },
    removeEventListener() {},
  };

  await assert.rejects(
    transport.request("/api/webrtc/session/session_abcdefghijkl", {
      method: "DELETE",
      signal,
    }),
    error => error && error.name === "AbortError",
  );
  assert.equal(socket.sent.length, 0);
  assert.equal(transport.pending.size, 0);
});

test("non-aborted request still emits exactly one envelope", async () => {
  const transport = loadTransport();
  const socket = openSocket();
  transport.socket = socket;
  transport.connect = () => Promise.resolve();
  const request = transport.request("/api/status", {method: "GET"});
  await new Promise(resolve => setImmediate(resolve));

  assert.equal(socket.sent.length, 1);
  const envelope = JSON.parse(socket.sent[0]);
  transport.pending.get(envelope.requestId).resolve(new Response(null, {status: 204}));
  assert.equal((await request).status, 204);
  assert.equal(transport.pending.size, 0);
});

test("production transport contains both post-connect and pre-send abort guards", () => {
  const source = extractCloudRelayTransportSource();
  const request = source.slice(source.indexOf("async request"), source.indexOf("async connect"));
  const connectEnd = request.indexOf("await this.connect(signal);");
  const listener = request.indexOf("signal.addEventListener('abort'");
  const send = request.indexOf("this.socket.send(envelope)");
  assert.match(request.slice(connectEnd, listener), /signal && signal\.aborted/u);
  assert.match(request.slice(listener, send), /signal && signal\.aborted/u);
});
