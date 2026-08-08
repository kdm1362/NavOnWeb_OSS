import assert from "node:assert/strict";
import test from "node:test";

import worker, { PairingBootstrap, SignalRoom, readBoundedPairingCode } from "../src/index.mjs";

import {
  MAX_MESSAGE_BYTES,
  PROTOCOL_VERSION,
  ROUTE_COOKIE_NAME,
  authorizeDeviceRoom,
  consumeTokenBucket,
  createRouteCookieValue,
  deriveBootstrapObjectName,
  deriveRoomIdFromDeviceSecret,
  isAllowedBrowserOrigin,
  isAllowedCloudRelayRequest,
  isValidBootstrapSecret,
  isValidPairingCode,
  isValidRequestId,
  normalizeClientNetwork,
  parseAllowedOrigins,
  readCookie,
  utf8ByteLength,
  validateClientMessage,
  verifyRouteCookieValue,
} from "../src/protocol.mjs";

const ZERO_SECRET = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";
const OTHER_SECRET = "BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB";
const ZERO_SECRET_ROOM = "DwBzhbb51LfusnSGBa_hqY";
const ROUTE_NONCE_A = "CCCCCCCCCCCCCCCCCCCCCC";
const ROUTE_NONCE_B = "DDDDDDDDDDDDDDDDDDDDDD";
const PAIRING_GENERATION_A = "E".repeat(43);
const PAIRING_GENERATION_B = "F".repeat(43);
const PAIRING_GENERATION_C = "G".repeat(43);
const PAIRING_EPOCH_A = 1;
const PAIRING_EPOCH_B = 2;
const PAIRING_EPOCH_C = 3;

test("matches Android ASCII-secret SHA-256 base64url room derivation", async () => {
  assert.equal(await deriveRoomIdFromDeviceSecret(ZERO_SECRET), ZERO_SECRET_ROOM);
});

test("device bearer authorization is bound to the 22-character room id", async () => {
  assert.deepEqual(
    await authorizeDeviceRoom(ZERO_SECRET_ROOM, `Bearer ${ZERO_SECRET}`),
    { ok: true },
  );
  assert.equal(
    (await authorizeDeviceRoom("AAAAAAAAAAAAAAAAAAAAAA", `Bearer ${ZERO_SECRET}`)).status,
    403,
  );
  assert.equal((await authorizeDeviceRoom(ZERO_SECRET_ROOM, null)).status, 401);
});

test("origin allowlist accepts exact HTTPS origins and loopback HTTP only", () => {
  const configured = "https://app.example.com,http://localhost:8788,https://bad.example/path,*";
  assert.deepEqual(
    [...parseAllowedOrigins(configured)],
    ["https://app.example.com", "http://localhost:8788"],
  );
  assert.equal(isAllowedBrowserOrigin("https://app.example.com", configured), true);
  assert.equal(isAllowedBrowserOrigin("https://sub.app.example.com", configured), false);
  assert.equal(isAllowedBrowserOrigin("http://app.example.com", configured), false);
  assert.equal(isAllowedBrowserOrigin("null", configured), false);
});

test("requestId matches Android 16-64 character base64url contract", () => {
  assert.equal(isValidRequestId("abcdefghijklmnop"), true);
  assert.equal(isValidRequestId("b54c8e75-6769-4b80-a882-3cc39ef4b595"), true);
  assert.equal(isValidRequestId("short"), false);
  assert.equal(isValidRequestId("rpc:123456789012"), false);
  assert.equal(isValidRequestId("a".repeat(65)), false);
});

test("cloud relay allows signaling metadata but rejects application payloads", () => {
  for (const [method, target] of [
    ["GET", "/health"],
    ["POST", "/api/pair"],
    ["GET", "/api/status"],
    ["GET", "/api/projection/profile"],
    ["GET", "/api/projection/viewport"],
    ["POST", "/api/projection/viewport?width=1080&height=1920"],
    ["GET", "/api/webrtc/capabilities"],
    ["POST", "/api/webrtc/session?codec=auto"],
    ["GET", "/api/webrtc/session/abcdefghijklmnop"],
    ["DELETE", "/api/webrtc/session/abcdefghijklmnop"],
  ]) {
    assert.equal(isAllowedCloudRelayRequest(method, target), true, `${method} ${target}`);
  }
  for (const [method, target] of [
    ["POST", "/api/touch"],
    ["GET", "/api/notices"],
    ["GET", "/api/frame.jpg"],
    ["GET", "/api/audio/media"],
    ["GET", "/api/audio/speech"],
    ["GET", "/api/audio/system"],
    ["POST", "/api/microphone"],
    ["POST", "/api/projection/profile"],
    ["GET", "https://attacker.example/api/status"],
  ]) {
    assert.equal(isAllowedCloudRelayRequest(method, target), false, `${method} ${target}`);
  }
});

test("worker rejects touch RPC before it reaches the phone", () => {
  const result = validateClientMessage(JSON.stringify({
    type: "rpc_request",
    requestId: "abcdefghijklmnop",
    method: "POST",
    target: "/api/touch?phase=down&x=0.5&y=0.5",
    headers: {},
    bodyBase64: "",
  }), "browser");
  assert.equal(result.ok, false);
  assert.equal(result.closeCode, 1008);
  assert.match(result.reason, /restricted to connection signaling/u);
});

test("pairing bootstrap validates eight digits and a 32-byte HMAC secret", () => {
  assert.equal(isValidPairingCode("01234567"), true);
  assert.equal(isValidPairingCode("012345"), false);
  assert.equal(isValidPairingCode("1234567"), false);
  assert.equal(isValidPairingCode("1234567a"), false);
  assert.equal(isValidBootstrapSecret("x".repeat(32)), true);
  assert.equal(isValidBootstrapSecret("한".repeat(11)), true);
  assert.equal(isValidBootstrapSecret("x".repeat(31)), false);
});

test("client egress address normalization uses IPv4 exact and IPv6 slash 64", () => {
  assert.equal(normalizeClientNetwork("192.0.2.9"), "v4:192.0.2.9");
  assert.equal(normalizeClientNetwork("2001:db8:abcd:12::9"), "v6:2001:0db8:abcd:0012::/64");
  assert.equal(
    normalizeClientNetwork("2001:0db8:abcd:0012:ffff::1"),
    "v6:2001:0db8:abcd:0012::/64",
  );
  assert.equal(normalizeClientNetwork("::ffff:192.0.2.9"), "v4:192.0.2.9");
  assert.equal(normalizeClientNetwork("2001:db8::1::2"), null);
  assert.equal(normalizeClientNetwork("not-an-ip"), null);
});

test("pairing slot object names are keyed, opaque, and network scoped", async () => {
  const secret = "test-bootstrap-secret-32-bytes-minimum";
  const first = await deriveBootstrapObjectName(secret, "slot", "v4:192.0.2.9", "12345678");
  const second = await deriveBootstrapObjectName(secret, "slot", "v4:192.0.2.10", "12345678");
  const third = await deriveBootstrapObjectName(secret, "slot", "v4:192.0.2.9", "65432109");
  assert.match(first, /^[A-Za-z0-9_-]{43}$/u);
  assert.notEqual(first, second);
  assert.notEqual(first, third);
  assert.equal(first.includes("12345678"), false);
  assert.equal(first.includes("192.0.2.9"), false);
});

