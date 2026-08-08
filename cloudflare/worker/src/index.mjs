import {
  MAX_MESSAGE_BYTES,
  PAIRING_CODE_TTL_MILLIS,
  PROTOCOL_VERSION,
  ROUTE_COOKIE_NAME,
  ROUTE_COOKIE_TTL_SECONDS,
  authorizeDeviceRoom,
  consumeTokenBucket,
  createRouteCookieValue,
  deriveBootstrapObjectName,
  deriveRoomIdFromDeviceSecret,
  encodeBase64Url,
  isAllowedBrowserOrigin,
  isValidBootstrapSecret,
  isValidPairingCode,
  isValidRouteNonce,
  isValidRoomId,
  normalizeClientNetwork,
  parseAllowedOrigins,
  parseDeviceBearer,
  readCookie,
  validateClientMessage,
  verifyRouteCookieValue,
} from "./protocol.mjs";

const ROLE_HEADER = "X-NavOnWeb-Role";
const ROOM_HEADER = "X-NavOnWeb-Room";
const ORIGIN_HEADER = "X-NavOnWeb-Browser-Origin";
const ROUTE_NONCE_HEADER = "X-NavOnWeb-Route-Nonce";
const PAIRING_EPOCH_HEADER = "X-NavOnWeb-Pairing-Epoch";
const PAIRING_TTL_HEADER = "X-NavOnWeb-Pairing-Ttl-Millis";
const PAIRING_EXPIRES_AT_HEADER = "X-NavOnWeb-Pairing-Expires-At";
const RATE_IDEMPOTENCY_HEADER = "X-NavOnWeb-Rate-Idempotency";
const LEGACY_BROWSER_ROUTE_HEADER = "X-NavOnWeb-Legacy-Browser-Route";
const PREPARE_BROWSER_ROUTE_PATH = "/internal/prepare-browser-route";
const ACTIVATE_PAIRING_GENERATION_PATH = "/internal/activate-pairing-generation";
const CHECK_BROWSER_ROUTE_PATH = "/internal/check-browser-route";
const OPEN = 1;
const CLOSED = 3;
const BROWSER_RPC_RATE_PER_SECOND = 64;
const BROWSER_RPC_BURST = 96;
const MAX_IN_FLIGHT_REQUESTS = 16;
// Low primary limits follow a browser installation or device credential. The
// secondary network ceilings are intentionally much higher: they bound a
// rotating-identity cost attack without treating a carrier CGNAT address as a
// single customer.
const BOOTSTRAP_BROWSER_ATTEMPT_LIMIT = 12;
const BOOTSTRAP_BROWSER_NETWORK_ATTEMPT_LIMIT = 4096;
const BOOTSTRAP_DEVICE_REGISTRATION_LIMIT = 12;
const BOOTSTRAP_DEVICE_NETWORK_REGISTRATION_LIMIT = 4096;
const SIGNAL_CREDENTIAL_CONNECTION_LIMIT = 60;
// Keep the single persisted counter compact under identity-rotation attacks.
// Once full, the high network ceiling still applies even though new identities
// no longer receive a separate primary counter for that window.
const RATE_IDENTITY_CAPACITY = 256;
const BOOTSTRAP_ATTEMPT_WINDOW_MILLIS = 10 * 60 * 1000;
const BOOTSTRAP_DEVICE_PATH = "/bootstrap/device";
const BOOTSTRAP_PAIR_PATH = "/bootstrap/pair";
const BOOTSTRAP_ROUTE_STATUS_PATH = "/bootstrap/route";
const COOKIE_BROWSER_SOCKET_PATH = "/ws/browser";
const SAME_ORIGIN_ROUTE_PREFIX = "/_nw";
const BROWSER_CLIENT_COOKIE_NAME = "__Host-navonweb_client";
const BROWSER_CLIENT_ID_PATTERN = /^[A-Fa-f0-9]{32}$/u;
const PAIRING_GENERATION_PATTERN = /^[A-Za-z0-9_-]{43}$/u;
const RATE_IDEMPOTENCY_PATTERN = /^[A-Za-z0-9_-]{22}$/u;

export default {
  async fetch(request, env) {
    const url = new URL(request.url);
    const pathname = stripSameOriginRoutePrefix(url.pathname);
    if (pathname === "/healthz") {
      return jsonResponse({ status: "ok", protocolVersion: PROTOCOL_VERSION });
    }

    if (pathname === BOOTSTRAP_DEVICE_PATH) {
      return registerDevicePairingCode(request, env);
    }
    if (pathname === BOOTSTRAP_PAIR_PATH) {
      return exchangeBrowserPairingCode(request, env);
    }
    if (pathname === BOOTSTRAP_ROUTE_STATUS_PATH) {
      return checkBrowserRoute(request, env);
    }

    const match = /^\/ws\/(device|browser)\/([A-Za-z0-9_-]{22})$/u.exec(pathname);
    const cookieRoutedBrowser = pathname === COOKIE_BROWSER_SOCKET_PATH;
    if (!match && !cookieRoutedBrowser) {
      return jsonResponse({ error: "Not found" }, 404);
    }
    if (request.method !== "GET") {
      return jsonResponse({ error: "Method not allowed" }, 405, { Allow: "GET" });
    }
    if (request.headers.get("Upgrade")?.toLowerCase() !== "websocket") {
      return jsonResponse({ error: "WebSocket upgrade required" }, 426, { Upgrade: "websocket" });
    }

    const role = cookieRoutedBrowser ? "browser" : match[1];
    if (role === "browser" && !cookieRoutedBrowser && env.ALLOW_LEGACY_BROWSER_ROOM_ROUTE !== "true") {
      // Production uses the signed, cookie-routed endpoint. Keeping the legacy
      // room-id URL opt-in prevents public OSS clients from bypassing pairing.
      return jsonResponse({ error: "Not found" }, 404);
    }
    let roomId = cookieRoutedBrowser ? null : match[2];
    let browserRouteNonce = null;
    if (cookieRoutedBrowser) {
      if (!isValidBootstrapSecret(env.BOOTSTRAP_HMAC_KEY)) {
        return jsonResponse({ error: "Pairing bootstrap is unavailable" }, 503);
      }
      const routeValue = readCookie(request.headers.get("Cookie"), ROUTE_COOKIE_NAME);
      const route = await verifyRouteCookieValue(env.BOOTSTRAP_HMAC_KEY, routeValue);
      if (!route) {
        return jsonResponse(
          { error: "Browser pairing is required" },
          401,
          { "WWW-Authenticate": 'NavOnWeb-Pairing realm="browser"' },
        );
      }
      roomId = route.roomId;
      browserRouteNonce = route.routeNonce;
    }
    if (!isValidRoomId(roomId)) {
      return jsonResponse({ error: "Invalid room id" }, 400);
    }

    let browserOrigin = "";
    if (role === "device") {
      const authorization = await authorizeDeviceRoom(
        roomId,
        request.headers.get("Authorization"),
      );
      if (!authorization.ok) {
        return jsonResponse(
          { error: "Device authorization failed" },
          401,
          { "WWW-Authenticate": 'Bearer realm="navonweb-device"' },
        );
      }
    } else {
      const allowedOrigins = parseAllowedOrigins(env.ALLOWED_BROWSER_ORIGINS);
      if (allowedOrigins.size === 0) {
        return jsonResponse({ error: "Browser origin allowlist is not configured" }, 503);
      }
      browserOrigin = request.headers.get("Origin") ?? "";
      if (!isAllowedBrowserOrigin(browserOrigin, env.ALLOWED_BROWSER_ORIGINS)) {
        return jsonResponse({ error: "Browser origin is not allowed" }, 403);
      }
    }

    const headers = new Headers(request.headers);
    headers.delete("Authorization");
    headers.delete(ROUTE_NONCE_HEADER);
    headers.delete(LEGACY_BROWSER_ROUTE_HEADER);
    headers.set(ROLE_HEADER, role);
    headers.set(ROOM_HEADER, roomId);
    if (browserOrigin) {
      headers.set(ORIGIN_HEADER, browserOrigin);
    } else {
      headers.delete(ORIGIN_HEADER);
    }
    if (role === "browser") {
      if (cookieRoutedBrowser) {
        headers.set(ROUTE_NONCE_HEADER, browserRouteNonce);
      } else {
        headers.set(LEGACY_BROWSER_ROUTE_HEADER, "1");
      }
    }

    const objectId = env.SIGNAL_ROOMS.idFromName(roomId);
    const room = env.SIGNAL_ROOMS.get(objectId);
    return room.fetch(new Request(request, { headers }));
  },
};

