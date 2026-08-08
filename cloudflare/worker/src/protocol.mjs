export const PROTOCOL_VERSION = 2;
export const ROOM_ID_CHARS = 22;
export const DEVICE_SECRET_CHARS = 43;
export const ROUTE_NONCE_CHARS = 22;
export const MAX_MESSAGE_BYTES = 192 * 1024;
export const PAIRING_CODE_TTL_MILLIS = 10 * 60 * 1000;
export const ROUTE_COOKIE_TTL_SECONDS = 180 * 24 * 60 * 60;
export const ROUTE_COOKIE_NAME = "__Host-navonweb_route";

const ROOM_ID_PATTERN = new RegExp(`^[A-Za-z0-9_-]{${ROOM_ID_CHARS}}$`);
const DEVICE_SECRET_PATTERN = new RegExp(`^[A-Za-z0-9_-]{${DEVICE_SECRET_CHARS}}$`);
const ROUTE_NONCE_PATTERN = new RegExp(`^[A-Za-z0-9_-]{${ROUTE_NONCE_CHARS}}$`);
const REQUEST_ID_PATTERN = /^[A-Za-z0-9_-]{16,64}$/;
const PAIRING_CODE_PATTERN = /^\d{8}$/;
const BOOTSTRAP_SECRET_MIN_BYTES = 32;
const COMMON_TYPES = new Set(["ping", "pong", "bye"]);
const RPC_REQUEST_FIELDS = new Set([
  "type",
  "requestId",
  "method",
  "target",
  "headers",
  "bodyBase64",
]);
const RPC_RESPONSE_FIELDS = new Set([
  "type",
  "requestId",
  "status",
  "contentType",
  "bodyBase64",
]);
const COMMON_FIELDS = new Set(["type", "requestId", "payload"]);

export function isValidRoomId(value) {
  return typeof value === "string" && ROOM_ID_PATTERN.test(value);
}

export function isValidRequestId(value) {
  return typeof value === "string" && REQUEST_ID_PATTERN.test(value);
}

export function isValidPairingCode(value) {
  return typeof value === "string" && PAIRING_CODE_PATTERN.test(value);
}

export function isValidRouteNonce(value) {
  return typeof value === "string" && ROUTE_NONCE_PATTERN.test(value);
}

export function isValidBootstrapSecret(value) {
  return typeof value === "string" &&
    new TextEncoder().encode(value).byteLength >= BOOTSTRAP_SECRET_MIN_BYTES;
}

export function parseDeviceBearer(authorization) {
  if (typeof authorization !== "string") {
    return null;
  }
  const match = new RegExp(`^Bearer ([A-Za-z0-9_-]{${DEVICE_SECRET_CHARS}})$`, "i")
    .exec(authorization.trim());
  return match ? match[1] : null;
}

/**
 * Must stay byte-for-byte compatible with CloudRelayIdentity.roomIdForSecret():
 * base64url(SHA-256(US_ASCII(deviceSecret))).take(22).
 */
export async function deriveRoomIdFromDeviceSecret(secret) {
  if (typeof secret !== "string" || !DEVICE_SECRET_PATTERN.test(secret)) {
    throw new TypeError("Device secret must be 43 canonical base64url characters");
  }
  const digest = new Uint8Array(
    await globalThis.crypto.subtle.digest("SHA-256", new TextEncoder().encode(secret)),
  );
  return encodeBase64Url(digest).slice(0, ROOM_ID_CHARS);
}

/**
 * Produces an opaque Durable Object name. Neither the eight-digit code nor the
 * client's network address is exposed in an object name or log-friendly URL.
 */
export async function deriveBootstrapObjectName(secret, namespace, ...parts) {
  if (!isValidBootstrapSecret(secret)) {
    throw new TypeError("Bootstrap HMAC secret is not configured");
  }
  if (typeof namespace !== "string" || namespace.length < 1 || namespace.length > 32) {
    throw new TypeError("Invalid bootstrap namespace");
  }
  if (parts.some((part) => typeof part !== "string" || part.length > 128)) {
    throw new TypeError("Invalid bootstrap key part");
  }
  return hmacBase64Url(secret, ["navonweb-bootstrap-v1", namespace, ...parts].join("\u0000"));
}