test("v2 signed route cookie binds its room and revocable route nonce", async () => {
  const secret = "test-bootstrap-secret-32-bytes-minimum";
  const now = 1_800_000_000_000;
  const value = await createRouteCookieValue(secret, ZERO_SECRET_ROOM, ROUTE_NONCE_A, now, 60);
  assert.deepEqual(await verifyRouteCookieValue(secret, value, now + 30_000), {
    roomId: ZERO_SECRET_ROOM,
    routeNonce: ROUTE_NONCE_A,
    expiresAtSeconds: 1_800_000_060,
  });
  assert.equal(await verifyRouteCookieValue(secret, value, now + 60_000), null);
  assert.equal(await verifyRouteCookieValue(secret, `${value.slice(0, -1)}A`, now), null);
  assert.equal(
    await verifyRouteCookieValue(
      secret,
      `v1.${ZERO_SECRET_ROOM}.1800000060.${"A".repeat(43)}`,
      now,
    ),
    null,
  );
  assert.equal(
    readCookie(`theme=dark; ${ROUTE_COOKIE_NAME}=${value}; other=value`, ROUTE_COOKIE_NAME),
    value,
  );
});

test("accepts the browser flat rpc_request envelope without rewriting its fields", () => {
  const envelope = {
    type: "rpc_request",
    requestId: "abcdefghijklmnop",
    method: "POST",
    target: "/api/webrtc/session?codec=auto",
    headers: { "content-type": "application/sdp" },
    bodyBase64: "b3BhcXVl",
  };
  const raw = JSON.stringify(envelope);
  const result = validateClientMessage(raw, "browser");
  assert.equal(result.ok, true);
  assert.deepEqual(result.envelope, envelope);
});

test("accepts the Android flat rpc_response envelope", () => {
  const envelope = {
    type: "rpc_response",
    requestId: "abcdefghijklmnop",
    status: 200,
    contentType: "application/json; charset=utf-8",
    bodyBase64: "e30=",
  };
  assert.equal(validateClientMessage(JSON.stringify(envelope), "device").ok, true);
});

test("rejects wrong direction, nested payload RPC, missing flat fields, and extras", () => {
  const validRequest = {
    type: "rpc_request",
    requestId: "abcdefghijklmnop",
    method: "GET",
    target: "/api/status",
    headers: {},
    bodyBase64: "",
  };
  assert.equal(validateClientMessage(JSON.stringify(validRequest), "device").closeCode, 1008);
  assert.equal(validateClientMessage(JSON.stringify({
    type: "rpc_request",
    requestId: "abcdefghijklmnop",
    payload: validRequest,
  }), "browser").closeCode, 1008);
  assert.equal(validateClientMessage(JSON.stringify({
    ...validRequest,
    bodyBase64: undefined,
  }), "browser").closeCode, 1008);
  assert.equal(validateClientMessage(JSON.stringify({
    ...validRequest,
    unexpected: true,
  }), "browser").closeCode, 1008);
});

test("common control messages are valid in both directions", () => {
  for (const role of ["browser", "device"]) {
    for (const type of ["ping", "pong", "bye"]) {
      assert.equal(validateClientMessage(JSON.stringify({
        type,
        requestId: "abcdefghijklmnop",
        payload: { source: role },
      }), role).ok, true);
    }
  }
});

test("token bucket enforces a burst and refills at the configured rate", () => {
  let state = { tokens: 2, updatedAt: 1000 };
  state = consumeTokenBucket(state, 1000, 2, 2);
  assert.equal(state.allowed, true);
  assert.equal(state.tokens, 1);
  state = consumeTokenBucket(state, 1000, 2, 2);
  assert.equal(state.allowed, true);
  assert.equal(state.tokens, 0);
  state = consumeTokenBucket(state, 1000, 2, 2);
  assert.equal(state.allowed, false);
  state = consumeTokenBucket(state, 1500, 2, 2);
  assert.equal(state.allowed, true);
  assert.equal(state.tokens, 0);
});

test("binary, invalid JSON, and messages over 192 KiB receive specific close codes", () => {
  assert.equal(validateClientMessage(new Uint8Array([1]).buffer, "browser").closeCode, 1003);
  assert.equal(validateClientMessage("{", "browser").closeCode, 1007);

  const oversized = JSON.stringify({
    type: "rpc_request",
    requestId: "abcdefghijklmnop",
    method: "POST",
    target: "/api/test",
    headers: {},
    bodyBase64: "x".repeat(MAX_MESSAGE_BYTES),
  });
  assert.ok(utf8ByteLength(oversized) > MAX_MESSAGE_BYTES);
  assert.equal(validateClientMessage(oversized, "browser").closeCode, 1009);
});

test("bare-url bootstrap registers and consumes a network-bound code once", async () => {
  const bootstrapBinding = new InMemoryBootstrapBinding();
  const signalRooms = new CapturingSignalBinding();
  const env = {
    ALLOWED_BROWSER_ORIGINS: "https://navonweb.com",
    BOOTSTRAP_HMAC_KEY: "test-bootstrap-secret-32-bytes-minimum",
    PAIRING_BOOTSTRAP: bootstrapBinding,
    SIGNAL_ROOMS: signalRooms,
  };
  const networkHeaders = { "CF-Connecting-IP": "2001:db8:abcd:12::9" };

  const registration = await worker.fetch(new Request("https://signal.navonweb.com/bootstrap/device", {
    method: "POST",
    headers: {
      ...networkHeaders,
      Authorization: `Bearer ${ZERO_SECRET}`,
      "X-NavOnWeb-Pairing-Epoch": String(PAIRING_EPOCH_A),
      "X-NavOnWeb-Pairing-Ttl-Millis": "600000",
      "Content-Type": "text/plain; charset=utf-8",
    },
    body: "12345678",
  }), env);
  assert.equal(registration.status, 204);

  const collision = await worker.fetch(new Request("https://signal.navonweb.com/bootstrap/device", {
    method: "POST",
    headers: {
      ...networkHeaders,
      Authorization: `Bearer ${OTHER_SECRET}`,
      "X-NavOnWeb-Pairing-Epoch": String(PAIRING_EPOCH_A),
      "X-NavOnWeb-Pairing-Ttl-Millis": "600000",
      "Content-Type": "text/plain; charset=utf-8",
    },
    body: "12345678",
  }), env);
  assert.equal(collision.status, 409);

  const exchange = await worker.fetch(new Request("https://navonweb.com/_nw/bootstrap/pair", {
    method: "POST",
    headers: {
      ...networkHeaders,
      Origin: "https://navonweb.com",
      "Content-Type": "text/plain; charset=utf-8",
    },
    body: "12345678",
  }), env);
  assert.equal(exchange.status, 204);
  assert.equal(exchange.headers.get("access-control-allow-origin"), "https://navonweb.com");
  assert.equal(exchange.headers.get("access-control-allow-credentials"), "true");
  const setCookies = exchange.headers.getSetCookie();
  const clientCookie = setCookies.find((value) => value.startsWith("__Host-navonweb_client="));
  const routeCookie = setCookies.find((value) => value.startsWith(`${ROUTE_COOKIE_NAME}=`));
  assert.match(clientCookie, /; HttpOnly; Secure; SameSite=Strict$/u);
  assert.match(routeCookie, /; HttpOnly; Secure; SameSite=Strict$/u);

  const consumedAgain = await worker.fetch(new Request("https://navonweb.com/_nw/bootstrap/pair", {
    method: "POST",
    headers: {
      ...networkHeaders,
      Origin: "https://navonweb.com",
      "Content-Type": "text/plain; charset=utf-8",
    },
    body: "12345678",
  }), env);
  assert.equal(consumedAgain.status, 404);

  // A consumed code remains tombstoned until its original expiry. Device
  // reconnect or response-loss retries cannot make it live again.
  assert.equal((await deviceRegistration(
    env,
    "2001:db8:abcd:12::9",
    ZERO_SECRET,
    "12345678",
    PAIRING_EPOCH_A,
  )).status, 409);

  const cookie = routeCookie.split(";", 1)[0];
  const routed = await worker.fetch(new Request("https://navonweb.com/_nw/ws/browser", {
    headers: {
      ...networkHeaders,
      Cookie: cookie,
      Origin: "https://navonweb.com",
      Upgrade: "websocket",
    },
  }), env);
  assert.equal(routed.status, 200);
  assert.equal(signalRooms.lastRoomId, ZERO_SECRET_ROOM);
  assert.equal(signalRooms.lastRole, "browser");
});