async function checkBrowserRoute(request, env) {
  const responseHeaders = {
    "Cache-Control": "no-store",
    "Cross-Origin-Resource-Policy": "same-origin",
    "X-Content-Type-Options": "nosniff",
  };
  if (request.method !== "GET") {
    return emptyResponse(405, { ...responseHeaders, Allow: "GET" });
  }
  const fetchSite = request.headers.get("Sec-Fetch-Site");
  if (fetchSite && fetchSite !== "same-origin" && fetchSite !== "none") {
    return emptyResponse(403, responseHeaders);
  }
  if (!isValidBootstrapSecret(env.BOOTSTRAP_HMAC_KEY) || !env.SIGNAL_ROOMS) {
    return emptyResponse(503, responseHeaders);
  }
  const routeValue = readCookie(request.headers.get("Cookie"), ROUTE_COOKIE_NAME);
  const route = await verifyRouteCookieValue(env.BOOTSTRAP_HMAC_KEY, routeValue);
  if (!route) return emptyResponse(401, responseHeaders);

  try {
    const room = env.SIGNAL_ROOMS.get(env.SIGNAL_ROOMS.idFromName(route.roomId));
    const routeResponse = await room.fetch(new Request(
      `https://signal.internal${CHECK_BROWSER_ROUTE_PATH}`,
      {
        method: "GET",
        headers: {
          [ROOM_HEADER]: route.roomId,
          [ROUTE_NONCE_HEADER]: route.routeNonce,
        },
      },
    ));
    if (routeResponse.status === 204) return emptyResponse(204, responseHeaders);
    if (routeResponse.status === 401) return emptyResponse(401, responseHeaders);
    return emptyResponse(503, responseHeaders);
  } catch {
    return emptyResponse(503, responseHeaders);
  }
}

function stripSameOriginRoutePrefix(pathname) {
  if (pathname === SAME_ORIGIN_ROUTE_PREFIX) return "/";
  return pathname.startsWith(`${SAME_ORIGIN_ROUTE_PREFIX}/`)
    ? pathname.slice(SAME_ORIGIN_ROUTE_PREFIX.length)
    : pathname;
}

async function consumeScopedRateLimit(env, limit) {
  if (!isValidBootstrapSecret(env.BOOTSTRAP_HMAC_KEY) || !env.PAIRING_BOOTSTRAP) {
    return 503;
  }
  try {
    // One network-scoped DO enforces both the low identity budget and the high
    // CGNAT budget. This avoids doubling DO requests/writes for every action.
    const name = await deriveBootstrapObjectName(
      env.BOOTSTRAP_HMAC_KEY,
      limit.namespace,
      limit.network,
    );
    const object = env.PAIRING_BOOTSTRAP.get(env.PAIRING_BOOTSTRAP.idFromName(name));
    const headers = new Headers({ "X-NavOnWeb-Rate-Identity": limit.identity });
    if (limit.idempotencyKey) {
      headers.set(RATE_IDEMPOTENCY_HEADER, limit.idempotencyKey);
    }
    const response = await object.fetch(new Request(
      `https://bootstrap.internal${limit.path}`,
      {
        method: "POST",
        headers,
      },
    ));
    return response.status === 204 || response.status === 429 ? response.status : 503;
  } catch {
    return 503;
  }
}

function readOrCreateBrowserClient(request) {
  const existing = readCookie(request.headers.get("Cookie"), BROWSER_CLIENT_COOKIE_NAME);
  const id = BROWSER_CLIENT_ID_PATTERN.test(existing ?? "")
    ? existing.toLowerCase()
    : randomBrowserClientId();
  return {
    id,
    cookie: [
      `${BROWSER_CLIENT_COOKIE_NAME}=${id}`,
      "Path=/",
      `Max-Age=${ROUTE_COOKIE_TTL_SECONDS}`,
      "HttpOnly",
      "Secure",
      "SameSite=Strict",
    ].join("; "),
  };
}

function randomBrowserClientId() {
  const bytes = new Uint8Array(16);
  globalThis.crypto.getRandomValues(bytes);
  return Array.from(bytes, (value) => value.toString(16).padStart(2, "0")).join("");
}

function randomRouteNonce() {
  const bytes = new Uint8Array(16);
  globalThis.crypto.getRandomValues(bytes);
  return encodeBase64Url(bytes);
}

