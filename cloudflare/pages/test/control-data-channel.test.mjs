import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import path from "node:path";
import test from "node:test";
import vm from "node:vm";

const cloudflareRoot = path.resolve(
  path.dirname(fileURLToPath(import.meta.url)),
  "..",
  "..",
);
const appScript = readFileSync(
  path.resolve(cloudflareRoot, "..", "app", "src", "main", "assets", "tesla", "app.js"),
  "utf8",
);

function loadDirectControlPolicy() {
  const source = appScript.match(
    /function isDirectWebRtcControlRequest\(path, options\) \{[\s\S]*?\n  \}/u,
  )?.[0];
  assert.ok(source, "direct WebRTC control routing policy must remain testable");
  return vm.runInNewContext(`(${source})`);
}

function loadCloudSignalingPolicy() {
  const source = appScript.match(
    /function isCloudRelaySignalingRequest\(path, options\) \{[\s\S]*?\n  \}/u,
  )?.[0];
  assert.ok(source, "cloud signaling allowlist must remain testable");
  return vm.runInNewContext(`(${source})`);
}

function extractAppSource(startMarker, endMarker) {
  const start = appScript.indexOf(startMarker);
  assert.notEqual(start, -1, `missing app source marker: ${startMarker}`);
  const end = appScript.indexOf(endMarker, start);
  assert.notEqual(end, -1, `missing app source marker: ${endMarker}`);
  return appScript.slice(start, end);
}

function loadCloudRelayReconnectDelayPolicy() {
  const source = appScript.match(
    /function cloudRelayReconnectDelayMillis\(attempt, randomSample = Math\.random\(\)\) \{[\s\S]*?\n  \}/u,
  )?.[0];
  assert.ok(source, "cloud relay reconnect jitter policy must remain testable");
  return vm.runInNewContext(`(${source})`);
}

function createControlChannel({ bufferedAmount = 0, readyState = "open", send } = {}) {
  const listeners = new Map();
  return {
    bufferedAmount,
    readyState,
    sent: [],
    addEventListener(type, listener) {
      const entries = listeners.get(type) ?? [];
      entries.push(listener);
      listeners.set(type, entries);
    },
    emit(type, data) {
      if (type === "open") this.readyState = "open";
      if (type === "close") this.readyState = "closed";
      for (const listener of listeners.get(type) ?? []) listener({ data });
    },
    send(envelope) {
      this.sent.push(envelope);
      if (send) send(envelope);
    },
    close() {
      this.readyState = "closed";
      this.emit("close");
    },
  };
}