test("bootstrap lookup is unavailable from a different egress network", async () => {
  const env = {
    ALLOWED_BROWSER_ORIGINS: "https://navonweb.com",
    BOOTSTRAP_HMAC_KEY: "test-bootstrap-secret-32-bytes-minimum",
    PAIRING_BOOTSTRAP: new InMemoryBootstrapBinding(),
    SIGNAL_ROOMS: new CapturingSignalBinding(),
  };
  await worker.fetch(new Request("https://signal.navonweb.com/bootstrap/device", {
    method: "POST",
    headers: {
      "CF-Connecting-IP": "192.0.2.10",
      Authorization: `Bearer ${ZERO_SECRET}`,
      "X-NavOnWeb-Pairing-Epoch": String(PAIRING_EPOCH_A),
      "X-NavOnWeb-Pairing-Ttl-Millis": "600000",
      "Content-Type": "text/plain",
    },
    body: "65432109",
  }), env);
  const response = await worker.fetch(new Request("https://signal.navonweb.com/bootstrap/pair", {
    method: "POST",
    headers: {
      "CF-Connecting-IP": "192.0.2.11",
      Origin: "https://navonweb.com",
      "Content-Type": "text/plain",
    },
    body: "65432109",
  }), env);
  assert.equal(response.status, 404);
  assert.deepEqual(await response.json(), { error: "Pairing unavailable" });
});

test("device pairing publication epoch must be a positive safe integer", async () => {
  const env = bootstrapEnvironment();
  for (const epoch of [undefined, "0", "-1", "9007199254740992", "not-a-number"]) {
    const headers = {
      "CF-Connecting-IP": "192.0.2.12",
      Authorization: `Bearer ${ZERO_SECRET}`,
      "Content-Type": "text/plain",
      "X-NavOnWeb-Pairing-Ttl-Millis": "600000",
    };
    if (epoch !== undefined) headers["X-NavOnWeb-Pairing-Epoch"] = epoch;
    const response = await worker.fetch(new Request(
      "https://signal.navonweb.com/bootstrap/device",
      { method: "POST", headers, body: "65432109" },
    ), env);
    assert.equal(response.status, 400);
  }
});

test("pairing protocol v2 requires a strictly bounded remaining TTL", async () => {
  assert.equal(PROTOCOL_VERSION, 2);
  const env = bootstrapEnvironment();
  for (const ttl of [undefined, "0", "-1", "600001", "not-a-number"]) {
    const headers = {
      "CF-Connecting-IP": "192.0.2.13",
      Authorization: `Bearer ${ZERO_SECRET}`,
      "X-NavOnWeb-Pairing-Epoch": String(PAIRING_EPOCH_A),
      "Content-Type": "text/plain",
    };
    if (ttl !== undefined) headers["X-NavOnWeb-Pairing-Ttl-Millis"] = ttl;
    const response = await worker.fetch(new Request(
      "https://signal.navonweb.com/bootstrap/device",
      { method: "POST", headers, body: "65432109" },
    ), env);
    assert.equal(response.status, 400);
  }
});

test("delayed first registration uses remaining TTL and retries never extend it", async () => {
  const env = bootstrapEnvironment();
  const network = "192.0.2.14";
  const before = Date.now();
  assert.equal((await deviceRegistration(
    env,
    network,
    ZERO_SECRET,
    "56565656",
    PAIRING_EPOCH_A,
    1_000,
  )).status, 204);
  const slotName = await deriveBootstrapObjectName(
    env.BOOTSTRAP_HMAC_KEY,
    "slot",
    normalizeClientNetwork(network),
    "56565656",
  );
  const firstSlot = await env.PAIRING_BOOTSTRAP.storedSlot(slotName);
  const after = Date.now();
  assert.ok(firstSlot.expiresAt >= before + 1_000);
  assert.ok(firstSlot.expiresAt <= after + 1_000);

  // Even a compromised retry claiming a fresh ten minutes receives the
  // canonical first-reserve expiry and cannot extend SignalRoom state.
  assert.equal((await deviceRegistration(
    env,
    network,
    ZERO_SECRET,
    "56565656",
    PAIRING_EPOCH_A,
    600_000,
  )).status, 204);
  assert.equal((await env.PAIRING_BOOTSTRAP.storedSlot(slotName)).expiresAt, firstSlot.expiresAt);
  assert.equal(
    env.SIGNAL_ROOMS.activePairingExpiresAt.get(ZERO_SECRET_ROOM),
    firstSlot.expiresAt,
  );
});

test("bootstrap durable object limits browser and device credential attempts", async () => {
  const browserObject = new PairingBootstrap({ storage: new MemoryStorage() }, {});
  for (let attempt = 0; attempt < 12; attempt += 1) {
    const response = await browserObject.fetch(new Request(
      "https://bootstrap.internal/attempt",
      {
        method: "POST",
        headers: { "X-NavOnWeb-Rate-Identity": "browseridentity000000000000000000" },
      },
    ));
    assert.equal(response.status, 204);
  }
  assert.equal((await browserObject.fetch(new Request(
    "https://bootstrap.internal/attempt",
    {
      method: "POST",
      headers: { "X-NavOnWeb-Rate-Identity": "browseridentity000000000000000000" },
    },
  ))).status, 429);

  const deviceObject = new PairingBootstrap({ storage: new MemoryStorage() }, {});
  for (let attempt = 0; attempt < 12; attempt += 1) {
    const idempotencyKey = String(attempt).padStart(22, "A");
    const response = await deviceObject.fetch(new Request(
      "https://bootstrap.internal/register-attempt",
      {
        method: "POST",
        headers: {
          "X-NavOnWeb-Rate-Identity": ZERO_SECRET_ROOM,
          "X-NavOnWeb-Rate-Idempotency": idempotencyKey,
        },
      },
    ));
    assert.equal(response.status, 204);
  }
  // An exact retry remains allowed after the distinct-publication budget is
  // full, while a thirteenth publication is throttled.
  assert.equal((await deviceObject.fetch(new Request(
    "https://bootstrap.internal/register-attempt",
    {
      method: "POST",
      headers: {
        "X-NavOnWeb-Rate-Identity": ZERO_SECRET_ROOM,
        "X-NavOnWeb-Rate-Idempotency": String(0).padStart(22, "A"),
      },
    },
  ))).status, 204);
  assert.equal((await deviceObject.fetch(new Request(
    "https://bootstrap.internal/register-attempt",
    {
      method: "POST",
      headers: {
        "X-NavOnWeb-Rate-Identity": ZERO_SECRET_ROOM,
        "X-NavOnWeb-Rate-Idempotency": String(12).padStart(22, "A"),
      },
    },
  ))).status, 429);
});