async function registerDevicePairingCode(request, env) {
  if (request.method !== "POST") {
    return jsonResponse({ error: "Method not allowed" }, 405, { Allow: "POST" });
  }
  if (!isValidBootstrapSecret(env.BOOTSTRAP_HMAC_KEY) || !env.PAIRING_BOOTSTRAP) {
    return jsonResponse({ error: "Pairing bootstrap is unavailable" }, 503);
  }
  const deviceSecret = parseDeviceBearer(request.headers.get("Authorization"));
  if (!deviceSecret) {
    return jsonResponse(
      { error: "Device authorization failed" },
      401,
      { "WWW-Authenticate": 'Bearer realm="navonweb-device"' },
    );
  }
  const network = normalizeClientNetwork(request.headers.get("CF-Connecting-IP"));
  if (!network) return jsonResponse({ error: "Pairing bootstrap is unavailable" }, 400);

  const pairingCode = await readBoundedPairingCode(request);
  if (!isValidPairingCode(pairingCode)) {
    return jsonResponse({ error: "Invalid pairing registration" }, 400);
  }
  const pairingEpoch = parsePairingEpochHeader(request.headers.get(PAIRING_EPOCH_HEADER));
  if (!pairingEpoch) {
    return jsonResponse({ error: "Invalid pairing registration" }, 400);
  }
  const pairingTtlMillis = parsePairingTtlHeader(request.headers.get(PAIRING_TTL_HEADER));
  if (!pairingTtlMillis) {
    return jsonResponse({ error: "Invalid pairing registration" }, 400);
  }
  let roomId;
  try {
    roomId = await deriveRoomIdFromDeviceSecret(deviceSecret);
  } catch {
    return jsonResponse({ error: "Device authorization failed" }, 401);
  }

  let pairingGeneration;
  try {
    pairingGeneration = await deriveBootstrapObjectName(
      env.BOOTSTRAP_HMAC_KEY,
      "pairing-generation",
      roomId,
      String(pairingEpoch),
      pairingCode,
    );
  } catch {
    return jsonResponse({ error: "Pairing bootstrap is unavailable" }, 503);
  }
  if (!isValidPairingGeneration(pairingGeneration)) {
    return jsonResponse({ error: "Pairing bootstrap is unavailable" }, 503);
  }

  const registrationRate = await consumeScopedRateLimit(env, {
    namespace: "device-registration-rate",
    network,
    identity: roomId,
    // A compact 132-bit HMAC prefix makes retries idempotent inside the rate
    // Durable Object without storing the public code or an unbounded key.
    idempotencyKey: pairingGeneration.slice(0, 22),
    path: "/register-attempt",
  });
  if (registrationRate === 429) {
    return jsonResponse(
      { error: "Pairing registration temporarily unavailable" },
      429,
      { "Retry-After": "600" },
    );
  }
  if (registrationRate !== 204) {
    return jsonResponse({ error: "Pairing bootstrap is unavailable" }, 503);
  }

  const objectName = await deriveBootstrapObjectName(
    env.BOOTSTRAP_HMAC_KEY,
    "slot",
    network,
    pairingCode,
  );
  const slot = env.PAIRING_BOOTSTRAP.get(env.PAIRING_BOOTSTRAP.idFromName(objectName));
  const requestedSlot = {
    roomId,
    pairingEpoch,
    pairingGeneration,
    // This duration comes from the phone's fixed monotonic Gate deadline.
    // Wall-clock skew is irrelevant and delayed first registration receives
    // only the remaining lifetime, never a fresh ten-minute window.
    expiresAt: Date.now() + pairingTtlMillis,
  };
  const reserveResponse = await slot.fetch(new Request("https://bootstrap.internal/reserve", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(requestedSlot),
  }));
  if (reserveResponse.status === 409) {
    return jsonResponse({ error: "Pairing registration unavailable" }, 409);
  }
  if (reserveResponse.status !== 204) {
    return jsonResponse({ error: "Pairing bootstrap is unavailable" }, 503);
  }
  const pairingExpiresAt = parsePairingExpiresAtHeader(
    reserveResponse.headers.get(PAIRING_EXPIRES_AT_HEADER),
  );
  if (!pairingExpiresAt || pairingExpiresAt <= Date.now()) {
    return jsonResponse({ error: "Pairing registration unavailable" }, 409);
  }
  const canonicalSlot = {
    roomId,
    pairingEpoch,
    pairingGeneration,
    expiresAt: pairingExpiresAt,
  };

  const roomObjectId = env.SIGNAL_ROOMS.idFromName(roomId);
  const room = env.SIGNAL_ROOMS.get(roomObjectId);
  const activationResponse = await room.fetch(new Request(
    `https://signal.internal${ACTIVATE_PAIRING_GENERATION_PATH}`,
    {
      method: "POST",
      headers: {
        [ROOM_HEADER]: roomId,
        "Content-Type": "application/json",
      },
      body: JSON.stringify({
        pairingEpoch,
        pairingGeneration,
        pairingExpiresAt,
      }),
    },
  )).catch(() => null);
  if (activationResponse?.status === 409) {
    return jsonResponse({ error: "Pairing registration unavailable" }, 409);
  }
  if (!activationResponse || activationResponse.status !== 204) {
    return jsonResponse({ error: "Pairing bootstrap is unavailable" }, 503);
  }

  const commitResponse = await slot.fetch(new Request("https://bootstrap.internal/commit", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(canonicalSlot),
  })).catch(() => null);
  if (!commitResponse || commitResponse.status !== 204) {
    return jsonResponse({ error: "Pairing bootstrap is unavailable" }, 503);
  }
  return emptyResponse(204);
}