function loadControlRuntime({
  maxBufferedAmount = 4096,
  maxInFlight = 2,
  openTimeoutMillis = 25,
  requestTimeoutMillis = 25,
  starting = false,
} = {}) {
  const source = extractAppSource(
    "function cancelControlWebRtcOpenWatchdog",
    "\n  function updateMicrophoneState",
  );
  const cloudRequests = [];
  const context = vm.createContext({
    AbortController,
    ArrayBuffer,
    DOMException,
    Headers,
    Response,
    TextEncoder,
    Uint8Array,
    atob,
    btoa,
    crypto,
    window: { clearTimeout, setTimeout },
    pad: { dataset: {} },
    CLOUD_RELAY_CONFIG: Object.freeze({}),
    CLOUD_RELAY_MAX_BODY_BYTES: 128 * 1024,
    CLOUD_RELAY_MAX_RESPONSE_BYTES: 160 * 1024,
    CLOUD_RELAY_MODE: true,
    CLOUD_RELAY_REQUEST_ID_PATTERN: /^[A-Za-z0-9_-]{16,64}$/u,
    CONTROL_WEBRTC_MAX_IN_FLIGHT_REQUESTS: maxInFlight,
    CONTROL_WEBRTC_MAX_MESSAGE_BYTES: maxBufferedAmount,
    CONTROL_WEBRTC_OPEN_TIMEOUT_MILLIS: openTimeoutMillis,
    CONTROL_WEBRTC_REQUEST_TIMEOUT_MILLIS: requestTimeoutMillis,
    VIEWPORT_CLIENT_ID: "viewport-client",
    cloudRequest(pathname, options) {
      cloudRequests.push({ pathname, options });
      return Promise.resolve({ pathname, transport: "cloud" });
    },
  });

  vm.runInContext(`
    let browserCredential = "browser-credential";
    let cloudRelayTransport = null;
    let webRtcControlTransport = null;
    let webRtcControlOpenTimer = 0;
    let webRtcControlNegotiatingGeneration = 0;
    let webRtcGeneration = 1;
    let webRtcPeer = null;
    let webRtcStarting = ${starting};
    let localControlCutover = false;
    let streamStateKey = "";
    let failWebRtcCalls = 0;

    function setStreamState(key) { streamStateKey = key; }
    function cancelNoticeRequestForRetry() {}
    function ensureNoticesLoaded() {}
    function failWebRtc() {
      failWebRtcCalls += 1;
      webRtcGeneration += 1;
      webRtcStarting = false;
      webRtcPeer = null;
      cancelControlWebRtcOpenWatchdog();
      webRtcControlTransport = null;
    }

    class CloudRelayTransport {
      request(pathname, options) {
        return cloudRequest(pathname, options);
      }
    }

    function base64NoWrap(bytes) {
      let binary = "";
      for (const byte of bytes) binary += String.fromCharCode(byte);
      return btoa(binary);
    }

    ${source}

    globalThis.createDirectTransport = channel => {
      const transport = new WebRtcControlTransport(channel, webRtcGeneration);
      webRtcControlTransport = transport;
      beginControlWebRtcChannelNegotiation(transport);
      if (channel.readyState === "open") channel.emit("open");
      return transport;
    };
    globalThis.invokeApi = (pathname, options, credential) =>
      api(pathname, options, credential);
    globalThis.normalizeRelayHeaders = value => relayHeaders(value);
    globalThis.failureCount = () => failWebRtcCalls;
    globalThis.setPeer = value => { webRtcPeer = value; };
    globalThis.setStarting = value => { webRtcStarting = value; };
    globalThis.markPeerConnected = () => {
      if (webRtcPeer) webRtcPeer.connectionState = "connected";
      armControlWebRtcOpenWatchdog(webRtcGeneration);
      webRtcStarting = false;
    };
    globalThis.setLocalControlCutover = value => { localControlCutover = value; };
    globalThis.advanceGeneration = () => { webRtcGeneration += 1; };
  `, context);

  return {
    cloudRequests,
    createDirectTransport: context.createDirectTransport,
    invokeApi: context.invokeApi,
    normalizeRelayHeaders: context.normalizeRelayHeaders,
    failureCount: context.failureCount,
    setPeer: context.setPeer,
    setStarting: context.setStarting,
    markPeerConnected: context.markPeerConnected,
    setLocalControlCutover: context.setLocalControlCutover,
    advanceGeneration: context.advanceGeneration,
  };
}

test("WebRTC control channel is ordered and reliable by default", () => {
  assert.match(
    appScript,
    /createDataChannel\(CONTROL_WEBRTC_CHANNEL_LABEL, \{ordered: true\}\)/u,
  );
  const setup = appScript.match(
    /function createControlWebRtcChannel[\s\S]*?function disposeControlWebRtcChannel/u,
  )?.[0] ?? "";
  assert.doesNotMatch(setup, /maxRetransmits|maxPacketLifeTime/u);
  assert.match(appScript, /capabilities\.controlDataChannelV1/u);
  assert.match(appScript, /cloudRelayTransport\.request\(path, requestOptions\)/u);
  assert.match(appScript, /const CONTROL_WEBRTC_OPEN_TIMEOUT_MILLIS = AUDIO_WEBRTC_OPEN_TIMEOUT_MILLIS;/u);
  assert.match(setup, /beginControlWebRtcChannelNegotiation\(transport\)/u);
  assert.doesNotMatch(setup, /armControlWebRtcOpenWatchdog\(transport\)/u);
  assert.match(
    appScript,
    /cancelWebRtcRecovery\(true\);\s+armControlWebRtcOpenWatchdog\(generation\);/u,
  );
});