test("idempotent publications keep the identity budget but obey the replay ceiling", async () => {
  const storage = new MemoryStorage();
  const deviceObject = new PairingBootstrap({ storage }, {});
  const registrationAttempt = () => deviceObject.consumeAttempt(new Request(
    "https://bootstrap.internal/register-attempt",
    {
      method: "POST",
      headers: {
        "X-NavOnWeb-Rate-Identity": ZERO_SECRET_ROOM,
        "X-NavOnWeb-Rate-Idempotency": "R".repeat(22),
      },
    },
  ), 12, 3, true);

  assert.equal((await registrationAttempt()).status, 204);
  assert.equal((await registrationAttempt()).status, 204);
  assert.equal((await registrationAttempt()).status, 204);
  assert.equal((await registrationAttempt()).status, 429);
  const stored = await storage.get("rate");
  assert.equal(stored.identities[ZERO_SECRET_ROOM], 1);
  assert.equal(stored.totalCount, 3);
});

test("pairing slot is unconsumable until activation commit and retries stay idempotent", async () => {
  const storage = new MemoryStorage();
  const slot = new PairingBootstrap({ storage }, {});
  const expiresAt = Date.now() + 10 * 60 * 1000;
  const reservation = {
    roomId: ZERO_SECRET_ROOM,
    pairingEpoch: PAIRING_EPOCH_A,
    pairingGeneration: PAIRING_GENERATION_A,
    expiresAt,
  };
  assert.equal((await slotRequest(slot, "/reserve", reservation)).status, 204);
  assert.equal((await slotRequest(slot, "/consume")).status, 404);

  const otherRoom = await deriveRoomIdFromDeviceSecret(OTHER_SECRET);
  assert.equal((await slotRequest(slot, "/reserve", {
    roomId: otherRoom,
    pairingEpoch: PAIRING_EPOCH_A,
    pairingGeneration: PAIRING_GENERATION_B,
    expiresAt,
  })).status, 409);

  assert.equal((await slotRequest(slot, "/commit", reservation)).status, 204);
  assert.equal((await slotRequest(slot, "/reserve", {
    ...reservation,
    expiresAt: expiresAt - 60_000,
  })).status, 204);
  assert.equal((await storage.get("slot")).expiresAt, expiresAt);
  const consumed = await slotRequest(slot, "/consume");
  assert.equal(consumed.status, 200);
  assert.deepEqual(await consumed.json(), {
    roomId: ZERO_SECRET_ROOM,
    pairingEpoch: PAIRING_EPOCH_A,
    pairingGeneration: PAIRING_GENERATION_A,
    expiresAt,
  });
  assert.equal((await storage.get("slot")).consumed, true);
  assert.equal((await slotRequest(slot, "/consume")).status, 404);
  assert.equal((await slotRequest(slot, "/reserve", reservation)).status, 409);
  assert.equal((await slotRequest(slot, "/commit", reservation)).status, 409);
});

test("pairing request body is streamed with a strict 32-byte ceiling", async () => {
  assert.equal(await readBoundedPairingCode(new Request("https://example.test", {
    method: "POST",
    body: "12345678",
  })), "12345678");
  assert.equal(await readBoundedPairingCode(new Request("https://example.test", {
    method: "POST",
    body: "x".repeat(33),
  })), null);
  assert.equal(await readBoundedPairingCode(new Request("https://example.test", {
    method: "POST",
    headers: { "Content-Length": "999999" },
    body: "12345678",
  })), null);
  assert.equal(await readBoundedPairingCode(new Request("https://example.test", {
    method: "POST",
    body: new Uint8Array([0xff]),
  })), null);
});

test("browser attempt limits are client-scoped before the high CGNAT ceiling", async () => {
  const env = bootstrapEnvironment();
  const first = await browserPairAttempt(env, "192.0.2.44", "11111111");
  assert.equal(first.status, 404);
  const clientCookie = first.headers.get("set-cookie").split(";", 1)[0];

  for (let attempt = 1; attempt < 12; attempt += 1) {
    assert.equal((await browserPairAttempt(env, "192.0.2.44", "11111111", clientCookie)).status, 404);
  }
  assert.equal(
    (await browserPairAttempt(env, "192.0.2.44", "11111111", clientCookie)).status,
    429,
  );

  // Another real browser behind the same carrier NAT receives its own primary
  // budget; only the deliberately high network abuse ceiling is shared.
  assert.equal((await browserPairAttempt(env, "192.0.2.44", "11111111")).status, 404);
  assert.equal(
    env.PAIRING_BOOTSTRAP.requestPaths.filter((path) => path === "/attempt").length,
    14,
  );
});

test("device registration limits are credential-scoped before the high CGNAT ceiling", async () => {
  const retryEnv = bootstrapEnvironment();
  for (let attempt = 0; attempt < 20; attempt += 1) {
    assert.equal(
      (await deviceRegistration(retryEnv, "192.0.2.55", ZERO_SECRET, "22222222")).status,
      204,
    );
  }

  const env = bootstrapEnvironment();
  for (let publication = 1; publication <= 12; publication += 1) {
    assert.equal((await deviceRegistration(
      env,
      "192.0.2.55",
      ZERO_SECRET,
      String(publication).padStart(8, "0"),
      publication,
    )).status, 204);
  }
  assert.equal(
    (await deviceRegistration(env, "192.0.2.55", ZERO_SECRET, "99999999", 13)).status,
    429,
  );
  assert.equal(
    (await deviceRegistration(env, "192.0.2.55", OTHER_SECRET, "33333333")).status,
    204,
  );
  assert.equal(
    env.PAIRING_BOOTSTRAP.requestPaths.filter((path) => path === "/register-attempt").length,
    14,
  );
});

test("identical downstream-failure retries do not exhaust the publication budget", async () => {
  const env = {
    ...bootstrapEnvironment(),
    SIGNAL_ROOMS: new AlwaysFailingSignalBinding(),
  };
  for (let attempt = 0; attempt < 20; attempt += 1) {
    assert.equal(
      (await deviceRegistration(env, "192.0.2.56", ZERO_SECRET, "44444444")).status,
      503,
    );
  }

  const distinctEnv = {
    ...bootstrapEnvironment(),
    SIGNAL_ROOMS: new AlwaysFailingSignalBinding(),
  };
  for (let publication = 1; publication <= 12; publication += 1) {
    assert.equal((await deviceRegistration(
      distinctEnv,
      "192.0.2.57",
      ZERO_SECRET,
      String(10_000_000 + publication),
      publication,
    )).status, 503);
  }
  assert.equal((await deviceRegistration(
    distinctEnv,
    "192.0.2.57",
    ZERO_SECRET,
    "20000000",
    13,
  )).status, 429);
});

test("invalid and unknown pairing codes have the same public failure body", async () => {
  const env = bootstrapEnvironment();
  const invalid = await browserPairAttempt(env, "192.0.2.66", "not-eight");
  const unknown = await browserPairAttempt(env, "192.0.2.66", "99999999");
  assert.equal(invalid.status, 404);
  assert.equal(unknown.status, 404);
  assert.deepEqual(await invalid.json(), await unknown.json());
});

