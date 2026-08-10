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
  isValidRequestId,
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
const ROUTE_ISSUED_AT_HEADER = "X-NavOnWeb-Route-Issued-At";
const PAIRING_EPOCH_HEADER = "X-NavOnWeb-Pairing-Epoch";
const PAIRING_TTL_HEADER = "X-NavOnWeb-Pairing-Ttl-Millis";
const PAIRING_EXPIRES_AT_HEADER = "X-NavOnWeb-Pairing-Expires-At";
const RATE_IDEMPOTENCY_HEADER = "X-NavOnWeb-Rate-Idempotency";
const LEGACY_BROWSER_ROUTE_HEADER = "X-NavOnWeb-Legacy-Browser-Route";
const OPEN = 1;
const CLOSED = 3;
const BROWSER_RPC_RATE_PER_SECOND = 64;
const BROWSER_RPC_BURST = 96;
// Aggregate ingress is sized for three fully active browser sessions while preventing 32
// signaling transports from multiplying the phone-facing request rate.
const DEVICE_RPC_RATE_PER_SECOND = BROWSER_RPC_RATE_PER_SECOND * 3;
const DEVICE_RPC_BURST = BROWSER_RPC_BURST * 3;
const MAX_IN_FLIGHT_REQUESTS = 16;
// This is an ephemeral transport ceiling, not a paired-device or session registry. The phone is
// authoritative for its free/premium media-session capacity and stored browser credentials.
const MAX_LIVE_BROWSER_SOCKETS = 32;
const MAX_CONNECTION_ADMISSION_KEYS = 128;
const STALE_ROUTE_QUARANTINE_MILLIS = 30_000;
const MAX_ROUTE_QUARANTINES = MAX_LIVE_BROWSER_SOCKETS;
// Hibernatable WebSocket attachments are capped at 16 KiB. Keep the exact orphan set small even
// though signaling admits 32 transports; overflow switches to the time-bounded grace mode below.
const MAX_ORPHANED_REQUEST_IDS = 48;
const ORPHANED_REQUEST_TTL_MILLIS = 60_000;
const BROWSER_SOCKET_LIMIT_REACHED = Symbol("browser-socket-limit-reached");
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
      // The signed, cookie-routed endpoint is the preferred browser route. Keeping the legacy
      // room-id URL opt-in prevents public OSS clients from bypassing pairing.
      return jsonResponse({ error: "Not found" }, 404);
    }
    let roomId = cookieRoutedBrowser ? null : match[2];
    let browserRouteNonce = null;
    let browserRouteIssuedAtSeconds = null;
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
      browserRouteIssuedAtSeconds = route.issuedAtSeconds;
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
    headers.delete(ROUTE_ISSUED_AT_HEADER);
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
        if (Number.isSafeInteger(browserRouteIssuedAtSeconds)) {
          headers.set(ROUTE_ISSUED_AT_HEADER, String(browserRouteIssuedAtSeconds));
        }
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
  if (!isValidBootstrapSecret(env.BOOTSTRAP_HMAC_KEY)) {
    return emptyResponse(503, responseHeaders);
  }
  const routeValue = readCookie(request.headers.get("Cookie"), ROUTE_COOKIE_NAME);
  const route = await verifyRouteCookieValue(env.BOOTSTRAP_HMAC_KEY, routeValue);
  if (!route) return emptyResponse(401, responseHeaders);
  // The signed, expiring cookie is the complete Worker-side route proof. The phone's credential
  // store remains authoritative for browser authorization on relayed RPCs.
  return emptyResponse(204, responseHeaders);
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
    // This duration comes from the phone's fixed monotonic expiry deadline.
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

  // Pairing publication lives only in this isolated, expiring one-time slot. SignalRoom stores
  // neither the publication generation nor any paired-browser/session registry.
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

  // A consumed one-time slot authorizes a self-contained signed route ticket. There is no
  // pending/current/remembered route state in SignalRoom, so pairing another browser cannot
  // invalidate or supersede this route.
  const routeNonce = randomRouteNonce();
  let routeValue;
  try {
    routeValue = await createRouteCookieValue(
      env.BOOTSTRAP_HMAC_KEY,
      slotBody.roomId,
      routeNonce,
    );
  } catch {
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
    this.routeQuarantineFallbackUntil = new Map();
    this.orphanFallbackGraceUntil = new WeakMap();
  }

  async fetch(request) {
    if (request.headers.get("Upgrade")?.toLowerCase() !== "websocket") {
      return jsonResponse({ error: "WebSocket upgrade required" }, 426);
    }

    const role = request.headers.get(ROLE_HEADER);
    const roomId = request.headers.get(ROOM_HEADER);
    if ((role !== "browser" && role !== "device") || !isValidRoomId(roomId)) {
      return jsonResponse({ error: "Invalid internal signaling request" }, 400);
    }

    const routeNonce = role === "browser" ? request.headers.get(ROUTE_NONCE_HEADER) : null;
    const routeIssuedAtSeconds = role === "browser"
      ? parseRouteIssuedAtHeader(request.headers.get(ROUTE_ISSUED_AT_HEADER))
      : null;
    let browserRouteStatus = role === "browser" ? "legacy" : null;
    if (role === "browser" && request.headers.get(LEGACY_BROWSER_ROUTE_HEADER) !== "1") {
      // The outer Worker verified the HMAC route cookie before forwarding this opaque nonce.
      // SignalRoom deliberately has no persistent route/session registry to consult.
      browserRouteStatus = isValidRouteNonce(routeNonce) ? "signed" : null;
      if (!browserRouteStatus) {
        return jsonResponse(
          { error: "Browser pairing is required" },
          401,
          { "WWW-Authenticate": 'NavOnWeb-Pairing realm="browser"' },
        );
      }
    }

    if (role === "browser" && !this.hasOpenPeer("device")) {
      return jsonResponse({ error: "The device is not connected" }, 409);
    }
    const quarantineRemaining = role === "browser"
      ? this.routeQuarantineRemainingMillis(routeNonce)
      : 0;
    if (quarantineRemaining > 0) {
      return jsonResponse(
        { error: "Browser authorization retry is temporarily paused" },
        429,
        { "Retry-After": String(Math.max(1, Math.ceil(quarantineRemaining / 1000))) },
      );
    }

    if (!this.consumeConnectionAdmission(role, routeNonce)) {
      return jsonResponse(
        { error: "Signaling temporarily unavailable" },
        429,
        { "Retry-After": "30" },
      );
    }

    const reconnectSockets = this.reconnectSockets(role, routeNonce);
    if (reconnectSockets === null) {
      return jsonResponse({ error: `A ${role} is already connected` }, 409);
    }
    if (reconnectSockets === BROWSER_SOCKET_LIMIT_REACHED) {
      return jsonResponse({ error: "Browser signaling transport limit reached" }, 409);
    }

    const now = Date.now();
    const inheritedRouteQuarantines = role === "device"
      ? this.collectRouteQuarantines(reconnectSockets, now)
      : undefined;
    const pair = new WebSocketPair();
    const [client, server] = Object.values(pair);
    this.ctx.acceptWebSocket(server, [role]);
    // Hibernation attachments exist only for this live signaling transport. They contain bounded
    // relay/rate/in-flight bookkeeping, never paired-device membership or media-session capacity.
    server.serializeAttachment({
      role,
      roomId,
      routeNonce: role === "browser" ? routeNonce : undefined,
      routeIssuedAtSeconds: role === "browser" ? routeIssuedAtSeconds : undefined,
      connectedAt: now,
      leftNotified: false,
      rpcRateTokens: role === "browser" ? BROWSER_RPC_BURST : undefined,
      rpcRateUpdatedAt: role === "browser" ? now : undefined,
      inFlightRequestIds: role === "browser" ? [] : undefined,
      aggregateRpcRateTokens: role === "device" ? DEVICE_RPC_BURST : undefined,
      aggregateRpcRateUpdatedAt: role === "device" ? now : undefined,
      orphanedRequestIds: role === "device" ? [] : undefined,
      orphanResponseGraceUntil: role === "device" ? null : undefined,
      routeQuarantines: role === "device" ? inheritedRouteQuarantines : undefined,
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

  consumeConnectionAdmission(role, routeNonce = null, now = Date.now()) {
    for (const [key, window] of this.connectionWindows) {
      if (!Number.isSafeInteger(window?.windowStartedAt) ||
          now - window.windowStartedAt >= BOOTSTRAP_ATTEMPT_WINDOW_MILLIS) {
        this.connectionWindows.delete(key);
      }
    }
    const key = role === "browser"
      ? `browser:${isValidRouteNonce(routeNonce) ? routeNonce : "legacy"}`
      : "device";
    const current = this.connectionWindows.get(key);
    const withinWindow = current &&
      now - current.windowStartedAt < BOOTSTRAP_ATTEMPT_WINDOW_MILLIS;
    const count = withinWindow ? current.count : 0;
    if (count >= SIGNAL_CREDENTIAL_CONNECTION_LIMIT) return false;
    if (!current && this.connectionWindows.size >= MAX_CONNECTION_ADMISSION_KEYS) {
      let oldestKey = null;
      let oldestStartedAt = Number.POSITIVE_INFINITY;
      for (const [candidateKey, window] of this.connectionWindows) {
        if (window.windowStartedAt < oldestStartedAt) {
          oldestKey = candidateKey;
          oldestStartedAt = window.windowStartedAt;
        }
      }
      if (oldestKey !== null) this.connectionWindows.delete(oldestKey);
    }
    this.connectionWindows.set(key, {
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

    const requestId = validation.envelope.requestId;
    if (attachment.role === "browser") {
      if (validation.envelope.type === "rpc_request" &&
          isPairingRelayRequest(validation.envelope) &&
          !isFreshPairingRoute(attachment.routeIssuedAtSeconds)) {
        this.sendRpcError(
          socket,
          requestId,
          428,
          "cloud_relay_fresh_pairing_route_required",
        );
        return;
      }
      const devices = this.openSockets("device", socket);
      if (devices.length !== 1) {
        this.closeSocket(socket, 1008, "Peer is not connected");
        return;
      }
      const device = devices[0];
      if (validation.envelope.type === "rpc_request") {
        const reservation = this.reserveBrowserRequest(socket, attachment, requestId);
        if (!reservation.ok) {
          if (reservation.retryable) {
            this.sendRpcError(socket, requestId, 429, reservation.error);
          } else {
            this.closeSocket(socket, 1008, reservation.reason);
          }
          return;
        }
        if (!this.reserveDeviceRpc(device)) {
          this.removeBrowserRequest(socket, requestId);
          this.sendRpcError(socket, requestId, 429, "cloud_relay_device_rate_limited");
          return;
        }
      }
      try {
        // Relay the original JSON text. The Worker validates the envelope but never
        // reads, rewrites, decodes, or logs the application-defined payload.
        device.send(message);
      } catch {
        if (validation.envelope.type === "rpc_request") {
          this.removeBrowserRequest(socket, requestId);
        }
        this.closeSocket(socket, 1011, "Peer relay failed");
        return;
      }
      if (validation.envelope.type === "bye") {
        this.closeSocket(socket, 1000, "Bye");
      }
      return;
    }

    if (validation.envelope.type === "rpc_response") {
      // Include a just-closed browser whose close callback has not run yet. Durable Object events
      // are serialized, but readyState may already be CLOSED when the device response is dequeued.
      const targets = this.browserSocketsForRequestId(requestId, true);
      if (targets.length === 0) {
        const orphan = this.consumeOrphanedRequest(socket, requestId);
        if (orphan.consumed) {
          if (validation.envelope.status === 401 && isValidRouteNonce(orphan.routeNonce)) {
            this.quarantineBrowserRoute(socket, orphan.routeNonce);
            this.closeAuthorizationRejectedRoute(orphan.routeNonce);
          }
          return;
        }
        this.closeSocket(socket, 1008, "Unknown rpc_response requestId");
        return;
      }
      if (targets.length !== 1 || !this.completeBrowserRequest(targets[0], requestId)) {
        this.closeSocket(socket, 1008, "Ambiguous rpc_response requestId");
        return;
      }
      const target = targets[0];
      const targetAttachment = target.deserializeAttachment();
      const authorizationRejected = validation.envelope.status === 401 &&
        isValidRouteNonce(targetAttachment?.routeNonce);
      if (authorizationRejected) {
        this.quarantineBrowserRoute(socket, targetAttachment.routeNonce);
      }
      if (target.readyState !== OPEN || targetAttachment?.revoked) {
        // The exact owner disappeared before its close event recorded an orphan. Consume this
        // response locally; never expose it to another browser and never tear down the device.
        return;
      }
      try {
        target.send(message);
      } catch {
        // The response was bound to this browser and must never be retried to another one.
        this.closeSocket(target, 1011, "Peer relay failed");
      }
      if (authorizationRejected) {
        // Deliver the phone's exact 401 to its owner, then stop only that stale route. Other
        // browsers and the shared device transport remain live, and reconnects observe backoff.
        this.closeAuthorizationRejectedBrowser(target);
      }
      return;
    }

    const browsers = this.openSockets("browser", socket);
    if (browsers.length === 0) {
      this.closeSocket(socket, 1008, "Peer is not connected");
      return;
    }
    for (const browser of browsers) {
      try {
        browser.send(message);
      } catch {
        this.closeSocket(browser, 1011, "Peer relay failed");
      }
    }
    if (validation.envelope.type === "bye") {
      this.closeSocket(socket, 1000, "Bye");
    }
  }

  /*
   * A browser request always targets the one authenticated device socket. The reservation below
   * additionally binds its response to this exact browser socket.
   */
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
    const existingOwners = this.browserSocketsForRequestId(requestId, true);
    if (inFlight.includes(requestId) ||
        existingOwners.some((owner) => owner.readyState === OPEN)) {
      return { ok: false, reason: "Duplicate cross-browser in-flight requestId" };
    }
    if (existingOwners.length > 0 || this.hasUnresolvedOrphanRequestId(requestId, now)) {
      return {
        ok: false,
        retryable: true,
        error: "cloud_relay_request_id_pending",
        reason: "A disconnected browser request is still pending",
      };
    }
    if (this.aggregateInFlightRequestCount(now) >= MAX_IN_FLIGHT_REQUESTS) {
      return {
        ok: false,
        retryable: true,
        error: "cloud_relay_busy",
        reason: "Too many room-wide in-flight RPC requests",
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

  reserveDeviceRpc(deviceSocket, now = Date.now()) {
    const attachment = deviceSocket.deserializeAttachment();
    if (!attachment || attachment.role !== "device") return false;
    const bucket = consumeTokenBucket(
      {
        tokens: attachment.aggregateRpcRateTokens,
        updatedAt: attachment.aggregateRpcRateUpdatedAt,
      },
      now,
      DEVICE_RPC_RATE_PER_SECOND,
      DEVICE_RPC_BURST,
    );
    if (!bucket.allowed) return false;
    try {
      deviceSocket.serializeAttachment({
        ...attachment,
        aggregateRpcRateTokens: bucket.tokens,
        aggregateRpcRateUpdatedAt: bucket.updatedAt,
      });
      return true;
    } catch {
      // Fail only this RPC with a retryable 429; the shared device transport stays connected.
      return false;
    }
  }

  browserSocketsForRequestId(requestId, includeUnavailable = false) {
    const browsers = includeUnavailable
      ? this.ctx.getWebSockets("browser").filter(
        (browser) => !browser.deserializeAttachment()?.revoked,
      )
      : this.openSockets("browser");
    return browsers.filter((browser) => {
      const browserAttachment = browser.deserializeAttachment();
      return normalizeInFlightIds(browserAttachment?.inFlightRequestIds).includes(requestId);
    });
  }

  aggregateInFlightRequestCount(now = Date.now()) {
    const requestIds = new Set();
    for (const browser of this.ctx.getWebSockets("browser")) {
      const attachment = browser.deserializeAttachment();
      if (attachment?.revoked) continue;
      for (const requestId of normalizeInFlightIds(attachment?.inFlightRequestIds)) {
        requestIds.add(requestId);
      }
    }
    for (const device of this.openSockets("device")) {
      const attachment = device.deserializeAttachment();
      for (const entry of normalizeOrphanedRequestIds(attachment?.orphanedRequestIds, now)) {
        requestIds.add(entry.requestId);
      }
      if (normalizeOrphanGraceUntil(attachment?.orphanResponseGraceUntil, now) !== null ||
          this.orphanFallbackGrace(device, now) !== null) {
        return Math.max(MAX_IN_FLIGHT_REQUESTS, requestIds.size);
      }
    }
    return requestIds.size;
  }

  rememberBrowserRequestsAsOrphans(browserSocket, now = Date.now()) {
    const attachment = browserSocket.deserializeAttachment();
    if (!attachment || attachment.role !== "browser") return;
    const requestIds = normalizeInFlightIds(attachment.inFlightRequestIds);
    if (requestIds.length === 0) return;
    for (const device of this.openSockets("device")) {
      this.addOrphanedRequestIds(device, requestIds, now, attachment.routeNonce);
    }
    this.clearBrowserRequests(browserSocket);
  }

  addOrphanedRequestIds(deviceSocket, requestIds, now = Date.now(), routeNonce = null) {
    const attachment = deviceSocket.deserializeAttachment();
    if (!attachment || attachment.role !== "device") return;
    const expiresAt = now + ORPHANED_REQUEST_TTL_MILLIS;
    const current = normalizeOrphanedRequestIds(attachment.orphanedRequestIds, now);
    const known = new Set(current.map((entry) => entry.requestId));
    let overflow = false;
    for (const requestId of requestIds) {
      if (known.has(requestId)) continue;
      if (current.length >= MAX_ORPHANED_REQUEST_IDS) {
        overflow = true;
        continue;
      }
      current.push({
        requestId,
        expiresAt,
        ...(isValidRouteNonce(routeNonce) ? { routeNonce } : {}),
      });
      known.add(requestId);
    }
    const graceUntil = overflow
      ? Math.max(attachment.orphanResponseGraceUntil ?? 0, expiresAt)
      : normalizeOrphanGraceUntil(attachment.orphanResponseGraceUntil, now);
    try {
      deviceSocket.serializeAttachment({
        ...attachment,
        orphanedRequestIds: current,
        orphanResponseGraceUntil: graceUntil,
      });
    } catch {
      // A hibernatable attachment write must never let a late response tear down the shared
      // device transport. Fall back to a compact grace marker and finally to instance memory.
      this.rememberOrphanFallbackGrace(deviceSocket, expiresAt);
      try {
        deviceSocket.serializeAttachment({
          ...attachment,
          orphanedRequestIds: [],
          orphanResponseGraceUntil: expiresAt,
        });
      } catch {
        // The in-memory marker remains bounded by the live WebSocket objects (WeakMap).
      }
    }
  }

  hasUnresolvedOrphanRequestId(requestId, now = Date.now()) {
    return this.openSockets("device").some((device) => {
      const attachment = device.deserializeAttachment();
      const orphans = normalizeOrphanedRequestIds(attachment?.orphanedRequestIds, now);
      return orphans.some((entry) => entry.requestId === requestId) ||
        normalizeOrphanGraceUntil(attachment?.orphanResponseGraceUntil, now) !== null ||
        this.orphanFallbackGrace(device, now) !== null;
    });
  }

  consumeOrphanedRequest(deviceSocket, requestId, now = Date.now()) {
    const attachment = deviceSocket.deserializeAttachment();
    if (!attachment || attachment.role !== "device") {
      return { consumed: false, routeNonce: null };
    }
    const current = normalizeOrphanedRequestIds(attachment.orphanedRequestIds, now);
    const index = current.findIndex((entry) => entry.requestId === requestId);
    const graceUntil = normalizeOrphanGraceUntil(attachment.orphanResponseGraceUntil, now);
    const fallbackGraceUntil = this.orphanFallbackGrace(deviceSocket, now);
    if (index < 0 && graceUntil === null && fallbackGraceUntil === null) {
      return { consumed: false, routeNonce: null };
    }
    const routeNonce = index >= 0 && isValidRouteNonce(current[index].routeNonce)
      ? current[index].routeNonce
      : null;
    if (index >= 0) current.splice(index, 1);
    try {
      deviceSocket.serializeAttachment({
        ...attachment,
        orphanedRequestIds: current,
        orphanResponseGraceUntil: graceUntil,
      });
    } catch {
      this.rememberOrphanFallbackGrace(
        deviceSocket,
        Math.max(graceUntil ?? 0, fallbackGraceUntil ?? 0, now + ORPHANED_REQUEST_TTL_MILLIS),
      );
    }
    return { consumed: true, routeNonce };
  }

  consumeOrphanedRequestId(deviceSocket, requestId, now = Date.now()) {
    return this.consumeOrphanedRequest(deviceSocket, requestId, now).consumed;
  }

  rememberOrphanFallbackGrace(deviceSocket, expiresAt) {
    const current = this.orphanFallbackGraceUntil.get(deviceSocket) ?? 0;
    this.orphanFallbackGraceUntil.set(deviceSocket, Math.max(current, expiresAt));
  }

  orphanFallbackGrace(deviceSocket, now = Date.now()) {
    const expiresAt = this.orphanFallbackGraceUntil.get(deviceSocket);
    if (!Number.isSafeInteger(expiresAt) || expiresAt <= now) {
      this.orphanFallbackGraceUntil.delete(deviceSocket);
      return null;
    }
    return expiresAt;
  }

  collectRouteQuarantines(deviceSockets, now = Date.now()) {
    const byRoute = new Map();
    for (const socket of deviceSockets) {
      const attachment = socket.deserializeAttachment();
      for (const entry of normalizeRouteQuarantines(attachment?.routeQuarantines, now)) {
        byRoute.set(entry.routeNonce, Math.max(byRoute.get(entry.routeNonce) ?? 0, entry.expiresAt));
      }
    }
    for (const [routeNonce, expiresAt] of this.routeQuarantineFallbackUntil) {
      if (!isValidRouteNonce(routeNonce) || !Number.isSafeInteger(expiresAt) || expiresAt <= now) {
        this.routeQuarantineFallbackUntil.delete(routeNonce);
        continue;
      }
      byRoute.set(routeNonce, Math.max(byRoute.get(routeNonce) ?? 0, expiresAt));
    }
    return [...byRoute.entries()]
      .map(([routeNonce, expiresAt]) => ({ routeNonce, expiresAt }))
      .sort((left, right) => right.expiresAt - left.expiresAt)
      .slice(0, MAX_ROUTE_QUARANTINES);
  }

  routeQuarantineRemainingMillis(routeNonce, now = Date.now()) {
    if (!isValidRouteNonce(routeNonce)) return 0;
    let expiresAt = this.routeQuarantineFallbackUntil.get(routeNonce) ?? 0;
    if (!Number.isSafeInteger(expiresAt) || expiresAt <= now) {
      this.routeQuarantineFallbackUntil.delete(routeNonce);
      expiresAt = 0;
    }
    for (const device of this.openSockets("device")) {
      for (const entry of normalizeRouteQuarantines(
        device.deserializeAttachment()?.routeQuarantines,
        now,
      )) {
        if (entry.routeNonce === routeNonce) expiresAt = Math.max(expiresAt, entry.expiresAt);
      }
    }
    return Math.max(0, expiresAt - now);
  }

  quarantineBrowserRoute(deviceSocket, routeNonce, now = Date.now()) {
    if (!isValidRouteNonce(routeNonce)) return;
    const expiresAt = now + STALE_ROUTE_QUARANTINE_MILLIS;
    this.routeQuarantineFallbackUntil.set(routeNonce, expiresAt);
    while (this.routeQuarantineFallbackUntil.size > MAX_ROUTE_QUARANTINES) {
      let oldestRoute = null;
      let oldestExpiresAt = Number.POSITIVE_INFINITY;
      for (const [candidateRoute, candidateExpiresAt] of this.routeQuarantineFallbackUntil) {
        if (candidateExpiresAt < oldestExpiresAt) {
          oldestRoute = candidateRoute;
          oldestExpiresAt = candidateExpiresAt;
        }
      }
      if (oldestRoute === null) break;
      this.routeQuarantineFallbackUntil.delete(oldestRoute);
    }

    const attachment = deviceSocket.deserializeAttachment();
    if (!attachment || attachment.role !== "device") return;
    const current = normalizeRouteQuarantines(attachment.routeQuarantines, now)
      .filter((entry) => entry.routeNonce !== routeNonce);
    try {
      deviceSocket.serializeAttachment({
        ...attachment,
        routeQuarantines: [...current, { routeNonce, expiresAt }]
          .sort((left, right) => right.expiresAt - left.expiresAt)
          .slice(0, MAX_ROUTE_QUARANTINES),
      });
    } catch {
      // The bounded instance map still protects the live room if attachment serialization fails.
    }
  }

  closeAuthorizationRejectedBrowser(browserSocket) {
    let attachment = browserSocket.deserializeAttachment();
    if (!attachment || attachment.role !== "browser") return;
    this.rememberBrowserRequestsAsOrphans(browserSocket);
    attachment = browserSocket.deserializeAttachment() ?? attachment;
    try {
      browserSocket.serializeAttachment({
        ...attachment,
        revoked: true,
        leftNotified: true,
        inFlightRequestIds: [],
      });
    } catch {
      // Closing still prevents this stale socket from spending more shared device budget.
    }
    this.closeSocket(browserSocket, 4003, "Browser authorization rejected");
  }

  closeAuthorizationRejectedRoute(routeNonce) {
    if (!isValidRouteNonce(routeNonce)) return;
    for (const browser of this.openSockets("browser")) {
      if (browser.deserializeAttachment()?.routeNonce === routeNonce) {
        this.closeAuthorizationRejectedBrowser(browser);
      }
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
    const sameRoute = occupied.filter((socket) => {
      const existingNonce = socket.deserializeAttachment()?.routeNonce;
      return isValidRouteNonce(routeNonce)
        ? existingNonce === routeNonce
        : !isValidRouteNonce(existingNonce);
    });
    if (sameRoute.length > 0) return sameRoute;
    return occupied.length >= MAX_LIVE_BROWSER_SOCKETS
      ? BROWSER_SOCKET_LIMIT_REACHED
      : [];
  }

  supersedeReconnectSockets(role, sockets) {
    for (const socket of sockets) {
      const attachment = socket.deserializeAttachment();
      if (!attachment || attachment.role !== role) continue;
      if (role === "browser") {
        this.rememberBrowserRequestsAsOrphans(socket);
      }
      // Suppress peer_left: the authenticated replacement is already accepted, so established
      // WebRTC media and the opposite signaling socket must remain undisturbed.
      const currentAttachment = socket.deserializeAttachment() ?? attachment;
      socket.serializeAttachment({
        ...currentAttachment,
        revoked: true,
        leftNotified: true,
        inFlightRequestIds: role === "browser" ? [] : currentAttachment.inFlightRequestIds,
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
    let attachment = socket.deserializeAttachment();
    if (!attachment || attachment.leftNotified) {
      return;
    }

    if (attachment.role === "browser") {
      this.rememberBrowserRequestsAsOrphans(socket);
      attachment = socket.deserializeAttachment() ?? attachment;
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
  return value.filter((entry) => isValidRequestId(entry)).slice(0, MAX_IN_FLIGHT_REQUESTS);
}

function normalizeOrphanedRequestIds(value, now = Date.now()) {
  if (!Array.isArray(value)) return [];
  const seen = new Set();
  const normalized = [];
  for (const entry of value) {
    if (!isPlainRecord(entry) ||
        !isValidRequestId(entry.requestId) ||
        !Number.isSafeInteger(entry.expiresAt) ||
        entry.expiresAt <= now ||
        seen.has(entry.requestId)) continue;
    seen.add(entry.requestId);
    normalized.push({
      requestId: entry.requestId,
      expiresAt: entry.expiresAt,
      ...(isValidRouteNonce(entry.routeNonce) ? { routeNonce: entry.routeNonce } : {}),
    });
    if (normalized.length >= MAX_ORPHANED_REQUEST_IDS) break;
  }
  return normalized;
}

function normalizeOrphanGraceUntil(value, now = Date.now()) {
  return Number.isSafeInteger(value) && value > now ? value : null;
}

function normalizeRouteQuarantines(value, now = Date.now()) {
  if (!Array.isArray(value)) return [];
  const byRoute = new Map();
  for (const entry of value) {
    if (!isPlainRecord(entry) ||
        !isValidRouteNonce(entry.routeNonce) ||
        !Number.isSafeInteger(entry.expiresAt) ||
        entry.expiresAt <= now ||
        entry.expiresAt > now + STALE_ROUTE_QUARANTINE_MILLIS) continue;
    byRoute.set(entry.routeNonce, Math.max(byRoute.get(entry.routeNonce) ?? 0, entry.expiresAt));
  }
  return [...byRoute.entries()]
    .map(([routeNonce, expiresAt]) => ({ routeNonce, expiresAt }))
    .sort((left, right) => right.expiresAt - left.expiresAt)
    .slice(0, MAX_ROUTE_QUARANTINES);
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

function parseRouteIssuedAtHeader(value) {
  if (typeof value !== "string" || !/^\d{10}$/u.test(value)) return null;
  const issuedAtSeconds = Number.parseInt(value, 10);
  return Number.isSafeInteger(issuedAtSeconds) ? issuedAtSeconds : null;
}

function isPairingRelayRequest(envelope) {
  if (typeof envelope?.method !== "string" ||
      envelope.method.toUpperCase() !== "POST" ||
      typeof envelope?.target !== "string") return false;
  try {
    const target = new URL(envelope.target, "https://navonweb.invalid");
    return target.origin === "https://navonweb.invalid" && target.pathname === "/api/pair";
  } catch {
    return false;
  }
}

function isFreshPairingRoute(issuedAtSeconds, now = Date.now()) {
  if (!Number.isSafeInteger(issuedAtSeconds)) return false;
  const ageMillis = now - issuedAtSeconds * 1000;
  return ageMillis >= 0 && ageMillis <= PAIRING_CODE_TTL_MILLIS;
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