test("cloud relay reconnect uses bounded equal jitter", () => {
  const delay = loadCloudRelayReconnectDelayPolicy();

  assert.equal(delay(0, 0), 500);
  assert.equal(delay(0, 1), 1000);
  assert.equal(delay(1, 0), 1000);
  assert.equal(delay(1, 1), 2000);
  assert.equal(delay(4, 0), 7500);
  assert.equal(delay(4, 1), 15000);
  assert.equal(delay(100, 0.5), 11250);
  assert.equal(delay(-1, Number.NaN), 500);
  assert.match(
    appScript,
    /const delayMillis = cloudRelayReconnectDelayMillis\(this\.reconnectAttempt\)/u,
  );
});

test("direct control routes only post-connect non-signaling APIs", () => {
  const isDirect = loadDirectControlPolicy();
  for (const target of [
    "/api/status",
    "/api/notices?locale=ko-KR",
    "/api/projection/profile",
    "/api/projection/viewport",
  ]) {
    assert.equal(isDirect(target, { method: "GET" }), true, target);
  }
  for (const target of ["/api/projection/viewport", "/api/touch"]) {
    assert.equal(isDirect(target, { method: "POST" }), true, target);
  }
  for (const [target, method] of [
    ["/health", "GET"],
    ["/api/pair", "POST"],
    ["/api/webrtc/capabilities", "GET"],
    ["/api/webrtc/session", "POST"],
    ["/api/audio/media", "GET"],
    ["/api/microphone", "POST"],
    ["/api/touch", "GET"],
    ["/api/projection/profile", "POST"],
  ]) {
    assert.equal(isDirect(target, { method }), false, `${method} ${target}`);
  }
});

test("cloud RPC allowlist contains signaling and pre-connect geometry only", () => {
  const isAllowed = loadCloudSignalingPolicy();
  for (const [target, method] of [
    ["/health", "GET"],
    ["/api/pair", "POST"],
    ["/api/status", "GET"],
    ["/api/projection/profile", "GET"],
    ["/api/projection/viewport", "GET"],
    ["/api/projection/viewport?width=1080&height=1920", "POST"],
    ["/api/webrtc/capabilities", "GET"],
    ["/api/webrtc/session?codec=auto", "POST"],
    ["/api/webrtc/session/abcdefghijklmnop", "GET"],
    ["/api/webrtc/session/abcdefghijklmnop", "DELETE"],
  ]) {
    assert.equal(isAllowed(target, { method }), true, `${method} ${target}`);
  }
  for (const [target, method] of [
    ["/api/touch", "POST"],
    ["/api/notices", "GET"],
    ["/api/microphone", "POST"],
    ["/api/audio/media", "GET"],
    ["/api/frame.jpg", "GET"],
    ["/api/projection/profile", "POST"],
  ]) {
    assert.equal(isAllowed(target, { method }), false, `${method} ${target}`);
  }
});

test("direct control preserves browser credential and removes untrusted authorization", () => {
  const runtime = loadControlRuntime();
  const browserCredential = "a".repeat(43);
  const headers = runtime.normalizeRelayHeaders({
    "X-Browser-Credential": browserCredential,
    "X-Viewport-Client-Id": "0123456789abcdef",
    Authorization: "Bearer must-not-cross",
  });

  assert.equal(headers["x-browser-credential"], browserCredential);
  assert.equal(headers["x-viewport-client-id"], "0123456789abcdef");
  assert.equal(headers.authorization, undefined);
});