test("same-origin route status validates only the signed current or pending cookie", async () => {
  const env = bootstrapEnvironment();
  const network = "192.0.2.67";

  const missing = await browserRouteStatus(env);
  assert.equal(missing.status, 401);
  assert.equal(missing.headers.get("cache-control"), "no-store");
  assert.equal(missing.headers.get("access-control-allow-origin"), null);
  assert.equal(await missing.text(), "");

  assert.equal((await deviceRegistration(env, network, ZERO_SECRET, "12121212")).status, 204);
  const paired = await browserPairAttempt(env, network, "12121212");
  assert.equal(paired.status, 204);
  const pendingCookie = responseCookie(paired, ROUTE_COOKIE_NAME);
  assert.equal((await browserRouteStatus(env, pendingCookie)).status, 204);
  assert.equal((await browserSocket(env, pendingCookie)).status, 200);
  assert.equal((await browserRouteStatus(env, pendingCookie)).status, 204);

  const crossSite = await browserRouteStatus(env, pendingCookie, "cross-site");
  assert.equal(crossSite.status, 403);
});

test("route status distinguishes a revoked cookie from a transient room outage", async () => {
  const env = bootstrapEnvironment();
  const network = "192.0.2.68";

  assert.equal((await deviceRegistration(env, network, ZERO_SECRET, "13131313")).status, 204);
  const first = await browserPairAttempt(env, network, "13131313");
  const firstCookie = responseCookie(first, ROUTE_COOKIE_NAME);
  assert.equal((await browserSocket(env, firstCookie)).status, 200);

  assert.equal((await deviceRegistration(
    env,
    network,
    ZERO_SECRET,
    "14141414",
    PAIRING_EPOCH_B,
  )).status, 204);
  const second = await browserPairAttempt(env, network, "14141414");
  const secondCookie = responseCookie(second, ROUTE_COOKIE_NAME);
  assert.equal((await browserSocket(env, secondCookie)).status, 200);
  assert.equal((await browserRouteStatus(env, firstCookie)).status, 401);
  assert.equal((await browserRouteStatus(env, secondCookie)).status, 204);

  const unavailable = {
    ...env,
    SIGNAL_ROOMS: new AlwaysFailingSignalBinding(),
  };
  assert.equal((await browserRouteStatus(unavailable, secondCookie)).status, 503);
});

test("route status is read-only and rejects unsupported methods", async () => {
  const response = await worker.fetch(new Request(
    "https://navonweb.com/_nw/bootstrap/route",
    { method: "POST" },
  ), bootstrapEnvironment());
  assert.equal(response.status, 405);
  assert.equal(response.headers.get("allow"), "GET");
});

test("device WebSocket authentication failures expose one uniform response", async () => {
  const missing = await worker.fetch(new Request(
    `https://signal.navonweb.com/ws/device/${ZERO_SECRET_ROOM}`,
    { headers: { Upgrade: "websocket" } },
  ), bootstrapEnvironment());
  const mismatched = await worker.fetch(new Request(
    `https://signal.navonweb.com/ws/device/${ZERO_SECRET_ROOM}`,
    {
      headers: {
        Authorization: `Bearer ${OTHER_SECRET}`,
        Upgrade: "websocket",
      },
    },
  ), bootstrapEnvironment());
  assert.equal(missing.status, 401);
  assert.equal(mismatched.status, 401);
  assert.equal(missing.headers.get("www-authenticate"), mismatched.headers.get("www-authenticate"));
  assert.deepEqual(await missing.json(), await mismatched.json());
});

test("legacy browser room-id sockets are opt-in while cookie routing stays production default", async () => {
  const response = await worker.fetch(new Request(
    `https://navonweb.com/_nw/ws/browser/${ZERO_SECRET_ROOM}`,
    {
      headers: {
        Origin: "https://navonweb.com",
        Upgrade: "websocket",
      },
    },
  ), bootstrapEnvironment());
  assert.equal(response.status, 404);
});

test("reconnect admission is credential-scoped without extra Durable Object writes", () => {
  const room = new SignalRoom({}, {});
  const now = 1_800_000_000_000;
  for (let attempt = 0; attempt < 60; attempt += 1) {
    assert.equal(room.consumeConnectionAdmission("browser", now), true);
  }
  assert.equal(room.consumeConnectionAdmission("browser", now), false);
  assert.equal(room.consumeConnectionAdmission("device", now), true);
  assert.equal(room.consumeConnectionAdmission("browser", now + 10 * 60 * 1000), true);
});

test("legacy browser room route can be enabled explicitly for local migration", async () => {
  const env = {
    ...bootstrapEnvironment(),
    ALLOW_LEGACY_BROWSER_ROOM_ROUTE: "true",
  };
  const response = await worker.fetch(new Request(
    `https://navonweb.com/_nw/ws/browser/${ZERO_SECRET_ROOM}`,
    {
      headers: {
        "CF-Connecting-IP": "192.0.2.88",
        Origin: "https://navonweb.com",
        Upgrade: "websocket",
      },
    },
  ), env);
  assert.equal(response.status, 200);
});

test("pending route preserves the old browser until the new WebSocket promotes it", async () => {
  const env = bootstrapEnvironment();
  const network = "192.0.2.99";

  assert.equal((await deviceRegistration(env, network, ZERO_SECRET, "11111111")).status, 204);
  const firstPair = await browserPairAttempt(env, network, "11111111");
  assert.equal(firstPair.status, 204);
  const firstRouteCookie = responseCookie(firstPair, ROUTE_COOKIE_NAME);
  const browserClientCookie = responseCookie(firstPair, "__Host-navonweb_client");
  assert.equal((await browserSocket(env, firstRouteCookie)).status, 200);
  assert.equal(env.SIGNAL_ROOMS.currentRoutes.get(ZERO_SECRET_ROOM), ROUTE_NONCE_A);

  assert.equal((await deviceRegistration(
    env,
    network,
    ZERO_SECRET,
    "22222222",
    PAIRING_EPOCH_B,
  )).status, 204);
  const secondPair = await browserPairAttempt(env, network, "22222222", browserClientCookie);
  assert.equal(secondPair.status, 204);
  const secondRouteCookie = responseCookie(secondPair, ROUTE_COOKIE_NAME);

  // Preparing and even losing the HTTP response does not revoke the current route. A reconnect
  // carrying that exact current route may replace its own stale socket without consuming the
  // pending handoff.
  assert.equal(env.SIGNAL_ROOMS.currentRoutes.get(ZERO_SECRET_ROOM), ROUTE_NONCE_A);
  assert.equal(env.SIGNAL_ROOMS.pendingRoutes.get(ZERO_SECRET_ROOM), ROUTE_NONCE_B);
  assert.equal(env.SIGNAL_ROOMS.closedBrowserCount, 0);
  assert.equal((await browserSocket(env, firstRouteCookie)).status, 200);
  assert.equal(env.SIGNAL_ROOMS.closedBrowserCount, 1);

  // The first connection carrying the pending cookie commits the handoff.
  assert.equal((await browserSocket(env, secondRouteCookie)).status, 200);
  assert.equal(env.SIGNAL_ROOMS.closedBrowserCount, 2);
  assert.equal(env.SIGNAL_ROOMS.currentRoutes.get(ZERO_SECRET_ROOM), ROUTE_NONCE_B);
  assert.equal(env.SIGNAL_ROOMS.pendingRoutes.has(ZERO_SECRET_ROOM), false);
  assert.equal((await browserSocket(env, firstRouteCookie)).status, 401);
});