/**
 * Cloudflare supplies CF-Connecting-IP. IPv4 is matched exactly; IPv6 privacy
 * addresses are matched by their canonical /64 network prefix.
 */
export function normalizeClientNetwork(value) {
  if (typeof value !== "string") return null;
  const candidate = value.trim();
  const ipv4 = parseIpv4(candidate);
  if (ipv4) return `v4:${ipv4.join(".")}`;

  const ipv6 = parseIpv6(candidate);
  if (!ipv6) return null;
  if (
    ipv6.slice(0, 5).every((part) => part === 0) &&
    ipv6[5] === 0xffff
  ) {
    return `v4:${ipv6[6] >>> 8}.${ipv6[6] & 0xff}.${ipv6[7] >>> 8}.${ipv6[7] & 0xff}`;
  }
  return `v6:${ipv6.slice(0, 4).map((part) => part.toString(16).padStart(4, "0")).join(":")}::/64`;
}

export async function createRouteCookieValue(
  secret,
  roomId,
  routeNonce,
  nowMillis = Date.now(),
  ttlSeconds = ROUTE_COOKIE_TTL_SECONDS,
) {
  if (!isValidBootstrapSecret(secret)) {
    throw new TypeError("Bootstrap HMAC secret is not configured");
  }
  if (!isValidRoomId(roomId)) throw new TypeError("Invalid room id");
  if (!isValidRouteNonce(routeNonce)) throw new TypeError("Invalid route nonce");
  if (!Number.isInteger(ttlSeconds) || ttlSeconds < 1 || ttlSeconds > ROUTE_COOKIE_TTL_SECONDS) {
    throw new TypeError("Invalid route cookie lifetime");
  }
  const expiresAtSeconds = Math.floor(nowMillis / 1000) + ttlSeconds;
  const payload = `v2.${roomId}.${routeNonce}.${expiresAtSeconds}`;
  const signature = await hmacBase64Url(secret, `navonweb-route-cookie\u0000${payload}`);
  return `${payload}.${signature}`;
}

export async function verifyRouteCookieValue(secret, value, nowMillis = Date.now()) {
  if (!isValidBootstrapSecret(secret) || typeof value !== "string") return null;
  const match = /^(v2)\.([A-Za-z0-9_-]{22})\.([A-Za-z0-9_-]{22})\.(\d{10})\.([A-Za-z0-9_-]{43})$/u.exec(value);
  if (!match) return null;
  const [, version, roomId, routeNonce, expirationText, providedSignature] = match;
  const expiresAtSeconds = Number.parseInt(expirationText, 10);
  if (!Number.isSafeInteger(expiresAtSeconds) || expiresAtSeconds <= Math.floor(nowMillis / 1000)) {
    return null;
  }
  const payload = `${version}.${roomId}.${routeNonce}.${expirationText}`;
  const expectedSignature = await hmacBase64Url(
    secret,
    `navonweb-route-cookie\u0000${payload}`,
  );
  if (!constantTimeEqualAscii(providedSignature, expectedSignature)) return null;
  return { roomId, routeNonce, expiresAtSeconds };
}

export function readCookie(cookieHeader, name) {
  if (typeof cookieHeader !== "string" || typeof name !== "string" || name === "") return null;
  for (const segment of cookieHeader.split(";")) {
    const separator = segment.indexOf("=");
    if (separator < 1) continue;
    if (segment.slice(0, separator).trim() !== name) continue;
    const value = segment.slice(separator + 1).trim();
    return value === "" ? null : value;
  }
  return null;
}

export async function authorizeDeviceRoom(roomId, authorization) {
  const secret = parseDeviceBearer(authorization);
  if (!secret) {
    return { ok: false, status: 401, error: "Missing or malformed device bearer credential" };
  }

  let expectedRoomId;
  try {
    expectedRoomId = await deriveRoomIdFromDeviceSecret(secret);
  } catch {
    return { ok: false, status: 401, error: "Malformed device bearer credential" };
  }

  if (!constantTimeEqualAscii(roomId, expectedRoomId)) {
    return { ok: false, status: 403, error: "Device credential does not authorize this room" };
  }
  return { ok: true };
}