test("pre-send direct saturation and congestion fail closed without cloud relay", async (t) => {
  await t.test("local in-flight saturation", async () => {
    const runtime = loadControlRuntime({ maxInFlight: 2 });
    const channel = createControlChannel();
    const transport = runtime.createDirectTransport(channel);
    transport.pending.set("first-request", {});
    transport.pending.set("second-request", {});

    await assert.rejects(
      runtime.invokeApi("/api/touch", { method: "POST", body: "touch" }, "credential"),
      /Local WebRTC control channel unavailable/u,
    );

    assert.equal(channel.sent.length, 0);
    assert.equal(runtime.cloudRequests.length, 0);
  });

  await t.test("bufferedAmount congestion", async () => {
    const runtime = loadControlRuntime({ maxBufferedAmount: 4096 });
    const channel = createControlChannel({ bufferedAmount: 4097 });
    runtime.createDirectTransport(channel);

    await assert.rejects(
      runtime.invokeApi("/api/touch", { method: "POST", body: "touch" }, "credential"),
      /Local WebRTC control channel unavailable/u,
    );

    assert.equal(channel.sent.length, 0);
    assert.equal(runtime.cloudRequests.length, 0);
  });
});

test("touch without an open direct channel is rejected locally", async () => {
  const runtime = loadControlRuntime();

  await assert.rejects(
    runtime.invokeApi("/api/touch", { method: "POST", body: "touch" }, "credential"),
    /Local WebRTC control channel unavailable/u,
  );
  assert.equal(runtime.cloudRequests.length, 0);
});

test("connected peer preserves local-only requests until its delayed control channel opens", async () => {
  const runtime = loadControlRuntime({ openTimeoutMillis: 30, starting: true });
  const channel = createControlChannel({ readyState: "connecting" });
  runtime.setPeer({ connectionState: "connecting" });
  runtime.setLocalControlCutover(true);
  runtime.createDirectTransport(channel);
  runtime.markPeerConnected();

  await assert.rejects(
    runtime.invokeApi(
      "/api/projection/viewport?width=1920&height=1080",
      { method: "POST" },
      "credential",
    ),
    /Local WebRTC control channel unavailable/u,
  );
  await new Promise(resolve => setTimeout(resolve, 0));

  assert.equal(runtime.failureCount(), 0);
  assert.equal(runtime.cloudRequests.length, 0);
  channel.emit("open");
  await new Promise(resolve => setTimeout(resolve, 35));
  assert.equal(runtime.failureCount(), 0);
});

test("control channel open watchdog starts after peer connection and fails exactly once", async () => {
  const runtime = loadControlRuntime({ openTimeoutMillis: 5, starting: true });
  const channel = createControlChannel({ readyState: "connecting" });
  runtime.setPeer({ connectionState: "connecting" });
  runtime.setLocalControlCutover(true);
  runtime.createDirectTransport(channel);
  runtime.markPeerConnected();

  await new Promise(resolve => setTimeout(resolve, 15));
  assert.equal(runtime.failureCount(), 1);
  assert.equal(runtime.cloudRequests.length, 0);

  channel.emit("open");
  await new Promise(resolve => setTimeout(resolve, 0));
  assert.equal(runtime.failureCount(), 1);
});

test("slow pre-connect signaling does not consume the control channel open budget", async () => {
  const runtime = loadControlRuntime({ openTimeoutMillis: 5, starting: true });
  const channel = createControlChannel({ readyState: "connecting" });
  runtime.setPeer({ connectionState: "connecting" });
  runtime.setLocalControlCutover(true);
  runtime.createDirectTransport(channel);

  // This represents ICE gathering and answer exchange taking longer than the post-connect budget.
  await new Promise(resolve => setTimeout(resolve, 15));
  assert.equal(runtime.failureCount(), 0);

  runtime.markPeerConnected();
  channel.emit("open");
  await new Promise(resolve => setTimeout(resolve, 10));
  assert.equal(runtime.failureCount(), 0);
});

test("stale generation control watchdog cannot fail the replacement peer", async () => {
  const runtime = loadControlRuntime({ openTimeoutMillis: 5, starting: true });
  const channel = createControlChannel({ readyState: "connecting" });
  runtime.setPeer({ connectionState: "connecting" });
  runtime.createDirectTransport(channel);
  runtime.markPeerConnected();
  runtime.advanceGeneration();

  await new Promise(resolve => setTimeout(resolve, 15));
  assert.equal(runtime.failureCount(), 0);
});