test("SignalRoom persists pending promotion and revokes the old socket before handoff", async () => {
  const ctx = new FakeSignalContext();
  const pairingExpiresAt = Date.now() + 600_000;
  await ctx.storage.put("browserRoute", {
    activePairingEpoch: PAIRING_EPOCH_A,
    activePairingGeneration: PAIRING_GENERATION_A,
    activePairingExpiresAt: pairingExpiresAt,
    currentRouteNonce: ROUTE_NONCE_A,
    currentSince: 1_800_000_000_000,
    pendingRouteNonce: null,
    pendingExpiresAt: null,
  });
  const oldBrowser = new FakeSocket({
    role: "browser",
    roomId: ZERO_SECRET_ROOM,
    routeNonce: ROUTE_NONCE_A,
    leftNotified: false,
    inFlightRequestIds: [],
  });
  const device = new FakeSocket({
    role: "device",
    roomId: ZERO_SECRET_ROOM,
    leftNotified: false,
  });
  ctx.sockets.browser.push(oldBrowser);
  ctx.sockets.device.push(device);
  const room = new SignalRoom(ctx, {});

  const prepared = await room.fetch(new Request(
    "https://signal.internal/internal/prepare-browser-route",
    {
      method: "POST",
      headers: {
        "X-NavOnWeb-Room": ZERO_SECRET_ROOM,
        "Content-Type": "application/json",
      },
      body: JSON.stringify({
        pairingEpoch: PAIRING_EPOCH_A,
        pairingGeneration: PAIRING_GENERATION_A,
        pairingExpiresAt,
      }),
    },
  ));
  assert.equal(prepared.status, 200);
  const pendingNonce = (await prepared.json()).routeNonce;
  assert.equal(oldBrowser.closed, null);
  assert.equal(await room.classifyBrowserRoute(ROUTE_NONCE_A), "current");
  assert.equal(oldBrowser.closed, null);

  assert.equal(await room.classifyBrowserRoute(pendingNonce), "pending");
  assert.equal(await room.promoteBrowserRoute(pendingNonce), true);
  assert.equal(oldBrowser.closed.code, 4001);
  assert.equal(oldBrowser.attachment.revoked, true);
  assert.equal(device.sent.length, 1);
  assert.equal(await room.classifyBrowserRoute(ROUTE_NONCE_A), null);

  // A new Durable Object instance reads the committed current generation.
  const restoredRoom = new SignalRoom(ctx, {});
  assert.equal(await restoredRoom.classifyBrowserRoute(pendingNonce), "current");
});

test("new pairing generation clears stale pending without disturbing current browser", async () => {
  const ctx = new FakeSignalContext();
  const firstExpiresAt = Date.now() + 300_000;
  const secondExpiresAt = Date.now() + 600_000;
  await ctx.storage.put("browserRoute", {
    activePairingEpoch: PAIRING_EPOCH_A,
    activePairingGeneration: PAIRING_GENERATION_A,
    activePairingExpiresAt: firstExpiresAt,
    currentRouteNonce: ROUTE_NONCE_A,
    currentSince: 1_800_000_000_000,
    pendingRouteNonce: null,
    pendingExpiresAt: null,
  });
  const oldBrowser = new FakeSocket({
    role: "browser",
    roomId: ZERO_SECRET_ROOM,
    routeNonce: ROUTE_NONCE_A,
    leftNotified: false,
  });
  ctx.sockets.browser.push(oldBrowser);
  const room = new SignalRoom(ctx, {});

  const stalePrepare = await signalRoomJsonRequest(
    room,
    "/internal/prepare-browser-route",
    PAIRING_EPOCH_A,
    PAIRING_GENERATION_A,
    firstExpiresAt,
  );
  assert.equal(stalePrepare.status, 200);
  const stalePendingNonce = (await stalePrepare.json()).routeNonce;

  // Linearization point for C -> C2: once C2 activates, any C slot that still
  // exists can no longer create or promote a pending route.
  assert.equal((await signalRoomJsonRequest(
    room,
    "/internal/activate-pairing-generation",
    PAIRING_EPOCH_B,
    PAIRING_GENERATION_B,
    secondExpiresAt,
  )).status, 204);
  assert.equal(await room.classifyBrowserRoute(stalePendingNonce), null);
  assert.equal(await room.promoteBrowserRoute(stalePendingNonce), false);
  assert.equal(await room.classifyBrowserRoute(ROUTE_NONCE_A), "current");
  assert.equal(oldBrowser.closed, null);

  const staleAfterRefresh = await signalRoomJsonRequest(
    room,
    "/internal/prepare-browser-route",
    PAIRING_EPOCH_A,
    PAIRING_GENERATION_A,
    firstExpiresAt,
  );
  assert.equal(staleAfterRefresh.status, 409);

  const freshPrepare = await signalRoomJsonRequest(
    room,
    "/internal/prepare-browser-route",
    PAIRING_EPOCH_B,
    PAIRING_GENERATION_B,
    secondExpiresAt,
  );
  assert.equal(freshPrepare.status, 200);
  const freshPendingNonce = (await freshPrepare.json()).routeNonce;
  // A registration retry for the same code/generation is a no-op and must not
  // erase the pending handoff it already authorized.
  assert.equal((await signalRoomJsonRequest(
    room,
    "/internal/activate-pairing-generation",
    PAIRING_EPOCH_B,
    PAIRING_GENERATION_B,
    secondExpiresAt,
  )).status, 204);
  assert.equal(await room.classifyBrowserRoute(freshPendingNonce), "pending");
});

test("authenticated reconnect replacement is scoped to device auth or the same browser route", () => {
  const ctx = new FakeSignalContext();
  const oldDevice = new FakeSocket({
    role: "device",
    roomId: ZERO_SECRET_ROOM,
    leftNotified: false,
  });
  const oldBrowser = new FakeSocket({
    role: "browser",
    roomId: ZERO_SECRET_ROOM,
    routeNonce: ROUTE_NONCE_A,
    leftNotified: false,
    inFlightRequestIds: ["Request_123456789"],
  });
  ctx.sockets.device.push(oldDevice);
  ctx.sockets.browser.push(oldBrowser);
  const room = new SignalRoom(ctx, {});

  assert.deepEqual(room.reconnectSockets("device", null), [oldDevice]);
  assert.deepEqual(room.reconnectSockets("browser", ROUTE_NONCE_A), [oldBrowser]);
  assert.equal(room.reconnectSockets("browser", ROUTE_NONCE_B), null);
  assert.equal(room.reconnectSockets("browser", null), null);

  room.supersedeReconnectSockets("device", [oldDevice]);
  assert.deepEqual(oldDevice.closed, { code: 4002, reason: "Connection replaced" });
  assert.equal(oldDevice.attachment.revoked, true);
  assert.equal(oldDevice.attachment.leftNotified, true);
  assert.deepEqual(oldBrowser.attachment.inFlightRequestIds, []);
  assert.equal(oldBrowser.sent.length, 0);
});