async function exchangeBrowserPairingCode(request, env) {
  const origin = request.headers.get("Origin") ?? "";
  if (!isAllowedBrowserOrigin(origin, env.ALLOWED_BROWSER_ORIGINS)) {
    return jsonResponse({ error: "Browser origin is not allowed" }, 403);
  }
  if (request.method === "OPTIONS") {
    return emptyResponse(204, browserCorsHeaders(origin, {
      "Access-Control-Allow-Headers": "Content-Type",
      "Access-Control-Allow-Methods": "POST, OPTIONS",
      "Access-Control-Max-Age": "600",
    }));
  }
  if (request.method !== "POST") {
    return jsonResponse(
      { error: "Method not allowed" },
      405,
      { ...browserCorsHeaders(origin), Allow: "POST, OPTIONS" },
    );
  }
  if (!isValidBootstrapSecret(env.BOOTSTRAP_HMAC_KEY) || !env.PAIRING_BOOTSTRAP) {
    return jsonResponse(
      { error: "Pairing bootstrap is unavailable" },
      503,
      browserCorsHeaders(origin),
    );
  }
  const network = normalizeClientNetwork(request.headers.get("CF-Connecting-IP"));
  if (!network) {
    return jsonResponse({ error: "Pairing unavailable" }, 404, browserCorsHeaders(origin));
  }
  const browserClient = readOrCreateBrowserClient(request);
  const responseHeaders = (extraHeaders = undefined) => browserCorsHeaders(origin, {
    "Set-Cookie": browserClient.cookie,
    ...extraHeaders,
  });

  const pairingCode = await readBoundedPairingCode(request);
  if (!isValidPairingCode(pairingCode)) {
    return jsonResponse({ error: "Pairing unavailable" }, 404, responseHeaders());
  }

  const attemptRate = await consumeScopedRateLimit(env, {
    namespace: "browser-pairing-rate",
    network,
    identity: browserClient.id,
    path: "/attempt",
  });
  if (attemptRate === 429) {
    return jsonResponse(
      { error: "Pairing temporarily unavailable" },
      429,
      responseHeaders({ "Retry-After": "600" }),
    );
  }
  if (attemptRate !== 204) {
    return jsonResponse(
      { error: "Pairing bootstrap is unavailable" },
      503,
      responseHeaders(),
    );
  }
  const slotName = await deriveBootstrapObjectName(
    env.BOOTSTRAP_HMAC_KEY,
    "slot",
    network,
    pairingCode,
  );
  const slot = env.PAIRING_BOOTSTRAP.get(env.PAIRING_BOOTSTRAP.idFromName(slotName));
  const slotResponse = await slot.fetch(new Request("https://bootstrap.internal/consume", {
    method: "POST",
  }));
  if (slotResponse.status !== 200) {
    return jsonResponse({ error: "Pairing unavailable" }, 404, responseHeaders());
  }
  const slotBody = await slotResponse.json().catch(() => null);
  if (!isValidRoomId(slotBody?.roomId) ||
      !isValidPairingEpoch(slotBody?.pairingEpoch) ||
      !isValidPairingGeneration(slotBody?.pairingGeneration) ||
      !Number.isSafeInteger(slotBody?.expiresAt)) {
    return jsonResponse(
      { error: "Pairing bootstrap is unavailable" },
      503,
      responseHeaders(),
    );
  }

  const roomObjectId = env.SIGNAL_ROOMS.idFromName(slotBody.roomId);
  const room = env.SIGNAL_ROOMS.get(roomObjectId);
  const prepareResponse = await room.fetch(new Request(
    `https://signal.internal${PREPARE_BROWSER_ROUTE_PATH}`,
    {
      method: "POST",
      headers: {
        [ROOM_HEADER]: slotBody.roomId,
        "Content-Type": "application/json",
      },
      body: JSON.stringify({
        pairingEpoch: slotBody.pairingEpoch,
        pairingGeneration: slotBody.pairingGeneration,
        pairingExpiresAt: slotBody.expiresAt,
      }),
    },
  )).catch(() => null);
  if (prepareResponse?.status === 409) {
    return jsonResponse({ error: "Pairing unavailable" }, 404, responseHeaders());
  }
  if (!prepareResponse || prepareResponse.status !== 200) {
    return jsonResponse(
      { error: "Pairing bootstrap is unavailable" },
      503,
      responseHeaders(),
    );
  }
  const prepareBody = await prepareResponse.json().catch(() => null);
  if (!isValidRouteNonce(prepareBody?.routeNonce)) {
    return jsonResponse(
      { error: "Pairing bootstrap is unavailable" },
      503,
      responseHeaders(),
    );
  }
  let routeValue;
  try {
    routeValue = await createRouteCookieValue(
      env.BOOTSTRAP_HMAC_KEY,
      slotBody.roomId,
      prepareBody.routeNonce,
    );
  } catch {
    // The slot is already consumed, but the pending route is not promoted until
    // its WebSocket arrives. A response/cookie failure therefore preserves the
    // current browser session; a fresh code can replace the pending route.
    return jsonResponse(
      { error: "Pairing bootstrap is unavailable" },
      503,
      responseHeaders(),
    );
  }
  const cookie = [
    `${ROUTE_COOKIE_NAME}=${routeValue}`,
    "Path=/",
    `Max-Age=${ROUTE_COOKIE_TTL_SECONDS}`,
    "HttpOnly",
    "Secure",
    "SameSite=Strict",
  ].join("; ");
  const successHeaders = new Headers(browserCorsHeaders(origin));
  successHeaders.append("Set-Cookie", browserClient.cookie);
  successHeaders.append("Set-Cookie", cookie);
  return emptyResponse(204, successHeaders);
}

/** Atomic, opaque pairing-code slots and keyed attempt counters. */
export class PairingBootstrap {
  constructor(ctx, env) {
    this.ctx = ctx;
    this.env = env;
  }

  async fetch(request) {
    const url = new URL(request.url);
    if (request.method !== "POST") return emptyResponse(405, { Allow: "POST" });
    if (url.pathname === "/reserve") return this.reserve(request);
    if (url.pathname === "/commit") return this.commit(request);
    if (url.pathname === "/consume") return this.consume();
    if (url.pathname === "/attempt") return this.consumeAttempt(
      request,
      BOOTSTRAP_BROWSER_ATTEMPT_LIMIT,
      BOOTSTRAP_BROWSER_NETWORK_ATTEMPT_LIMIT,
    );
    if (url.pathname === "/register-attempt") {
      return this.consumeAttempt(
        request,
        BOOTSTRAP_DEVICE_REGISTRATION_LIMIT,
        BOOTSTRAP_DEVICE_NETWORK_REGISTRATION_LIMIT,
        true,
      );
    }
    return emptyResponse(404);
  }

  async reserve(request) {
    const body = await request.json().catch(() => null);
    if (!isValidRoomId(body?.roomId) ||
        !isValidPairingEpoch(body?.pairingEpoch) ||
        !isValidPairingGeneration(body?.pairingGeneration) ||
        !Number.isSafeInteger(body?.expiresAt)) {
      return emptyResponse(400);
    }
    const now = Date.now();
    if (body.expiresAt <= now || body.expiresAt > now + PAIRING_CODE_TTL_MILLIS) {
      return emptyResponse(400);
    }
    const result = await this.ctx.storage.transaction(async (storage) => {
      const current = await storage.get("slot");
      if (current && current.expiresAt > now && current.consumed === true) {
        return { accepted: false, expiresAt: null };
      }
      if (current && current.expiresAt > now &&
          (current.roomId !== body.roomId ||
           current.pairingEpoch !== body.pairingEpoch ||
           current.pairingGeneration !== body.pairingGeneration)) {
        return { accepted: false, expiresAt: null };
      }
      if (current && current.expiresAt > now &&
          current.roomId === body.roomId &&
          current.pairingEpoch === body.pairingEpoch &&
          current.pairingGeneration === body.pairingGeneration) {
        // Registration retries are idempotent and never extend the code TTL.
        return { accepted: true, expiresAt: current.expiresAt };
      }
      await storage.put("slot", {
        roomId: body.roomId,
        pairingEpoch: body.pairingEpoch,
        pairingGeneration: body.pairingGeneration,
        expiresAt: body.expiresAt,
        committed: false,
        consumed: false,
      });
      return { accepted: true, expiresAt: body.expiresAt };
    });
    if (!result.accepted) return emptyResponse(409);
    await this.ctx.storage.setAlarm(result.expiresAt);
    return emptyResponse(204, { [PAIRING_EXPIRES_AT_HEADER]: String(result.expiresAt) });
  }

  async commit(request) {
    const body = await request.json().catch(() => null);
    if (!isValidRoomId(body?.roomId) ||
        !isValidPairingEpoch(body?.pairingEpoch) ||
        !isValidPairingGeneration(body?.pairingGeneration) ||
        !Number.isSafeInteger(body?.expiresAt)) {
      return emptyResponse(400);
    }
    const now = Date.now();
    const committed = await this.ctx.storage.transaction(async (storage) => {
      const current = await storage.get("slot");
      if (!current || current.expiresAt <= now ||
          current.consumed === true ||
          current.roomId !== body.roomId ||
          current.pairingEpoch !== body.pairingEpoch ||
          current.pairingGeneration !== body.pairingGeneration ||
          current.expiresAt !== body.expiresAt) {
        if (current?.expiresAt <= now) await storage.delete("slot");
        return false;
      }
      if (!current.committed) await storage.put("slot", { ...current, committed: true });
      return true;
    });
    return emptyResponse(committed ? 204 : 409);
  }