test("opened control channel close remains an immediate fail-closed error", async () => {
  const runtime = loadControlRuntime({ openTimeoutMillis: 30, starting: true });
  const channel = createControlChannel({ readyState: "connecting" });
  runtime.setPeer({ connectionState: "connecting" });
  runtime.createDirectTransport(channel);
  runtime.markPeerConnected();
  channel.emit("open");
  channel.emit("close");

  await new Promise(resolve => setTimeout(resolve, 0));
  assert.equal(runtime.failureCount(), 1);
  assert.equal(runtime.cloudRequests.length, 0);
});

test("missing direct channel after an established cutover still restarts WebRTC", async () => {
  const runtime = loadControlRuntime();
  runtime.setPeer({ connectionState: "connected" });
  runtime.setLocalControlCutover(true);

  await assert.rejects(
    runtime.invokeApi("/api/touch", { method: "POST", body: "touch" }, "credential"),
    /Local WebRTC control channel unavailable/u,
  );
  await new Promise(resolve => setTimeout(resolve, 0));

  assert.equal(runtime.failureCount(), 1);
  assert.equal(runtime.cloudRequests.length, 0);
});

test("post-cutover recovery keeps control local while allowing read-only connection metadata", async () => {
  const runtime = loadControlRuntime();
  const channel = createControlChannel();
  runtime.createDirectTransport(channel);
  channel.emit("open");
  channel.emit("error");

  await assert.rejects(
    runtime.invokeApi(
      "/api/projection/viewport?width=1080&height=1920",
      { method: "POST" },
      "credential",
    ),
    /Local WebRTC control channel unavailable/u,
  );
  const status = await runtime.invokeApi("/api/status", { method: "GET" }, "credential");

  assert.equal(status.transport, "cloud");
  assert.equal(runtime.cloudRequests.length, 1);
  assert.equal(runtime.cloudRequests[0].pathname, "/api/status");
});

test("touch is never retried through cloud relay after direct send is attempted", async (t) => {
  await t.test("synchronous send failure", async () => {
    const runtime = loadControlRuntime();
    const channel = createControlChannel({
      send() {
        throw new Error("direct send failed");
      },
    });
    runtime.createDirectTransport(channel);

    await assert.rejects(
      runtime.invokeApi("/api/touch", { method: "POST", body: "touch" }, "credential"),
      /direct send failed/u,
    );
    assert.equal(channel.sent.length, 1);
    assert.equal(runtime.cloudRequests.length, 0);
  });

  await t.test("channel error", async () => {
    const runtime = loadControlRuntime();
    const channel = createControlChannel();
    runtime.createDirectTransport(channel);

    const request = runtime.invokeApi(
      "/api/touch",
      { method: "POST", body: "touch" },
      "credential",
    );
    assert.equal(channel.sent.length, 1);
    channel.emit("error");

    await assert.rejects(request, /WebRTC control channel failed/u);
    assert.equal(runtime.cloudRequests.length, 0);
  });

  await t.test("abort", async () => {
    const runtime = loadControlRuntime();
    const channel = createControlChannel();
    const controller = new AbortController();
    runtime.createDirectTransport(channel);

    const request = runtime.invokeApi(
      "/api/touch",
      { method: "POST", body: "touch", signal: controller.signal },
      "credential",
    );
    assert.equal(channel.sent.length, 1);
    controller.abort();

    await assert.rejects(request, { name: "AbortError" });
    assert.equal(runtime.cloudRequests.length, 0);
  });

  await t.test("timeout", async () => {
    const runtime = loadControlRuntime({ requestTimeoutMillis: 5 });
    const channel = createControlChannel();
    runtime.createDirectTransport(channel);

    await assert.rejects(
      runtime.invokeApi("/api/touch", { method: "POST", body: "touch" }, "credential"),
      /WebRTC control request timed out/u,
    );
    assert.equal(channel.sent.length, 1);
    assert.equal(runtime.cloudRequests.length, 0);
  });
});