test("pairing publication epoch CAS rejects reverse arrival and conflicting retries", async () => {
  const ctx = new FakeSignalContext();
  const room = new SignalRoom(ctx, {});
  const firstExpiresAt = Date.now() + 300_000;
  const secondExpiresAt = Date.now() + 600_000;

  assert.equal((await signalRoomJsonRequest(
    room,
    "/internal/activate-pairing-generation",
    PAIRING_EPOCH_B,
    PAIRING_GENERATION_B,
    secondExpiresAt,
  )).status, 204);
  const prepared = await signalRoomJsonRequest(
    room,
    "/internal/prepare-browser-route",
    PAIRING_EPOCH_B,
    PAIRING_GENERATION_B,
    secondExpiresAt,
  );
  assert.equal(prepared.status, 200);
  const pendingNonce = (await prepared.json()).routeNonce;

  // C2 arrived first. A delayed C request cannot roll the active publication
  // backwards, nor can it authorize a route from its stale slot.
  assert.equal((await signalRoomJsonRequest(
    room,
    "/internal/activate-pairing-generation",
    PAIRING_EPOCH_A,
    PAIRING_GENERATION_A,
    firstExpiresAt,
  )).status, 409);
  assert.equal((await signalRoomJsonRequest(
    room,
    "/internal/prepare-browser-route",
    PAIRING_EPOCH_A,
    PAIRING_GENERATION_A,
    firstExpiresAt,
  )).status, 409);

  // An exact retry is idempotent and preserves the pending handoff; reusing
  // the same epoch for different content fails closed.
  assert.equal((await signalRoomJsonRequest(
    room,
    "/internal/activate-pairing-generation",
    PAIRING_EPOCH_B,
    PAIRING_GENERATION_B,
    secondExpiresAt,
  )).status, 204);
  assert.equal(await room.classifyBrowserRoute(pendingNonce), "pending");
  assert.equal((await signalRoomJsonRequest(
    room,
    "/internal/activate-pairing-generation",
    PAIRING_EPOCH_B,
    PAIRING_GENERATION_C,
    secondExpiresAt,
  )).status, 409);
  const stored = await ctx.storage.get("browserRoute");
  assert.equal(stored.activePairingEpoch, PAIRING_EPOCH_B);
  assert.equal(stored.activePairingGeneration, PAIRING_GENERATION_B);
  assert.equal(stored.pendingRouteNonce, pendingNonce);
});

test("near-deadline bootstrap cannot promote a pending route after code expiry", async () => {
  const expiresAt = Date.now() + 1_000;
  const slot = new PairingBootstrap({ storage: new MemoryStorage() }, {});
  const reservation = {
    roomId: ZERO_SECRET_ROOM,
    pairingEpoch: PAIRING_EPOCH_A,
    pairingGeneration: PAIRING_GENERATION_A,
    expiresAt,
  };
  assert.equal((await slotRequest(slot, "/reserve", reservation)).status, 204);
  assert.equal((await slotRequest(slot, "/commit", reservation)).status, 204);
  const consumed = await slotRequest(slot, "/consume");
  assert.equal(consumed.status, 200);
  assert.equal((await consumed.json()).expiresAt, expiresAt);

  const ctx = new FakeSignalContext();
  const room = new SignalRoom(ctx, {});
  assert.equal((await signalRoomJsonRequest(
    room,
    "/internal/activate-pairing-generation",
    PAIRING_EPOCH_A,
    PAIRING_GENERATION_A,
    expiresAt,
  )).status, 204);
  const prepared = await signalRoomJsonRequest(
    room,
    "/internal/prepare-browser-route",
    PAIRING_EPOCH_A,
    PAIRING_GENERATION_A,
    expiresAt,
  );
  assert.equal(prepared.status, 200);
  const pendingNonce = (await prepared.json()).routeNonce;
  assert.equal((await ctx.storage.get("browserRoute")).pendingExpiresAt, expiresAt);
  assert.equal(await room.classifyBrowserRoute(pendingNonce, expiresAt - 1), "pending");
  assert.equal(await room.classifyBrowserRoute(pendingNonce, expiresAt), null);
  assert.equal(await room.promoteBrowserRoute(pendingNonce, expiresAt), false);
});

test("an old committed code cannot replace current route after a new code activates", async () => {
  const env = bootstrapEnvironment();
  const network = "192.0.2.123";

  assert.equal((await deviceRegistration(env, network, ZERO_SECRET, "10101010")).status, 204);
  const initialPair = await browserPairAttempt(env, network, "10101010");
  assert.equal(initialPair.status, 204);
  const currentCookie = responseCookie(initialPair, ROUTE_COOKIE_NAME);
  assert.equal((await browserSocket(env, currentCookie)).status, 200);
  const currentNonce = env.SIGNAL_ROOMS.currentRoutes.get(ZERO_SECRET_ROOM);

  assert.equal((await deviceRegistration(
    env,
    network,
    ZERO_SECRET,
    "20202020",
    PAIRING_EPOCH_B,
  )).status, 204);
  const staleGeneration = env.SIGNAL_ROOMS.activePairingGenerations.get(ZERO_SECRET_ROOM);
  assert.match(staleGeneration, /^[A-Za-z0-9_-]{43}$/u);
  assert.equal((await deviceRegistration(
    env,
    network,
    ZERO_SECRET,
    "30303030",
    PAIRING_EPOCH_C,
  )).status, 204);
  const freshGeneration = env.SIGNAL_ROOMS.activePairingGenerations.get(ZERO_SECRET_ROOM);
  assert.notEqual(staleGeneration, freshGeneration);

  const stalePair = await browserPairAttempt(env, network, "20202020");
  assert.equal(stalePair.status, 404);
  assert.equal(env.SIGNAL_ROOMS.currentRoutes.get(ZERO_SECRET_ROOM), currentNonce);
  assert.equal(env.SIGNAL_ROOMS.closedBrowserCount, 0);
  assert.equal(env.SIGNAL_ROOMS.pendingRoutes.has(ZERO_SECRET_ROOM), false);

  const freshPair = await browserPairAttempt(env, network, "30303030");
  assert.equal(freshPair.status, 204);
  const freshCookie = responseCookie(freshPair, ROUTE_COOKIE_NAME);
  assert.equal((await browserSocket(env, freshCookie)).status, 200);
  assert.equal(env.SIGNAL_ROOMS.closedBrowserCount, 1);
  assert.equal((await browserSocket(env, currentCookie)).status, 401);
});

function bootstrapEnvironment() {
  return {
    ALLOWED_BROWSER_ORIGINS: "https://navonweb.com",
    BOOTSTRAP_HMAC_KEY: "test-bootstrap-secret-32-bytes-minimum",
    PAIRING_BOOTSTRAP: new InMemoryBootstrapBinding(),
    SIGNAL_ROOMS: new CapturingSignalBinding(),
  };
}

function browserPairAttempt(env, network, code, cookie = undefined) {
  const headers = {
    "CF-Connecting-IP": network,
    Origin: "https://navonweb.com",
    "Content-Type": "text/plain; charset=utf-8",
  };
  if (cookie) headers.Cookie = cookie;
  return worker.fetch(new Request("https://navonweb.com/_nw/bootstrap/pair", {
    method: "POST",
    headers,
    body: code,
  }), env);
}

function deviceRegistration(
  env,
  network,
  secret,
  code,
  pairingEpoch = PAIRING_EPOCH_A,
  pairingTtlMillis = 600_000,
) {
  return worker.fetch(new Request("https://signal.navonweb.com/bootstrap/device", {
    method: "POST",
    headers: {
      "CF-Connecting-IP": network,
      Authorization: `Bearer ${secret}`,
      "X-NavOnWeb-Pairing-Epoch": String(pairingEpoch),
      "X-NavOnWeb-Pairing-Ttl-Millis": String(pairingTtlMillis),
      "Content-Type": "text/plain; charset=utf-8",
    },
    body: code,
  }), env);
}

function responseCookie(response, name) {
  const value = response.headers.getSetCookie().find((entry) => entry.startsWith(`${name}=`));
  assert.ok(value, `missing ${name} response cookie`);
  return value.split(";", 1)[0];
}