  async consume() {
    const now = Date.now();
    const slotData = await this.ctx.storage.transaction(async (storage) => {
      const current = await storage.get("slot");
      if (!current || current.expiresAt <= now || !isValidRoomId(current.roomId)) {
        await storage.delete("slot");
        return null;
      }
      if (current.consumed === true ||
          !current.committed ||
          !isValidPairingEpoch(current.pairingEpoch) ||
          !isValidPairingGeneration(current.pairingGeneration)) {
        return null;
      }
      // Keep a tombstone until the original expiry. Otherwise a lost response
      // or a device WebSocket reconnect could reserve and replay the same code.
      await storage.put("slot", { ...current, consumed: true });
      return {
        roomId: current.roomId,
        pairingEpoch: current.pairingEpoch,
        pairingGeneration: current.pairingGeneration,
        expiresAt: current.expiresAt,
      };
    });
    return slotData ? jsonResponse(slotData) : emptyResponse(404);
  }

  async consumeAttempt(request, identityLimit, networkLimit, deduplicate = false) {
    const identity = request.headers.get("X-NavOnWeb-Rate-Identity") ?? "";
    if (!/^[A-Za-z0-9_-]{16,64}$/u.test(identity)) return emptyResponse(400);
    const idempotencyKey = request.headers.get(RATE_IDEMPOTENCY_HEADER) ?? "";
    if (deduplicate && !RATE_IDEMPOTENCY_PATTERN.test(idempotencyKey)) {
      return emptyResponse(400);
    }
    const now = Date.now();
    const allowed = await this.ctx.storage.transaction(async (storage) => {
      const current = await storage.get("rate");
      const windowStartedAt = Number.isSafeInteger(current?.windowStartedAt)
        ? current.windowStartedAt
        : now;
      const withinWindow = now - windowStartedAt < BOOTSTRAP_ATTEMPT_WINDOW_MILLIS;
      const totalCount = withinWindow && Number.isSafeInteger(current?.totalCount)
        ? current.totalCount
        : 0;
      const identities = withinWindow && isPlainRecord(current?.identities)
        ? { ...current.identities }
        : {};
      const publications = withinWindow && isPlainRecord(current?.publications)
        ? { ...current.publications }
        : {};
      const recentPublications = deduplicate && Array.isArray(publications[identity])
        ? publications[identity]
          .filter((entry) => RATE_IDEMPOTENCY_PATTERN.test(entry))
          .slice(-identityLimit)
        : [];
      if (totalCount >= networkLimit) return false;
      if (deduplicate && recentPublications.includes(idempotencyKey)) {
        // Exact retries do not consume another distinct-publication credential
        // slot, but every request still consumes the bounded CGNAT/replay
        // ceiling so a leaked credential cannot trigger unlimited DO work.
        const next = {
          windowStartedAt: withinWindow ? windowStartedAt : now,
          totalCount: totalCount + 1,
          identities,
          publications,
        };
        await storage.put("rate", next);
        await storage.setAlarm(next.windowStartedAt + BOOTSTRAP_ATTEMPT_WINDOW_MILLIS);
        return true;
      }
      const legacyIdentityCount = Number.isSafeInteger(identities[identity])
        ? identities[identity]
        : 0;
      const identityCount = deduplicate
        ? Math.max(legacyIdentityCount, recentPublications.length)
        : legacyIdentityCount;
      if (identityCount >= identityLimit) return false;
      if (identityCount > 0 || Object.keys(identities).length < RATE_IDENTITY_CAPACITY) {
        identities[identity] = identityCount + 1;
        if (deduplicate) {
          publications[identity] = [...recentPublications, idempotencyKey].slice(-identityLimit);
        }
      }
      const next = {
        windowStartedAt: withinWindow ? windowStartedAt : now,
        totalCount: totalCount + 1,
        identities,
        ...(deduplicate ? { publications } : {}),
      };
      await storage.put("rate", next);
      await storage.setAlarm(next.windowStartedAt + BOOTSTRAP_ATTEMPT_WINDOW_MILLIS);
      return true;
    });
    return emptyResponse(allowed ? 204 : 429);
  }

  async alarm() {
    const now = Date.now();
    const slot = await this.ctx.storage.get("slot");
    if (slot?.expiresAt <= now) await this.ctx.storage.delete("slot");
    const rate = await this.ctx.storage.get("rate");
    if (rate?.windowStartedAt + BOOTSTRAP_ATTEMPT_WINDOW_MILLIS <= now) {
      await this.ctx.storage.delete("rate");
    }
  }
}

export class SignalRoom {
  constructor(ctx, env) {
    this.ctx = ctx;
    this.env = env;
    this.connectionWindows = new Map();
  }

  async fetch(request) {
    const url = new URL(request.url);
    if (url.pathname === PREPARE_BROWSER_ROUTE_PATH) {
      return this.prepareBrowserRoute(request);
    }
    if (url.pathname === ACTIVATE_PAIRING_GENERATION_PATH) {
      return this.activatePairingGeneration(request);
    }
    if (url.pathname === CHECK_BROWSER_ROUTE_PATH) {
      return this.checkBrowserRoute(request);
    }
    if (request.headers.get("Upgrade")?.toLowerCase() !== "websocket") {
      return jsonResponse({ error: "WebSocket upgrade required" }, 426);
    }

    const role = request.headers.get(ROLE_HEADER);
    const roomId = request.headers.get(ROOM_HEADER);
    if ((role !== "browser" && role !== "device") || !isValidRoomId(roomId)) {
      return jsonResponse({ error: "Invalid internal signaling request" }, 400);
    }

    let browserRouteStatus = role === "browser" ? "legacy" : null;
    if (role === "browser" && request.headers.get(LEGACY_BROWSER_ROUTE_HEADER) !== "1") {
      const routeNonce = request.headers.get(ROUTE_NONCE_HEADER);
      browserRouteStatus = isValidRouteNonce(routeNonce)
        ? await this.classifyBrowserRoute(routeNonce)
        : null;
      if (!browserRouteStatus) {
        return jsonResponse(
          { error: "Browser pairing is required" },
          401,
          { "WWW-Authenticate": 'NavOnWeb-Pairing realm="browser"' },
        );
      }
    }

    // A pending route is backed by a freshly consumed one-time code. Exempt it
    // from the old route's reconnect bucket so an old browser cannot block the
    // handoff by intentionally exhausting that bucket.
    if (browserRouteStatus !== "pending" && !this.consumeConnectionAdmission(role)) {
      return jsonResponse(
        { error: "Signaling temporarily unavailable" },
        429,
        { "Retry-After": "30" },
      );
    }

    if (role === "browser" && !this.hasOpenPeer("device")) {
      return jsonResponse({ error: "The device is not connected" }, 409);
    }
    const reconnectSockets = browserRouteStatus === "pending"
      ? []
      : this.reconnectSockets(role, request.headers.get(ROUTE_NONCE_HEADER));
    if (reconnectSockets === null) {
      return jsonResponse({ error: `A ${role} is already connected` }, 409);
    }

    const pair = new WebSocketPair();
    const [client, server] = Object.values(pair);
    if (browserRouteStatus === "pending") {
      const routeNonce = request.headers.get(ROUTE_NONCE_HEADER);
      if (!(await this.promoteBrowserRoute(routeNonce))) {
        return jsonResponse(
          { error: "Browser pairing is required" },
          401,
          { "WWW-Authenticate": 'NavOnWeb-Pairing realm="browser"' },
        );
      }
    }
    this.ctx.acceptWebSocket(server, [role]);
    server.serializeAttachment({
      role,
      roomId,
      routeNonce: role === "browser" ? request.headers.get(ROUTE_NONCE_HEADER) : undefined,
      connectedAt: Date.now(),
      leftNotified: false,
      rpcRateTokens: role === "browser" ? BROWSER_RPC_BURST : undefined,
      rpcRateUpdatedAt: role === "browser" ? Date.now() : undefined,
      inFlightRequestIds: role === "browser" ? [] : undefined,
    });
    this.supersedeReconnectSockets(role, reconnectSockets);

    const peerRole = oppositeRole(role);
    const peerConnected = this.hasOpenPeer(peerRole, server);
    this.sendSystem(server, {
      type: "ready",
      protocolVersion: PROTOCOL_VERSION,
      role,
      roomId,
      peerConnected,
      maxMessageBytes: MAX_MESSAGE_BYTES,
      maxInFlightRequests: MAX_IN_FLIGHT_REQUESTS,
      browserRpcRatePerSecond: BROWSER_RPC_RATE_PER_SECOND,
      at: Date.now(),
    });

    for (const peer of this.openSockets(peerRole, server)) {
      this.sendSystem(peer, {
        type: "peer_joined",
        peerRole: role,
        at: Date.now(),
      });
    }

    return new Response(null, { status: 101, webSocket: client });
  }

