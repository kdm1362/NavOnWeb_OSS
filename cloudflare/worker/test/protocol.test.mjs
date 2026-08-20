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
const ROUTE_NONCE_C = "HHHHHHHHHHHHHHHHHHHHHH";
const ROUTE_NONCE_D = "IIIIIIIIIIIIIIIIIIIIII";
const PAIRING_GENERATION_A = "E".repeat(43);
const PAIRING_GENERATION_B = "F".repeat(43);
const PAIRING_EPOCH_A = 1;
const PAIRING_EPOCH_B = 2;

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

test("v3 route cookie authenticates issuance while legacy v2 remains reconnect-only", async () => {
  const secret = "test-bootstrap-secret-32-bytes-minimum";
  const now = 1_800_000_000_000;
  const value = await createRouteCookieValue(secret, ZERO_SECRET_ROOM, ROUTE_NONCE_A, now, 60);
  assert.deepEqual(await verifyRouteCookieValue(secret, value, now + 30_000), {
    version: "v3",
    roomId: ZERO_SECRET_ROOM,
    routeNonce: ROUTE_NONCE_A,
    issuedAtSeconds: 1_800_000_000,
    expiresAtSeconds: 1_800_000_060,
  });
  const legacy = await createRouteCookieValue(
    secret,
    ZERO_SECRET_ROOM,
    ROUTE_NONCE_B,
    now,
    60,
    "v2",
  );
  assert.deepEqual(await verifyRouteCookieValue(secret, legacy, now + 30_000), {
    version: "v2",
    roomId: ZERO_SECRET_ROOM,
    routeNonce: ROUTE_NONCE_B,
    issuedAtSeconds: null,
    expiresAtSeconds: 1_800_000_060,
  });
  assert.equal(await verifyRouteCookieValue(secret, value, now + 60_000), null);
  assert.equal(await verifyRouteCookieValue(secret, `${value.slice(0, -1)}A`, now), null);
  const issuedAtTampered = value.replace(".1800000000.", ".1800000001.");
  assert.equal(await verifyRouteCookieValue(secret, issuedAtTampered, now), null);
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
  const signedRoute = await signedRouteFromCookie(env, cookie);
  assert.equal(signedRoute.version, "v3");
  assert.equal(signalRooms.lastRouteIssuedAt, String(signedRoute.issuedAtSeconds));
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

test("delayed first registration uses remaining TTL and retries never extend its bootstrap slot", async () => {
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
  // canonical first-reserve expiry. No SignalRoom registry is touched.
  assert.equal((await deviceRegistration(
    env,
    network,
    ZERO_SECRET,
    "56565656",
    PAIRING_EPOCH_A,
    600_000,
  )).status, 204);
  assert.equal((await env.PAIRING_BOOTSTRAP.storedSlot(slotName)).expiresAt, firstSlot.expiresAt);
  assert.equal(env.SIGNAL_ROOMS.fetchCount, 0);
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

test("pairing bootstrap does not depend on SignalRoom storage or availability", async () => {
  const env = {
    ...bootstrapEnvironment(),
    SIGNAL_ROOMS: new AlwaysFailingSignalBinding(),
  };
  assert.equal(
    (await deviceRegistration(env, "192.0.2.56", ZERO_SECRET, "44444444")).status,
    204,
  );
  const paired = await browserPairAttempt(env, "192.0.2.56", "44444444");
  assert.equal(paired.status, 204);
  const routeCookie = responseCookie(paired, ROUTE_COOKIE_NAME);
  assert.equal((await browserRouteStatus(env, routeCookie)).status, 204);
  assert.equal(env.SIGNAL_ROOMS.fetchCount, 0);
});

test("invalid and unknown pairing codes have the same public failure body", async () => {
  const env = bootstrapEnvironment();
  const invalid = await browserPairAttempt(env, "192.0.2.66", "not-eight");
  const unknown = await browserPairAttempt(env, "192.0.2.66", "99999999");
  assert.equal(invalid.status, 404);
  assert.equal(unknown.status, 404);
  assert.deepEqual(await invalid.json(), await unknown.json());
});

test("same-origin route status validates only the self-contained signed cookie", async () => {
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
  const routeCookie = responseCookie(paired, ROUTE_COOKIE_NAME);
  assert.equal((await browserRouteStatus(env, routeCookie)).status, 204);
  assert.equal((await browserSocket(env, routeCookie)).status, 200);
  assert.equal((await browserRouteStatus(env, routeCookie)).status, 204);

  const route = await signedRouteFromCookie(env, routeCookie);
  const expiredValue = await createRouteCookieValue(
    env.BOOTSTRAP_HMAC_KEY,
    route.roomId,
    route.routeNonce,
    Date.now() - 120_000,
    60,
  );
  assert.equal((await browserRouteStatus(
    env,
    `${ROUTE_COOKIE_NAME}=${expiredValue}`,
  )).status, 401);
  const routeValue = readCookie(routeCookie, ROUTE_COOKIE_NAME);
  const tamperedValue = `${routeValue.slice(0, -1)}${routeValue.endsWith("A") ? "B" : "A"}`;
  assert.equal((await browserRouteStatus(
    env,
    `${ROUTE_COOKIE_NAME}=${tamperedValue}`,
  )).status, 401);

  const crossSite = await browserRouteStatus(env, routeCookie, "cross-site");
  assert.equal(crossSite.status, 403);
});

test("legacy v2 route remains valid while outer routing strips forged internal headers", async () => {
  const env = bootstrapEnvironment();
  const legacyValue = await createRouteCookieValue(
    env.BOOTSTRAP_HMAC_KEY,
    ZERO_SECRET_ROOM,
    ROUTE_NONCE_A,
    Date.now(),
    60,
    "v2",
  );
  const cookie = `${ROUTE_COOKIE_NAME}=${legacyValue}`;
  assert.equal((await browserRouteStatus(env, cookie)).status, 204);
  const routed = await worker.fetch(new Request("https://navonweb.com/_nw/ws/browser", {
    headers: {
      Cookie: cookie,
      Origin: "https://navonweb.com",
      Upgrade: "websocket",
      "X-NavOnWeb-Route-Issued-At": String(Math.floor(Date.now() / 1000)),
      "X-NavOnWeb-Route-Nonce": ROUTE_NONCE_B,
      "X-NavOnWeb-Legacy-Browser-Route": "1",
    },
  }), env);
  assert.equal(routed.status, 200);
  assert.equal(env.SIGNAL_ROOMS.lastRouteIssuedAt, null);
  assert.equal(env.SIGNAL_ROOMS.lastRouteNonce, ROUTE_NONCE_A);
  assert.equal(env.SIGNAL_ROOMS.lastLegacyBrowserRoute, null);
});

test("new pairings preserve every signed cookie and route status is independent of room outages", async () => {
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
  assert.equal((await browserRouteStatus(env, firstCookie)).status, 204);
  assert.equal((await browserRouteStatus(env, secondCookie)).status, 204);

  const unavailable = {
    ...env,
    SIGNAL_ROOMS: new AlwaysFailingSignalBinding(),
  };
  assert.equal((await browserRouteStatus(unavailable, firstCookie)).status, 204);
  assert.equal((await browserRouteStatus(unavailable, secondCookie)).status, 204);
  assert.equal(unavailable.SIGNAL_ROOMS.fetchCount, 0);
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

test("reconnect admission is isolated by signed route without Durable Object writes", () => {
  const room = new SignalRoom({}, {});
  const now = 1_800_000_000_000;
  for (let attempt = 0; attempt < 60; attempt += 1) {
    assert.equal(room.consumeConnectionAdmission("browser", ROUTE_NONCE_A, now), true);
  }
  assert.equal(room.consumeConnectionAdmission("browser", ROUTE_NONCE_A, now), false);
  assert.equal(room.consumeConnectionAdmission("browser", ROUTE_NONCE_B, now), true);
  assert.equal(room.consumeConnectionAdmission("browser", ROUTE_NONCE_C, now), true);
  assert.equal(room.consumeConnectionAdmission("device", null, now), true);
  assert.equal(
    room.consumeConnectionAdmission(
      "browser",
      ROUTE_NONCE_A,
      now + 10 * 60 * 1000,
    ),
    true,
  );
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

test("a newly paired browser preserves existing routes and same-route reconnect replaces only itself", async () => {
  const env = bootstrapEnvironment();
  const network = "192.0.2.99";

  assert.equal((await deviceRegistration(env, network, ZERO_SECRET, "11111111")).status, 204);
  const firstPair = await browserPairAttempt(env, network, "11111111");
  assert.equal(firstPair.status, 204);
  const firstRouteCookie = responseCookie(firstPair, ROUTE_COOKIE_NAME);
  const browserClientCookie = responseCookie(firstPair, "__Host-navonweb_client");
  const firstRoute = await signedRouteFromCookie(env, firstRouteCookie);
  assert.equal((await browserSocket(env, firstRouteCookie)).status, 200);

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
  const secondRoute = await signedRouteFromCookie(env, secondRouteCookie);
  assert.notEqual(firstRoute.routeNonce, secondRoute.routeNonce);

  // Issuing another signed route does not touch SignalRoom and cannot replace the first socket.
  assert.equal(env.SIGNAL_ROOMS.fetchCount, 1);
  assert.equal(env.SIGNAL_ROOMS.closedBrowserCount, 0);
  assert.equal((await browserSocket(env, secondRouteCookie)).status, 200);
  assert.equal(env.SIGNAL_ROOMS.closedBrowserCount, 0);
  assert.deepEqual(
    [...env.SIGNAL_ROOMS.browserConnections.get(ZERO_SECRET_ROOM)],
    [firstRoute.routeNonce, secondRoute.routeNonce],
  );

  // A reconnect carrying the exact same signed nonce replaces only its stale signaling socket.
  assert.equal((await browserSocket(env, firstRouteCookie)).status, 200);
  assert.equal(env.SIGNAL_ROOMS.closedBrowserCount, 1);
  assert.equal((await browserRouteStatus(env, firstRouteCookie)).status, 204);
  assert.equal((await browserRouteStatus(env, secondRouteCookie)).status, 204);
});

test("three stale signaling routes cannot block a fourth phone-side admission candidate", async () => {
  const env = bootstrapEnvironment();
  const network = "192.0.2.100";
  const codes = ["41414141", "42424242", "43434343", "44444444"];
  const routeCookies = [];
  const routeNonces = [];

  for (let index = 0; index < codes.length; index += 1) {
    assert.equal((await deviceRegistration(
      env,
      network,
      ZERO_SECRET,
      codes[index],
      index + 1,
    )).status, 204);
    const paired = await browserPairAttempt(env, network, codes[index]);
    assert.equal(paired.status, 204);
    const cookie = responseCookie(paired, ROUTE_COOKIE_NAME);
    routeCookies.push(cookie);
    routeNonces.push((await signedRouteFromCookie(env, cookie)).routeNonce);
    const connected = await browserSocket(env, cookie);
    assert.equal(connected.status, 200);
  }
  assert.deepEqual(
    [...env.SIGNAL_ROOMS.browserConnections.get(ZERO_SECRET_ROOM)],
    routeNonces,
  );

  // The Worker has no paired-device/capacity registry. The authenticated phone RPC endpoint is
  // authoritative for deleted credentials and its actual 1/3 media-session limit.
  assert.equal(env.SIGNAL_ROOMS.browserConnections.get(ZERO_SECRET_ROOM).size, 4);
  for (const cookie of routeCookies) {
    assert.equal((await browserRouteStatus(env, cookie)).status, 204);
  }
});

test("SignalRoom has no durable paired-route, session, or capacity registry", () => {
  const source = SignalRoom.toString();
  assert.doesNotMatch(source, /ctx\.storage|rememberedRoutes|browserRouteStateForStorage/u);
  assert.doesNotMatch(source, /pendingRouteNonce|activePairingGeneration/u);
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
  assert.deepEqual(room.reconnectSockets("browser", ROUTE_NONCE_B), []);
  assert.deepEqual(room.reconnectSockets("browser", null), []);

  const secondBrowser = new FakeSocket({
    role: "browser",
    roomId: ZERO_SECRET_ROOM,
    routeNonce: ROUTE_NONCE_B,
    leftNotified: false,
    inFlightRequestIds: ["Request_234567890"],
  });
  const thirdBrowser = new FakeSocket({
    role: "browser",
    roomId: ZERO_SECRET_ROOM,
    routeNonce: ROUTE_NONCE_C,
    leftNotified: false,
    inFlightRequestIds: ["Request_345678901"],
  });
  ctx.sockets.browser.push(secondBrowser, thirdBrowser);
  assert.deepEqual(room.reconnectSockets("browser", ROUTE_NONCE_D), []);
  assert.deepEqual(room.reconnectSockets("browser", ROUTE_NONCE_B), [secondBrowser]);

  for (let index = 3; index < 32; index += 1) {
    ctx.sockets.browser.push(signalBrowserSocket(routeNonceForRotation(index)));
  }
  assert.equal(
    typeof room.reconnectSockets("browser", routeNonceForRotation(32)),
    "symbol",
  );

  room.supersedeReconnectSockets("device", [oldDevice]);
  assert.deepEqual(oldDevice.closed, { code: 4002, reason: "Connection replaced" });
  assert.equal(oldDevice.attachment.revoked, true);
  assert.equal(oldDevice.attachment.leftNotified, true);
  assert.deepEqual(oldBrowser.attachment.inFlightRequestIds, []);
  assert.deepEqual(secondBrowser.attachment.inFlightRequestIds, []);
  assert.deepEqual(thirdBrowser.attachment.inFlightRequestIds, []);
  assert.equal(oldBrowser.closed, null);
  assert.equal(secondBrowser.closed, null);
  assert.equal(thirdBrowser.closed, null);
  assert.equal(oldBrowser.sent.length, 0);
});

test("phone 401 quarantines only its exact stale route while preserving peers and device", async () => {
  const ctx = new FakeSignalContext();
  const device = signalDeviceSocket();
  const firstBrowser = signalBrowserSocket(ROUTE_NONCE_A);
  const secondBrowser = signalBrowserSocket(ROUTE_NONCE_B);
  const thirdBrowser = signalBrowserSocket(ROUTE_NONCE_C);
  ctx.sockets.device.push(device);
  ctx.sockets.browser.push(firstBrowser, secondBrowser, thirdBrowser);
  const room = new SignalRoom(ctx, {});
  const firstRequestId = "Request_123456789";
  const thirdRequestId = "Request_987654321";
  const firstRequest = JSON.stringify({
    type: "rpc_request",
    requestId: firstRequestId,
    method: "GET",
    target: "/api/status",
    headers: {},
    bodyBase64: "",
  });
  const thirdRequest = JSON.stringify({
    type: "rpc_request",
    requestId: thirdRequestId,
    method: "GET",
    target: "/api/status",
    headers: {},
    bodyBase64: "",
  });

  room.webSocketMessage(firstBrowser, firstRequest);
  room.webSocketMessage(secondBrowser, firstRequest);
  room.webSocketMessage(thirdBrowser, thirdRequest);
  assert.deepEqual(device.sent, [firstRequest, thirdRequest]);
  assert.deepEqual(secondBrowser.closed, {
    code: 1008,
    reason: "Duplicate cross-browser in-flight requestId",
  });

  // A deleted phone-side credential is represented by the phone's opaque 401 RPC body. The
  // Worker must preserve it byte-for-byte and route it only to the browser that made the call.
  const phoneRejection = JSON.stringify({
    type: "rpc_response",
    requestId: firstRequestId,
    status: 401,
    contentType: "application/json; charset=utf-8",
    bodyBase64: "eyJlcnJvciI6InBhaXJpbmdfcmVxdWlyZWQifQ==",
  });
  room.webSocketMessage(device, phoneRejection);
  assert.deepEqual(firstBrowser.sent, [phoneRejection]);
  assert.deepEqual(secondBrowser.sent, []);
  assert.deepEqual(thirdBrowser.sent, []);
  assert.deepEqual(firstBrowser.attachment.inFlightRequestIds, []);
  assert.equal(firstBrowser.attachment.revoked, true);
  assert.deepEqual(firstBrowser.closed, {
    code: 4003,
    reason: "Browser authorization rejected",
  });
  assert.deepEqual(thirdBrowser.attachment.inFlightRequestIds, [thirdRequestId]);
  assert.equal(device.closed, null);
  assert.equal(thirdBrowser.closed, null);
  assert.ok(room.routeQuarantineRemainingMillis(ROUTE_NONCE_A) > 0);
  assert.equal(room.routeQuarantineRemainingMillis(ROUTE_NONCE_C), 0);

  const quarantinedReconnect = await room.fetch(new Request(
    "https://signal.internal/ws/browser",
    {
      headers: {
        Upgrade: "websocket",
        "X-NavOnWeb-Role": "browser",
        "X-NavOnWeb-Room": ZERO_SECRET_ROOM,
        "X-NavOnWeb-Route-Nonce": ROUTE_NONCE_A,
        "X-NavOnWeb-Route-Issued-At": String(Math.floor(Date.now() / 1000)),
      },
    },
  ));
  assert.equal(quarantinedReconnect.status, 429);
  assert.match(quarantinedReconnect.headers.get("retry-after"), /^[1-9][0-9]*$/u);

  const thirdResponse = JSON.stringify({
    type: "rpc_response",
    requestId: thirdRequestId,
    status: 200,
    contentType: "application/json; charset=utf-8",
    bodyBase64: "e30=",
  });
  room.webSocketMessage(device, thirdResponse);
  assert.deepEqual(thirdBrowser.sent, [thirdResponse]);
  assert.deepEqual(thirdBrowser.attachment.inFlightRequestIds, []);
  assert.equal(device.closed, null);
});

test("room-wide in-flight cap rejects only the seventeenth RPC and preserves every owner", () => {
  const ctx = new FakeSignalContext();
  const device = signalDeviceSocket();
  const browsers = [
    signalBrowserSocket(ROUTE_NONCE_A),
    signalBrowserSocket(ROUTE_NONCE_B),
    signalBrowserSocket(ROUTE_NONCE_C),
  ];
  ctx.sockets.device.push(device);
  ctx.sockets.browser.push(...browsers);
  const room = new SignalRoom(ctx, {});
  const requests = Array.from({ length: 17 }, (_, index) => JSON.stringify({
    type: "rpc_request",
    requestId: `Aggregate${String(index).padStart(8, "0")}`,
    method: "GET",
    target: "/api/status",
    headers: {},
    bodyBase64: "",
  }));

  for (let index = 0; index < 16; index += 1) {
    room.webSocketMessage(browsers[index % browsers.length], requests[index]);
  }
  assert.equal(device.sent.length, 16);
  assert.equal(room.aggregateInFlightRequestCount(), 16);

  const rejectedOwner = browsers[0];
  room.webSocketMessage(rejectedOwner, requests[16]);
  assert.equal(device.sent.length, 16);
  const busy = JSON.parse(rejectedOwner.sent.at(-1));
  assert.equal(busy.status, 429);
  assert.deepEqual(JSON.parse(atob(busy.bodyBase64)), { error: "cloud_relay_busy" });
  assert.equal(device.closed, null);
  for (const browser of browsers) assert.equal(browser.closed, null);
  assert.equal(room.aggregateInFlightRequestCount(), 16);

  const firstResponse = JSON.stringify({
    type: "rpc_response",
    requestId: "Aggregate00000000",
    status: 200,
    contentType: "application/json",
    bodyBase64: "e30=",
  });
  room.webSocketMessage(device, firstResponse);
  assert.equal(room.aggregateInFlightRequestCount(), 15);
  room.webSocketMessage(rejectedOwner, requests[16]);
  assert.equal(device.sent.length, 17);
  assert.equal(device.sent.at(-1), requests[16]);
  assert.equal(room.aggregateInFlightRequestCount(), 16);
  assert.equal(device.closed, null);
});

test("orphaned owner requests remain inside the room-wide in-flight cap", () => {
  const ctx = new FakeSignalContext();
  const device = signalDeviceSocket();
  const requestIds = Array.from(
    { length: 16 },
    (_, index) => `OrphanCap${String(index).padStart(8, "0")}`,
  );
  const disconnectedOwner = signalBrowserSocket(ROUTE_NONCE_A, {
    inFlightRequestIds: requestIds,
  });
  const contender = signalBrowserSocket(ROUTE_NONCE_B);
  ctx.sockets.device.push(device);
  ctx.sockets.browser.push(disconnectedOwner, contender);
  const room = new SignalRoom(ctx, {});

  room.supersedeReconnectSockets("browser", [disconnectedOwner]);
  assert.equal(disconnectedOwner.attachment.revoked, true);
  assert.equal(device.attachment.orphanedRequestIds.length, 16);
  assert.equal(room.aggregateInFlightRequestCount(), 16);

  const seventeenth = JSON.stringify({
    type: "rpc_request",
    requestId: "OrphanCap00000016",
    method: "GET",
    target: "/api/status",
    headers: {},
    bodyBase64: "",
  });
  room.webSocketMessage(contender, seventeenth);
  assert.equal(device.sent.length, 0);
  const busy = JSON.parse(contender.sent.at(-1));
  assert.equal(busy.status, 429);
  assert.deepEqual(JSON.parse(atob(busy.bodyBase64)), { error: "cloud_relay_busy" });
  assert.equal(device.closed, null);
  assert.equal(contender.closed, null);

  room.webSocketMessage(device, JSON.stringify({
    type: "rpc_response",
    requestId: requestIds[0],
    status: 200,
    contentType: "application/json",
    bodyBase64: "e30=",
  }));
  assert.equal(room.aggregateInFlightRequestCount(), 15);
  room.webSocketMessage(contender, seventeenth);
  assert.equal(device.sent.at(-1), seventeenth);
  assert.equal(room.aggregateInFlightRequestCount(), 16);
  assert.equal(device.closed, null);
});

test("only a route issued by the current ten-minute bootstrap may relay api pair", () => {
  const ctx = new FakeSignalContext();
  const device = signalDeviceSocket();
  const freshBrowser = signalBrowserSocket(ROUTE_NONCE_A);
  const staleBrowser = signalBrowserSocket(ROUTE_NONCE_B, {
    routeIssuedAtSeconds: Math.floor((Date.now() - 600_001) / 1000),
  });
  const legacyBrowser = signalBrowserSocket(ROUTE_NONCE_C, {
    routeIssuedAtSeconds: null,
  });
  ctx.sockets.device.push(device);
  ctx.sockets.browser.push(freshBrowser, staleBrowser, legacyBrowser);
  const room = new SignalRoom(ctx, {});
  const pairRequest = (requestId, method = "POST") => JSON.stringify({
    type: "rpc_request",
    requestId,
    method,
    target: "/api/pair",
    headers: { "X-Pairing-Code": "12345678" },
    bodyBase64: "",
  });

  room.webSocketMessage(staleBrowser, pairRequest("StalePair1234567", "post"));
  room.webSocketMessage(legacyBrowser, pairRequest("LegacyPair123456"));
  assert.equal(device.sent.length, 0);
  for (const browser of [staleBrowser, legacyBrowser]) {
    const response = JSON.parse(browser.sent.at(-1));
    assert.equal(response.status, 428);
    assert.deepEqual(JSON.parse(atob(response.bodyBase64)), {
      error: "cloud_relay_fresh_pairing_route_required",
    });
    assert.deepEqual(browser.attachment.inFlightRequestIds, []);
    assert.equal(browser.closed, null);
  }

  room.webSocketMessage(freshBrowser, pairRequest("FreshPair1234567"));
  assert.equal(device.sent.length, 1);
  assert.deepEqual(freshBrowser.attachment.inFlightRequestIds, ["FreshPair1234567"]);

  const oldRouteStatus = JSON.stringify({
    type: "rpc_request",
    requestId: "OldStatus1234567",
    method: "GET",
    target: "/api/status",
    headers: {},
    bodyBase64: "",
  });
  room.webSocketMessage(staleBrowser, oldRouteStatus);
  assert.equal(device.sent.at(-1), oldRouteStatus);
  assert.equal(staleBrowser.closed, null);
  assert.equal(device.closed, null);
});

test("32 signaling transports share a bounded phone-facing RPC budget without socket teardown", () => {
  const ctx = new FakeSignalContext();
  const device = signalDeviceSocket();
  const browsers = Array.from(
    { length: 32 },
    (_, index) => signalBrowserSocket(routeNonceForRotation(index)),
  );
  ctx.sockets.device.push(device);
  ctx.sockets.browser.push(...browsers);
  const room = new SignalRoom(ctx, {});
  const fixedNow = 1_800_000_000_000;

  // Three normal browser bursts fit; the next aggregate request is denied.
  for (let request = 0; request < 288; request += 1) {
    assert.equal(room.reserveDeviceRpc(device, fixedNow), true);
  }
  assert.equal(room.reserveDeviceRpc(device, fixedNow), false);

  // Hold the already-exhausted bucket without refill and let all 32 transports attempt a burst.
  device.attachment.aggregateRpcRateTokens = 0;
  device.attachment.aggregateRpcRateUpdatedAt = Number.MAX_SAFE_INTEGER;
  for (let index = 0; index < 512; index += 1) {
    const requestId = `Flood${String(index).padStart(11, "0")}`;
    room.webSocketMessage(browsers[index % browsers.length], JSON.stringify({
      type: "rpc_request",
      requestId,
      method: "GET",
      target: "/api/status",
      headers: {},
      bodyBase64: "",
    }));
  }

  assert.equal(device.sent.length, 0);
  assert.equal(device.closed, null);
  assert.equal(browsers.reduce((count, browser) => count + browser.sent.length, 0), 512);
  for (const browser of browsers) {
    assert.equal(browser.closed, null);
    assert.deepEqual(browser.attachment.inFlightRequestIds, []);
    for (const encoded of browser.sent) {
      const response = JSON.parse(encoded);
      assert.equal(response.status, 429);
      assert.deepEqual(JSON.parse(atob(response.bodyBase64)), {
        error: "cloud_relay_device_rate_limited",
      });
    }
  }
});

test("late response for a disconnected browser is consumed without closing the shared device", () => {
  const ctx = new FakeSignalContext();
  const device = signalDeviceSocket();
  const disconnectedBrowser = signalBrowserSocket(ROUTE_NONCE_A);
  const liveBrowser = signalBrowserSocket(ROUTE_NONCE_B);
  ctx.sockets.device.push(device);
  ctx.sockets.browser.push(disconnectedBrowser, liveBrowser);
  const room = new SignalRoom(ctx, {});
  const orphanRequestId = "Orphan_123456789";
  const orphanRequest = JSON.stringify({
    type: "rpc_request",
    requestId: orphanRequestId,
    method: "GET",
    target: "/api/status",
    headers: {},
    bodyBase64: "",
  });
  room.webSocketMessage(disconnectedBrowser, orphanRequest);
  disconnectedBrowser.readyState = 3;
  room.webSocketClose(disconnectedBrowser, 1000, "Browser left", true);
  assert.deepEqual(disconnectedBrowser.attachment.inFlightRequestIds, []);
  assert.equal(device.attachment.orphanedRequestIds[0].requestId, orphanRequestId);

  const lateResponse = JSON.stringify({
    type: "rpc_response",
    requestId: orphanRequestId,
    status: 200,
    contentType: "application/json",
    bodyBase64: "e30=",
  });
  room.webSocketMessage(device, lateResponse);
  assert.equal(device.closed, null);
  assert.deepEqual(liveBrowser.sent, []);
  assert.deepEqual(device.attachment.orphanedRequestIds, []);

  const unknownResponse = JSON.stringify({
    type: "rpc_response",
    requestId: "Unknown_12345678",
    status: 200,
    contentType: "application/json",
    bodyBase64: "e30=",
  });
  room.webSocketMessage(device, unknownResponse);
  assert.deepEqual(device.closed, {
    code: 1008,
    reason: "Unknown rpc_response requestId",
  });
});

test("orphaned 401 closes a same-route replacement but preserves peers and device", () => {
  const ctx = new FakeSignalContext();
  const device = signalDeviceSocket();
  const staleBrowser = signalBrowserSocket(ROUTE_NONCE_A);
  const peerBrowser = signalBrowserSocket(ROUTE_NONCE_B);
  ctx.sockets.device.push(device);
  ctx.sockets.browser.push(staleBrowser, peerBrowser);
  const room = new SignalRoom(ctx, {});
  const requestId = "Orphan4011234567";
  const request = JSON.stringify({
    type: "rpc_request",
    requestId,
    method: "GET",
    target: "/api/status",
    headers: {},
    bodyBase64: "",
  });

  room.webSocketMessage(staleBrowser, request);
  room.supersedeReconnectSockets("browser", [staleBrowser]);
  const replacement = signalBrowserSocket(ROUTE_NONCE_A);
  ctx.sockets.browser.push(replacement);
  assert.equal(device.attachment.orphanedRequestIds[0].routeNonce, ROUTE_NONCE_A);

  room.webSocketMessage(device, JSON.stringify({
    type: "rpc_response",
    requestId,
    status: 401,
    contentType: "application/json",
    bodyBase64: "eyJlcnJvciI6InBhaXJpbmdfcmVxdWlyZWQifQ==",
  }));
  assert.deepEqual(device.attachment.orphanedRequestIds, []);
  assert.equal(replacement.attachment.revoked, true);
  assert.deepEqual(replacement.closed, {
    code: 4003,
    reason: "Browser authorization rejected",
  });
  assert.equal(peerBrowser.closed, null);
  assert.equal(device.closed, null);
  assert.ok(room.routeQuarantineRemainingMillis(ROUTE_NONCE_A) > 0);
  assert.equal(room.routeQuarantineRemainingMillis(ROUTE_NONCE_B), 0);
});

test("device response racing ahead of the browser close callback is ignored safely", () => {
  const ctx = new FakeSignalContext();
  const device = signalDeviceSocket();
  const disconnectedBrowser = signalBrowserSocket(ROUTE_NONCE_A);
  const liveBrowser = signalBrowserSocket(ROUTE_NONCE_B);
  ctx.sockets.device.push(device);
  ctx.sockets.browser.push(disconnectedBrowser, liveBrowser);
  const room = new SignalRoom(ctx, {});
  const requestId = "Closing_12345678";
  const request = JSON.stringify({
    type: "rpc_request",
    requestId,
    method: "GET",
    target: "/api/status",
    headers: {},
    bodyBase64: "",
  });
  room.webSocketMessage(disconnectedBrowser, request);

  // The WebSocket becomes unavailable before the hibernatable close event is delivered.
  disconnectedBrowser.readyState = 3;
  const response = JSON.stringify({
    type: "rpc_response",
    requestId,
    status: 200,
    contentType: "application/json",
    bodyBase64: "e30=",
  });
  room.webSocketMessage(device, response);
  assert.equal(device.closed, null);
  assert.deepEqual(liveBrowser.sent, []);
  assert.deepEqual(disconnectedBrowser.sent, []);
  assert.deepEqual(disconnectedBrowser.attachment.inFlightRequestIds, []);

  room.webSocketClose(disconnectedBrowser, 1000, "Browser left", true);
  assert.deepEqual(device.attachment.orphanedRequestIds, []);
});

test("orphan response quarantine is bounded and expires after its TTL", () => {
  const ctx = new FakeSignalContext();
  const device = signalDeviceSocket();
  ctx.sockets.device.push(device);
  const room = new SignalRoom(ctx, {});
  const now = 1_800_000_000_000;
  const requestIds = Array.from(
    { length: 49 },
    (_, index) => String(index).padStart(64, "A"),
  );

  room.addOrphanedRequestIds(device, requestIds, now);
  assert.equal(device.attachment.orphanedRequestIds.length, 48);
  assert.equal(device.attachment.orphanResponseGraceUntil, now + 60_000);
  assert.ok(
    new TextEncoder().encode(JSON.stringify(device.attachment)).byteLength < 16 * 1024,
  );
  assert.equal(room.consumeOrphanedRequestId(device, "UnknownResponse01", now), true);
  assert.equal(
    room.consumeOrphanedRequestId(device, requestIds[0], now + 60_001),
    false,
  );
});

test("orphan attachment serialization failure falls back to bounded response grace", () => {
  const ctx = new FakeSignalContext();
  const device = signalDeviceSocket();
  device.serializeAlwaysThrows = true;
  ctx.sockets.device.push(device);
  const room = new SignalRoom(ctx, {});
  const now = 1_800_000_000_000;

  assert.doesNotThrow(() => {
    room.addOrphanedRequestIds(device, ["Serialize_123456"], now);
  });
  assert.equal(room.consumeOrphanedRequestId(device, "Serialize_123456", now), true);
  assert.equal(
    room.consumeOrphanedRequestId(device, "Serialize_123456", now + 60_001),
    false,
  );
  assert.equal(device.closed, null);
});

test("bootstrap alarm deletes expired one-time slots and attempt counters", async () => {
  const storage = new MemoryStorage();
  const now = Date.now();
  await storage.put("slot", {
    roomId: ZERO_SECRET_ROOM,
    pairingEpoch: PAIRING_EPOCH_A,
    pairingGeneration: PAIRING_GENERATION_A,
    expiresAt: now - 1,
    committed: true,
    consumed: true,
  });
  await storage.put("rate", {
    windowStartedAt: now - 10 * 60 * 1000,
    totalCount: 1,
    identities: {},
  });

  await new PairingBootstrap({ storage }, {}).alarm();
  assert.equal(await storage.get("slot"), undefined);
  assert.equal(await storage.get("rate"), undefined);
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

async function signedRouteFromCookie(env, cookie) {
  const value = readCookie(cookie, ROUTE_COOKIE_NAME);
  const route = await verifyRouteCookieValue(env.BOOTSTRAP_HMAC_KEY, value);
  assert.ok(route, "expected a valid self-contained signed route cookie");
  return route;
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
  serializeAttachment(value) {
    if (this.serializeAlwaysThrows) throw new Error("attachment serialization failed");
    this.attachment = value;
  }
  send(value) { this.sent.push(value); }
  close(code, reason) {
    this.closed = { code, reason };
    this.readyState = 3;
  }
}

function signalBrowserSocket(routeNonce, overrides = {}) {
  return new FakeSocket({
    role: "browser",
    roomId: ZERO_SECRET_ROOM,
    routeNonce,
    routeIssuedAtSeconds: Math.floor(Date.now() / 1000),
    leftNotified: false,
    rpcRateTokens: 96,
    rpcRateUpdatedAt: Date.now(),
    inFlightRequestIds: [],
    ...overrides,
  });
}

function signalDeviceSocket(overrides = {}) {
  return new FakeSocket({
    role: "device",
    roomId: ZERO_SECRET_ROOM,
    leftNotified: false,
    orphanedRequestIds: [],
    orphanResponseGraceUntil: null,
    routeQuarantines: [],
    ...overrides,
  });
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
  constructor() { this.fetchCount = 0; }
  idFromName(name) { return name; }
  get() {
    return {
      fetch: async () => {
        this.fetchCount += 1;
        return new Response(null, { status: 503 });
      },
    };
  }
}

class CapturingSignalBinding {
  constructor() {
    this.fetchCount = 0;
    this.closedBrowserCount = 0;
    this.browserConnections = new Map();
  }

  idFromName(name) { return name; }

  get(id) {
    return {
      fetch: async (request) => {
        this.fetchCount += 1;
        this.lastRoomId = id;
        this.lastRole = request.headers.get("X-NavOnWeb-Role");
        this.lastRouteNonce = request.headers.get("X-NavOnWeb-Route-Nonce");
        this.lastRouteIssuedAt = request.headers.get("X-NavOnWeb-Route-Issued-At");
        this.lastLegacyBrowserRoute = request.headers.get("X-NavOnWeb-Legacy-Browser-Route");
        if (this.lastRole === "browser" &&
            request.headers.get("X-NavOnWeb-Legacy-Browser-Route") !== "1") {
          const routeNonce = request.headers.get("X-NavOnWeb-Route-Nonce");
          if (!/^[A-Za-z0-9_-]{22}$/u.test(routeNonce ?? "")) {
            return new Response(JSON.stringify({ error: "Browser pairing is required" }), {
              status: 401,
              headers: { "Content-Type": "application/json" },
            });
          }
          const connections = this.browserConnections.get(id) ?? new Set();
          if (!connections.has(routeNonce) && connections.size >= 32) {
            return new Response(JSON.stringify({
              error: "Browser signaling transport limit reached",
            }), {
              status: 409,
              headers: { "Content-Type": "application/json" },
            });
          }
          if (connections.has(routeNonce)) this.closedBrowserCount += 1;
          connections.add(routeNonce);
          this.browserConnections.set(id, connections);
        }
        return new Response("routed");
      },
    };
  }
}

function routeNonceForRotation(index) {
  if (index === 0) return ROUTE_NONCE_A;
  if (index === 1) return ROUTE_NONCE_B;
  if (index === 2) return ROUTE_NONCE_C;
  if (index === 3) return ROUTE_NONCE_D;
  return String(index).padStart(22, "0");
}