function browserSocket(env, routeCookie) {
  return worker.fetch(new Request("https://navonweb.com/_nw/ws/browser", {
    headers: {
      Cookie: routeCookie,
      Origin: "https://navonweb.com",
      Upgrade: "websocket",
    },
  }), env);
}

function browserRouteStatus(env, routeCookie = undefined, fetchSite = "same-origin") {
  const headers = { "Sec-Fetch-Site": fetchSite };
  if (routeCookie) headers.Cookie = routeCookie;
  return worker.fetch(new Request("https://navonweb.com/_nw/bootstrap/route", {
    headers,
  }), env);
}

function slotRequest(slot, path, body = undefined) {
  return slot.fetch(new Request(`https://bootstrap.internal${path}`, {
    method: "POST",
    headers: body ? { "Content-Type": "application/json" } : undefined,
    body: body ? JSON.stringify(body) : undefined,
  }));
}

function signalRoomJsonRequest(
  room,
  path,
  pairingEpoch,
  pairingGeneration,
  pairingExpiresAt,
) {
  return room.fetch(new Request(`https://signal.internal${path}`, {
    method: "POST",
    headers: {
      "X-NavOnWeb-Room": ZERO_SECRET_ROOM,
      "Content-Type": "application/json",
    },
    body: JSON.stringify({ pairingEpoch, pairingGeneration, pairingExpiresAt }),
  }));
}

class MemoryStorage {
  constructor() {
    this.values = new Map();
    this.alarm = null;
  }

  async get(key) { return this.values.get(key); }
  async put(key, value) { this.values.set(key, value); }
  async delete(key) { return this.values.delete(key); }
  async setAlarm(value) { this.alarm = value; }
  async transaction(callback) { return callback(this); }
}

class FakeSocket {
  constructor(attachment) {
    this.attachment = attachment;
    this.readyState = 1;
    this.sent = [];
    this.closed = null;
  }

  deserializeAttachment() { return this.attachment; }
  serializeAttachment(value) { this.attachment = value; }
  send(value) { this.sent.push(value); }
  close(code, reason) {
    this.closed = { code, reason };
    this.readyState = 3;
  }
}

class FakeSignalContext {
  constructor() {
    this.storage = new MemoryStorage();
    this.sockets = { browser: [], device: [] };
  }

  getWebSockets(role) { return this.sockets[role] ?? []; }
}

class InMemoryBootstrapBinding {
  constructor() {
    this.objects = new Map();
    this.requestPaths = [];
  }

  idFromName(name) { return name; }

  get(id) {
    if (!this.objects.has(id)) {
      const storage = new MemoryStorage();
      const instance = new PairingBootstrap({ storage }, {});
      this.objects.set(id, {
        storage,
        fetch: (request) => {
          this.requestPaths.push(new URL(request.url).pathname);
          return instance.fetch(request);
        },
      });
    }
    return this.objects.get(id);
  }

  async storedSlot(id) {
    return this.objects.get(id)?.storage.get("slot");
  }
}

class AlwaysFailingSignalBinding {
  idFromName(name) { return name; }
  get() {
    return { fetch: async () => new Response(null, { status: 503 }) };
  }
}

class CapturingSignalBinding {
  constructor() {
    this.fetchCount = 0;
    this.rotationCount = 0;
    this.closedBrowserCount = 0;
    this.currentRoutes = new Map();
    this.pendingRoutes = new Map();
    this.pendingGenerations = new Map();
    this.pendingExpiresAt = new Map();
    this.activePairingEpochs = new Map();
    this.activePairingGenerations = new Map();
    this.activePairingExpiresAt = new Map();
    this.browserConnected = new Set();
  }

  idFromName(name) { return name; }

  get(id) {
    return {
      fetch: async (request) => {
        this.fetchCount += 1;
        this.lastRoomId = id;
        this.lastRole = request.headers.get("X-NavOnWeb-Role");
        const path = new URL(request.url).pathname;
        if (path === "/internal/activate-pairing-generation") {
          const body = await request.json();
          const activeEpoch = this.activePairingEpochs.get(id);
          const activeGeneration = this.activePairingGenerations.get(id);
          const activeExpiresAt = this.activePairingExpiresAt.get(id);
          if (!Number.isSafeInteger(body.pairingExpiresAt) || body.pairingExpiresAt <= Date.now()) {
            return new Response(null, { status: 409 });
          }
          if (activeEpoch !== undefined && body.pairingEpoch < activeEpoch) {
            return new Response(null, { status: 409 });
          }
          if (body.pairingEpoch === activeEpoch &&
              (body.pairingGeneration !== activeGeneration ||
               body.pairingExpiresAt !== activeExpiresAt)) {
            return new Response(null, { status: 409 });
          }
          if (body.pairingEpoch !== activeEpoch) {
            this.activePairingEpochs.set(id, body.pairingEpoch);
            this.activePairingGenerations.set(id, body.pairingGeneration);
            this.activePairingExpiresAt.set(id, body.pairingExpiresAt);
            this.pendingRoutes.delete(id);
            this.pendingGenerations.delete(id);
            this.pendingExpiresAt.delete(id);
          }
          return new Response(null, { status: 204 });
        }
        if (path === "/internal/prepare-browser-route") {
          const body = await request.json();
          if (this.activePairingEpochs.get(id) !== body.pairingEpoch ||
              this.activePairingGenerations.get(id) !== body.pairingGeneration ||
              this.activePairingExpiresAt.get(id) !== body.pairingExpiresAt ||
              body.pairingExpiresAt <= Date.now()) {
            return new Response(null, { status: 409 });
          }
          const routeNonce = this.rotationCount === 0 ? ROUTE_NONCE_A : ROUTE_NONCE_B;
          this.rotationCount += 1;
          this.pendingRoutes.set(id, routeNonce);
          this.pendingGenerations.set(id, body.pairingGeneration);
          this.pendingExpiresAt.set(id, body.pairingExpiresAt);
          return new Response(JSON.stringify({ routeNonce }), {
            headers: { "Content-Type": "application/json" },
          });
        }
        if (path === "/internal/check-browser-route") {
          const routeNonce = request.headers.get("X-NavOnWeb-Route-Nonce");
          const current = this.currentRoutes.get(id);
          const pending = this.pendingRoutes.get(id);
          const pendingAlive = this.pendingExpiresAt.get(id) > Date.now();
          return new Response(null, {
            status: routeNonce === current || (routeNonce === pending && pendingAlive)
              ? 204
              : 401,
          });
        }
        if (this.lastRole === "browser" &&
            request.headers.get("X-NavOnWeb-Legacy-Browser-Route") !== "1") {
          const routeNonce = request.headers.get("X-NavOnWeb-Route-Nonce");
          if (routeNonce === this.pendingRoutes.get(id) &&
              this.pendingExpiresAt.get(id) > Date.now()) {
            this.pendingRoutes.delete(id);
            this.pendingGenerations.delete(id);
            this.pendingExpiresAt.delete(id);
            this.currentRoutes.set(id, routeNonce);
            if (this.browserConnected.delete(id)) this.closedBrowserCount += 1;
          } else if (routeNonce !== this.currentRoutes.get(id)) {
            return new Response(JSON.stringify({ error: "Browser pairing is required" }), {
              status: 401,
              headers: { "Content-Type": "application/json" },
            });
          }
          if (this.browserConnected.has(id)) this.closedBrowserCount += 1;
          this.browserConnected.add(id);
        }
        return new Response("routed");
      },
    };
  }
}