  async checkBrowserRoute(request) {
    if (request.method !== "GET") return emptyResponse(405, { Allow: "GET" });
    const roomId = request.headers.get(ROOM_HEADER);
    const routeNonce = request.headers.get(ROUTE_NONCE_HEADER);
    if (!isValidRoomId(roomId) || !isValidRouteNonce(routeNonce)) {
      return emptyResponse(401);
    }
    return await this.classifyBrowserRoute(routeNonce)
      ? emptyResponse(204)
      : emptyResponse(401);
  }

  async prepareBrowserRoute(request) {
    if (request.method !== "POST") return emptyResponse(405, { Allow: "POST" });
    const roomId = request.headers.get(ROOM_HEADER);
    if (!isValidRoomId(roomId)) return emptyResponse(400);
    const body = await request.json().catch(() => null);
    if (!isValidPairingEpoch(body?.pairingEpoch) ||
        !isValidPairingGeneration(body?.pairingGeneration) ||
        !Number.isSafeInteger(body?.pairingExpiresAt)) return emptyResponse(400);

    const routeNonce = randomRouteNonce();
    const now = Date.now();
    if (body.pairingExpiresAt <= now) return emptyResponse(409);
    if (body.pairingExpiresAt > now + PAIRING_CODE_TTL_MILLIS) return emptyResponse(400);
    const accepted = await this.ctx.storage.transaction(async (storage) => {
      const current = normalizeBrowserRouteState(await storage.get("browserRoute"));
      if (current.activePairingEpoch !== body.pairingEpoch ||
          current.activePairingGeneration !== body.pairingGeneration ||
          current.activePairingExpiresAt !== body.pairingExpiresAt) return false;
      await storage.put("browserRoute", {
        activePairingEpoch: current.activePairingEpoch,
        activePairingGeneration: current.activePairingGeneration,
        activePairingExpiresAt: current.activePairingExpiresAt,
        currentRouteNonce: current.currentRouteNonce,
        currentSince: current.currentSince,
        pendingRouteNonce: routeNonce,
        // A pending cookie can never outlive the one-time code that authorized
        // it. Once promoted, the current browser session remains independent.
        pendingExpiresAt: body.pairingExpiresAt,
      });
      return true;
    });
    return accepted ? jsonResponse({ routeNonce }) : emptyResponse(409);
  }

  async activatePairingGeneration(request) {
    if (request.method !== "POST") return emptyResponse(405, { Allow: "POST" });
    const roomId = request.headers.get(ROOM_HEADER);
    if (!isValidRoomId(roomId)) return emptyResponse(400);
    const body = await request.json().catch(() => null);
    if (!isValidPairingEpoch(body?.pairingEpoch) ||
        !isValidPairingGeneration(body?.pairingGeneration) ||
        !Number.isSafeInteger(body?.pairingExpiresAt)) return emptyResponse(400);
    const now = Date.now();
    if (body.pairingExpiresAt <= now) return emptyResponse(409);
    if (body.pairingExpiresAt > now + PAIRING_CODE_TTL_MILLIS) return emptyResponse(400);

    const accepted = await this.ctx.storage.transaction(async (storage) => {
      const current = normalizeBrowserRouteState(await storage.get("browserRoute"));
      if (current.activePairingEpoch !== null) {
        if (body.pairingEpoch < current.activePairingEpoch) return false;
        if (body.pairingEpoch === current.activePairingEpoch) {
          // Exact publication retries are idempotent and retain any pending
          // browser handoff. Reusing an epoch for different content fails closed.
          return current.activePairingGeneration === body.pairingGeneration &&
            current.activePairingExpiresAt === body.pairingExpiresAt;
        }
      }
      await storage.put("browserRoute", {
        activePairingEpoch: body.pairingEpoch,
        activePairingGeneration: body.pairingGeneration,
        activePairingExpiresAt: body.pairingExpiresAt,
        currentRouteNonce: current.currentRouteNonce,
        currentSince: current.currentSince,
        pendingRouteNonce: null,
        pendingExpiresAt: null,
      });
      return true;
    });
    return emptyResponse(accepted ? 204 : 409);
  }

  async classifyBrowserRoute(routeNonce, now = Date.now()) {
    if (!isValidRouteNonce(routeNonce)) return null;
    const current = normalizeBrowserRouteState(await this.ctx.storage.get("browserRoute"));
    if (current.currentRouteNonce === routeNonce) return "current";
    if (current.pendingRouteNonce === routeNonce && current.pendingExpiresAt > now) {
      return "pending";
    }
    return null;
  }

  async promoteBrowserRoute(routeNonce, now = Date.now()) {
    if (!isValidRouteNonce(routeNonce)) return false;
    const promoted = await this.ctx.storage.transaction(async (storage) => {
      const current = normalizeBrowserRouteState(await storage.get("browserRoute"));
      if (current.pendingRouteNonce === routeNonce && current.pendingExpiresAt > now) {
        await storage.put("browserRoute", {
          activePairingEpoch: current.activePairingEpoch,
          activePairingGeneration: current.activePairingGeneration,
          activePairingExpiresAt: current.activePairingExpiresAt,
          currentRouteNonce: routeNonce,
          currentSince: now,
          pendingRouteNonce: null,
          pendingExpiresAt: null,
        });
        return true;
      }
      return false;
    });
    if (promoted) this.revokeBrowserSockets();
    return promoted;
  }