export function parseAllowedOrigins(value) {
  const origins = new Set();
  if (typeof value !== "string") {
    return origins;
  }

  for (const entry of value.split(",")) {
    const origin = canonicalOrigin(entry.trim());
    if (origin) {
      origins.add(origin);
    }
  }
  return origins;
}

export function isAllowedBrowserOrigin(origin, configuredOrigins) {
  const canonical = canonicalOrigin(origin);
  return canonical !== null && parseAllowedOrigins(configuredOrigins).has(canonical);
}

export function utf8ByteLength(value) {
  return new TextEncoder().encode(value).byteLength;
}

export function consumeTokenBucket(state, now, ratePerSecond, capacity) {
  const previousTokens = Number.isFinite(state?.tokens) ? state.tokens : capacity;
  const previousUpdatedAt = Number.isFinite(state?.updatedAt) ? state.updatedAt : now;
  const elapsedMillis = Math.max(0, now - previousUpdatedAt);
  const available = Math.min(
    capacity,
    Math.max(0, previousTokens) + (elapsedMillis * ratePerSecond) / 1000,
  );
  return {
    allowed: available >= 1,
    tokens: available >= 1 ? available - 1 : available,
    updatedAt: now,
  };
}

/** Cloud RPC is limited to pairing, connection metadata, initial geometry, and SDP/ICE. */
export function isAllowedCloudRelayRequest(method, target) {
  if (typeof method !== "string" || typeof target !== "string" ||
      !target.startsWith("/") || target.startsWith("//")) return false;
  let url;
  try {
    url = new URL(target, "https://navonweb.invalid");
  } catch {
    return false;
  }
  if (url.origin !== "https://navonweb.invalid" || url.hash) return false;
  const normalizedMethod = method.toUpperCase();
  const path = url.pathname;
  if (normalizedMethod === "GET" && new Set([
    "/health",
    "/api/status",
    "/api/projection/profile",
    "/api/projection/viewport",
    "/api/webrtc/capabilities",
  ]).has(path)) return true;
  if (normalizedMethod === "POST" && new Set([
    "/api/pair",
    "/api/projection/viewport",
    "/api/webrtc/session",
  ]).has(path)) return true;
  return (normalizedMethod === "GET" || normalizedMethod === "DELETE") &&
    /^\/api\/webrtc\/session\/[A-Za-z0-9_-]{16,64}$/u.test(path);
}

export function validateClientMessage(message, role) {
  if (typeof message !== "string") {
    return invalid(1003, "Only UTF-8 JSON text messages are accepted");
  }
  if (utf8ByteLength(message) > MAX_MESSAGE_BYTES) {
    return invalid(1009, `Message exceeds ${MAX_MESSAGE_BYTES} bytes`);
  }

  let envelope;
  try {
    envelope = JSON.parse(message);
  } catch {
    return invalid(1007, "Message is not valid JSON");
  }
  if (!isPlainObject(envelope)) {
    return invalid(1007, "Message must be a JSON object");
  }
  if (!isValidRequestId(envelope.requestId)) {
    return invalid(1008, "requestId must match [A-Za-z0-9_-]{16,64}");
  }
  if (!isAllowedClientType(envelope.type, role)) {
    return invalid(1008, `Message type is not allowed for role ${role}`);
  }

  if (envelope.type === "rpc_request") {
    if (!hasExactlyAllowedFields(envelope, RPC_REQUEST_FIELDS) ||
        typeof envelope.method !== "string" ||
        typeof envelope.target !== "string" ||
        !isStringMap(envelope.headers) ||
        typeof envelope.bodyBase64 !== "string") {
      return invalid(1008, "Malformed flat rpc_request envelope");
    }
    if (!isAllowedCloudRelayRequest(envelope.method, envelope.target)) {
      return invalid(1008, "Cloud relay is restricted to connection signaling");
    }
  } else if (envelope.type === "rpc_response") {
    if (!hasExactlyAllowedFields(envelope, RPC_RESPONSE_FIELDS) ||
        !Number.isInteger(envelope.status) ||
        typeof envelope.contentType !== "string" ||
        typeof envelope.bodyBase64 !== "string") {
      return invalid(1008, "Malformed flat rpc_response envelope");
    }
  } else if (!hasOnlyAllowedFields(envelope, COMMON_FIELDS)) {
    return invalid(1008, "Malformed control envelope");
  }

  return { ok: true, envelope };
}