  revokeBrowserSockets() {
    // Promotion is already durable before sockets are revoked. Marking their
    // attachments first blocks messages racing the close handshake.
    for (const socket of this.ctx.getWebSockets("browser")) {
      const attachment = socket.deserializeAttachment();
      if (attachment) socket.serializeAttachment({ ...attachment, revoked: true });
      this.notifyPeerLeft(socket, 4001, "Browser pairing replaced", true);
      this.closeSocket(socket, 4001, "Browser pairing replaced");
    }
  }

  consumeConnectionAdmission(role, now = Date.now()) {
    const current = this.connectionWindows.get(role);
    const withinWindow = current &&
      now - current.windowStartedAt < BOOTSTRAP_ATTEMPT_WINDOW_MILLIS;
    const count = withinWindow ? current.count : 0;
    if (count >= SIGNAL_CREDENTIAL_CONNECTION_LIMIT) return false;
    this.connectionWindows.set(role, {
      windowStartedAt: withinWindow ? current.windowStartedAt : now,
      count: count + 1,
    });
    return true;
  }

  webSocketMessage(socket, message) {
    const attachment = socket.deserializeAttachment();
    if (!attachment || (attachment.role !== "browser" && attachment.role !== "device")) {
      this.closeSocket(socket, 1011, "Missing connection state");
      return;
    }
    if (attachment.revoked) {
      this.closeSocket(socket, 4001, "Browser pairing replaced");
      return;
    }

    const validation = validateClientMessage(message, attachment.role);
    if (!validation.ok) {
      this.closeSocket(socket, validation.closeCode, validation.reason);
      return;
    }

    const peerRole = oppositeRole(attachment.role);
    const peers = this.openSockets(peerRole, socket);
    if (peers.length !== 1) {
      this.closeSocket(socket, 1008, "Peer is not connected");
      return;
    }

    const requestId = validation.envelope.requestId;
    if (attachment.role === "browser" && validation.envelope.type === "rpc_request") {
      const reservation = this.reserveBrowserRequest(socket, attachment, requestId);
      if (!reservation.ok) {
        if (reservation.retryable) {
          this.sendRpcError(socket, requestId, 429, reservation.error);
        } else {
          this.closeSocket(socket, 1008, reservation.reason);
        }
        return;
      }
    } else if (attachment.role === "device" && validation.envelope.type === "rpc_response") {
      if (!this.completeBrowserRequest(peers[0], requestId)) {
        this.closeSocket(socket, 1008, "Unknown rpc_response requestId");
        return;
      }
    }

    try {
      // Relay the original JSON text. The Worker validates the envelope but never
      // reads, rewrites, or logs the application-defined payload.
      peers[0].send(message);
    } catch {
      if (attachment.role === "browser" && validation.envelope.type === "rpc_request") {
        this.removeBrowserRequest(socket, requestId);
      }
      this.closeSocket(socket, 1011, "Peer relay failed");
      return;
    }

    if (validation.envelope.type === "bye") {
      this.closeSocket(socket, 1000, "Bye");
    }
  }

  webSocketClose(socket, code, reason, wasClean) {
    this.notifyPeerLeft(socket, code, reason, wasClean);
  }

  webSocketError(socket) {
    this.notifyPeerLeft(socket, 1011, "WebSocket error", false);
    this.closeSocket(socket, 1011, "WebSocket error");
  }

  reconnectSockets(role, routeNonce) {
    const occupied = this.openSockets(role);
    if (occupied.length === 0) return [];

    // A device request reaches this Durable Object only after the outer Worker has verified that
    // its bearer secret derives this exact room. A current browser reconnect must additionally
    // prove the same signed route nonce; legacy or different-route sockets remain exclusive.
    if (role === "device") return occupied;
    if (!isValidRouteNonce(routeNonce)) return null;
    return occupied.every((socket) =>
      socket.deserializeAttachment()?.routeNonce === routeNonce
    ) ? occupied : null;
  }

  supersedeReconnectSockets(role, sockets) {
    for (const socket of sockets) {
      const attachment = socket.deserializeAttachment();
      if (!attachment || attachment.role !== role) continue;
      // Suppress peer_left: the authenticated replacement is already accepted, so established
      // WebRTC media and the opposite signaling socket must remain undisturbed.
      socket.serializeAttachment({
        ...attachment,
        revoked: true,
        leftNotified: true,
        inFlightRequestIds: role === "browser" ? [] : attachment.inFlightRequestIds,
      });
      this.closeSocket(socket, 4002, "Connection replaced");
    }
    if (role === "device" && sockets.length > 0) {
      for (const browser of this.openSockets("browser")) {
        this.clearBrowserRequests(browser);
      }
    }
  }

  hasOpenPeer(role, excludedSocket = null) {
    return this.openSockets(role, excludedSocket).length > 0;
  }

  openSockets(role, excludedSocket = null) {
    return this.ctx.getWebSockets(role).filter(
      (socket) => {
        const attachment = socket.deserializeAttachment();
        return socket !== excludedSocket && socket.readyState === OPEN && !attachment?.revoked;
      },
    );
  }

  reserveBrowserRequest(socket, attachment, requestId) {
    const now = Date.now();
    const bucket = consumeTokenBucket(
      { tokens: attachment.rpcRateTokens, updatedAt: attachment.rpcRateUpdatedAt },
      now,
      BROWSER_RPC_RATE_PER_SECOND,
      BROWSER_RPC_BURST,
    );
    if (!bucket.allowed) {
      socket.serializeAttachment({
        ...attachment,
        rpcRateTokens: bucket.tokens,
        rpcRateUpdatedAt: bucket.updatedAt,
      });
      return {
        ok: false,
        retryable: true,
        error: "cloud_relay_rate_limited",
        reason: "Browser RPC rate limit exceeded",
      };
    }

    const inFlight = normalizeInFlightIds(attachment.inFlightRequestIds);
    if (inFlight.includes(requestId)) {
      return { ok: false, reason: "Duplicate in-flight requestId" };
    }
    if (inFlight.length >= MAX_IN_FLIGHT_REQUESTS) {
      return {
        ok: false,
        retryable: true,
        error: "cloud_relay_busy",
        reason: "Too many in-flight RPC requests",
      };
    }

    socket.serializeAttachment({
      ...attachment,
      rpcRateTokens: bucket.tokens,
      rpcRateUpdatedAt: bucket.updatedAt,
      inFlightRequestIds: [...inFlight, requestId],
    });
    return { ok: true };
  }

  completeBrowserRequest(browserSocket, requestId) {
    const attachment = browserSocket.deserializeAttachment();
    if (!attachment || attachment.role !== "browser") {
      return false;
    }
    const inFlight = normalizeInFlightIds(attachment.inFlightRequestIds);
    if (!inFlight.includes(requestId)) {
      return false;
    }
    browserSocket.serializeAttachment({
      ...attachment,
      inFlightRequestIds: inFlight.filter((value) => value !== requestId),
    });
    return true;
  }

  removeBrowserRequest(browserSocket, requestId) {
    const attachment = browserSocket.deserializeAttachment();
    if (!attachment || attachment.role !== "browser") {
      return;
    }
    const inFlight = normalizeInFlightIds(attachment.inFlightRequestIds);
    browserSocket.serializeAttachment({
      ...attachment,
      inFlightRequestIds: inFlight.filter((value) => value !== requestId),
    });
  }

  clearBrowserRequests(browserSocket) {
    const attachment = browserSocket.deserializeAttachment();
    if (!attachment || attachment.role !== "browser") {
      return;
    }
    browserSocket.serializeAttachment({ ...attachment, inFlightRequestIds: [] });
  }

  notifyPeerLeft(socket, code, reason, wasClean) {
    const attachment = socket.deserializeAttachment();
    if (!attachment || attachment.leftNotified) {
      return;
    }

    socket.serializeAttachment({ ...attachment, leftNotified: true });
    for (const peer of this.openSockets(oppositeRole(attachment.role), socket)) {
      if (attachment.role === "device") {
        this.clearBrowserRequests(peer);
      }
      this.sendSystem(peer, {
        type: "peer_left",
        peerRole: attachment.role,
        code,
        reason: sanitizeSystemReason(reason),
        wasClean: Boolean(wasClean),
        at: Date.now(),
      });
    }
  }

  sendSystem(socket, message) {
    if (socket.readyState !== OPEN) {
      return;
    }
    try {
      socket.send(JSON.stringify(message));
    } catch {
      // A close/error callback will perform peer notification when available.
    }
  }

  sendRpcError(socket, requestId, status, error) {
    if (socket.readyState !== OPEN) {
      return;
    }
    const bodyBase64 = btoa(JSON.stringify({ error }));
    try {
      socket.send(JSON.stringify({
        type: "rpc_response",
        requestId,
        status,
        contentType: "application/json; charset=utf-8",
        bodyBase64,
      }));
    } catch {
      this.closeSocket(socket, 1011, "RPC error response failed");
    }
  }

  closeSocket(socket, code, reason) {
    try {
      socket.close(code, sanitizeCloseReason(reason));
    } catch {
      // The socket may already be closing.
    }
  }
}

function oppositeRole(role) {
  return role === "browser" ? "device" : "browser";
}

function normalizeInFlightIds(value) {
  if (!Array.isArray(value)) {
    return [];
  }
  return value.filter((entry) => typeof entry === "string").slice(0, MAX_IN_FLIGHT_REQUESTS);
}

function sanitizeCloseReason(reason) {
  const value = typeof reason === "string" ? reason : "Connection closed";
  return value.slice(0, 120);
}

function sanitizeSystemReason(reason) {
  const value = typeof reason === "string" ? reason : "";
  return value.slice(0, 256);
}

function isPlainRecord(value) {
  return value !== null && typeof value === "object" && !Array.isArray(value);
}

function isValidPairingGeneration(value) {
  return typeof value === "string" && PAIRING_GENERATION_PATTERN.test(value);
}

function isValidPairingEpoch(value) {
  return Number.isSafeInteger(value) && value > 0;
}

function parsePairingEpochHeader(value) {
  if (typeof value !== "string" || !/^[1-9][0-9]{0,15}$/u.test(value)) return null;
  const epoch = Number(value);
  return isValidPairingEpoch(epoch) ? epoch : null;
}

function parsePairingTtlHeader(value) {
  if (typeof value !== "string" || !/^[1-9][0-9]{0,5}$/u.test(value)) return null;
  const ttlMillis = Number(value);
  return Number.isSafeInteger(ttlMillis) && ttlMillis <= PAIRING_CODE_TTL_MILLIS
    ? ttlMillis
    : null;
}

function parsePairingExpiresAtHeader(value) {
  if (typeof value !== "string" || !/^[1-9][0-9]{0,15}$/u.test(value)) return null;
  const expiresAt = Number(value);
  return Number.isSafeInteger(expiresAt) ? expiresAt : null;
}

function normalizeBrowserRouteState(value) {
  const legacyCurrent = isValidRouteNonce(value?.routeNonce) ? value.routeNonce : null;
  return {
    activePairingEpoch: isValidPairingEpoch(value?.activePairingEpoch)
      ? value.activePairingEpoch
      : null,
    activePairingGeneration: isValidPairingGeneration(value?.activePairingGeneration)
      ? value.activePairingGeneration
      : null,
    activePairingExpiresAt: Number.isSafeInteger(value?.activePairingExpiresAt)
      ? value.activePairingExpiresAt
      : null,
    currentRouteNonce: isValidRouteNonce(value?.currentRouteNonce)
      ? value.currentRouteNonce
      : legacyCurrent,
    currentSince: Number.isSafeInteger(value?.currentSince)
      ? value.currentSince
      : null,
    pendingRouteNonce: isValidRouteNonce(value?.pendingRouteNonce)
      ? value.pendingRouteNonce
      : null,
    pendingExpiresAt: Number.isSafeInteger(value?.pendingExpiresAt)
      ? value.pendingExpiresAt
      : null,
  };
}

export async function readBoundedPairingCode(request) {
  const maximumBytes = 32;
  const contentLength = request.headers.get("Content-Length");
  if (contentLength !== null) {
    if (!/^\d+$/u.test(contentLength)) return null;
    const declaredBytes = Number.parseInt(contentLength, 10);
    if (!Number.isSafeInteger(declaredBytes) || declaredBytes > maximumBytes) return null;
  }
  if (!request.body) return "";

  const reader = request.body.getReader();
  const chunks = [];
  let receivedBytes = 0;
  try {
    while (true) {
      const { done, value } = await reader.read();
      if (done) break;
      receivedBytes += value.byteLength;
      if (receivedBytes > maximumBytes) {
        await reader.cancel("Pairing request body is too large").catch(() => undefined);
        return null;
      }
      chunks.push(value);
    }
  } catch {
    return null;
  }

  const bytes = new Uint8Array(receivedBytes);
  let offset = 0;
  for (const chunk of chunks) {
    bytes.set(chunk, offset);
    offset += chunk.byteLength;
  }
  try {
    return new TextDecoder("utf-8", { fatal: true }).decode(bytes).trim();
  } catch {
    return null;
  }
}

function browserCorsHeaders(origin, extraHeaders = undefined) {
  return {
    "Access-Control-Allow-Origin": origin,
    "Access-Control-Allow-Credentials": "true",
    Vary: "Origin",
    ...extraHeaders,
  };
}

function emptyResponse(status = 204, extraHeaders = undefined) {
  const headers = new Headers(extraHeaders);
  headers.set("Cache-Control", "no-store");
  headers.set("X-Content-Type-Options", "nosniff");
  return new Response(null, { status, headers });
}

function jsonResponse(body, status = 200, extraHeaders = undefined) {
  const headers = new Headers(extraHeaders);
  headers.set("Content-Type", "application/json; charset=utf-8");
  headers.set("Cache-Control", "no-store");
  headers.set("X-Content-Type-Options", "nosniff");
  return new Response(JSON.stringify(body), { status, headers });
}