export function encodeBase64Url(bytes) {
  let binary = "";
  for (const value of bytes) {
    binary += String.fromCharCode(value);
  }
  return btoa(binary).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/u, "");
}

async function hmacBase64Url(secret, message) {
  const key = await globalThis.crypto.subtle.importKey(
    "raw",
    new TextEncoder().encode(secret),
    { name: "HMAC", hash: "SHA-256" },
    false,
    ["sign"],
  );
  const signature = new Uint8Array(
    await globalThis.crypto.subtle.sign("HMAC", key, new TextEncoder().encode(message)),
  );
  return encodeBase64Url(signature);
}

function parseIpv4(value) {
  const pieces = value.split(".");
  if (pieces.length !== 4) return null;
  const parsed = [];
  for (const piece of pieces) {
    if (!/^\d{1,3}$/u.test(piece)) return null;
    const number = Number.parseInt(piece, 10);
    if (number < 0 || number > 255) return null;
    parsed.push(number);
  }
  return parsed;
}

function parseIpv6(value) {
  if (value === "" || value.includes("%") || !/^[0-9A-Fa-f:.]+$/u.test(value)) return null;
  if ((value.match(/::/gu) ?? []).length > 1) return null;

  let candidate = value;
  const lastColon = candidate.lastIndexOf(":");
  if (candidate.includes(".") && lastColon >= 0) {
    const ipv4 = parseIpv4(candidate.slice(lastColon + 1));
    if (!ipv4) return null;
    candidate = `${candidate.slice(0, lastColon)}:${((ipv4[0] << 8) | ipv4[1]).toString(16)}:${((ipv4[2] << 8) | ipv4[3]).toString(16)}`;
  }

  const compressed = candidate.includes("::");
  const [leftText, rightText = ""] = candidate.split("::");
  const left = leftText === "" ? [] : leftText.split(":");
  const right = rightText === "" ? [] : rightText.split(":");
  if ([...left, ...right].some((part) => !/^[0-9A-Fa-f]{1,4}$/u.test(part))) return null;
  if ((!compressed && left.length !== 8) || (compressed && left.length + right.length >= 8)) {
    return null;
  }
  const zeros = compressed ? Array(8 - left.length - right.length).fill("0") : [];
  const parts = [...left, ...zeros, ...right].map((part) => Number.parseInt(part, 16));
  return parts.length === 8 ? parts : null;
}

function canonicalOrigin(value) {
  if (typeof value !== "string" || value === "" || value === "null") {
    return null;
  }
  try {
    const url = new URL(value);
    const isLoopback = url.hostname === "localhost" ||
      url.hostname === "127.0.0.1" ||
      url.hostname === "[::1]";
    if (url.protocol !== "https:" && !(url.protocol === "http:" && isLoopback)) {
      return null;
    }
    if (url.username || url.password || url.pathname !== "/" || url.search || url.hash) {
      return null;
    }
    return url.origin;
  } catch {
    return null;
  }
}

function isAllowedClientType(type, role) {
  if (COMMON_TYPES.has(type)) {
    return role === "browser" || role === "device";
  }
  return (role === "browser" && type === "rpc_request") ||
    (role === "device" && type === "rpc_response");
}

function constantTimeEqualAscii(left, right) {
  const maxLength = Math.max(left.length, right.length);
  let difference = left.length ^ right.length;
  for (let index = 0; index < maxLength; index += 1) {
    difference |= (left.charCodeAt(index) || 0) ^ (right.charCodeAt(index) || 0);
  }
  return difference === 0;
}

function isPlainObject(value) {
  return value !== null && typeof value === "object" && !Array.isArray(value);
}

function isStringMap(value) {
  return isPlainObject(value) && Object.values(value).every((entry) => typeof entry === "string");
}

function hasExactlyAllowedFields(value, allowedFields) {
  return Object.keys(value).length === allowedFields.size && hasOnlyAllowedFields(value, allowedFields);
}

function hasOnlyAllowedFields(value, allowedFields) {
  return Object.keys(value).every((key) => allowedFields.has(key));
}

function invalid(closeCode, reason) {
  return { ok: false, closeCode, reason };
}
