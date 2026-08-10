# Bare URL browser pairing

The browser can open a stable viewer URL without a device or room identifier in
the address. The examples below use `https://signal.example.com` for the Worker and
`https://viewer.example.com` for Pages. Replace them with origins owned by the
deployment operator.

The browser and signaling origins must share the same schemeful site because the route cookie
uses `Secure; SameSite=Strict`. Sibling HTTPS subdomains under the same registrable domain meet
that requirement; unrelated sites do not.

## Required configuration

The Worker requires:

- `BOOTSTRAP_HMAC_KEY`: at least 32 cryptographically random bytes, stored as a
  Wrangler secret;
- `ALLOWED_BROWSER_ORIGINS`: an exact, comma-separated HTTPS origin allowlist;
- the two Durable Object bindings and migrations in `worker/wrangler.toml`.

```powershell
npx wrangler secret put BOOTSTRAP_HMAC_KEY --config worker/wrangler.toml
```

Origins containing paths, credentials, queries, fragments, or wildcards are
rejected. Loopback HTTP origins are accepted only for local development.

## Pairing protocol

1. The Android device keeps an authenticated device WebSocket and publishes an
   eight-digit, one-time pairing code:

   ```http
   POST /bootstrap/device
   Authorization: Bearer <43-character-device-secret>
   X-NavOnWeb-Pairing-Epoch: <monotonic-positive-integer>
   X-NavOnWeb-Pairing-Ttl-Millis: <remaining-duration>
   Content-Type: text/plain; charset=utf-8

   12345678
   ```

2. The browser submits that code from an allowed origin:

   ```http
   POST https://signal.example.com/_nw/bootstrap/pair
   Origin: https://viewer.example.com
   Content-Type: text/plain; charset=utf-8

   12345678
   ```

   A successful response is `204` and sets the
   `__Host-navonweb_route` cookie.

3. The browser opens the cookie-routed socket:

   ```text
   wss://signal.example.com/_nw/ws/browser
   ```

   This is the default separate-origin layout and matches
   `NAVONWEB_SIGNALING_WEBSOCKET_ORIGIN=wss://signal.example.com`. The browser
   sends credentials and the Worker must allow `https://viewer.example.com`.
   A deployment may use `wss://viewer.example.com/_nw/ws/browser` only when its
   same-origin `/_nw/*` route is explicitly mapped to the Worker.

4. The device opens its room-bound socket:

   ```http
   GET /ws/device/<22-character-room-id>
   Upgrade: websocket
   Authorization: Bearer <43-character-device-secret>
   ```

The room identifier is derived as:

```text
base64url(SHA-256(US_ASCII(deviceSecret))).take(22)
```

The Worker compares the derived value with the requested room before forwarding
any message.

## Lifetime and replay rules

- A pairing code is exactly eight decimal digits and is valid for at most ten
  minutes.
- The phone supplies only its remaining monotonic duration. Retries never
  extend the original window.
- Publication epochs must increase. An exact retry of the current publication
  is idempotent; a delayed older publication cannot reactivate a code.
- A lookup slot is consumed once and retains a tombstone until its original
  expiry.
- Bootstrap object names are keyed HMAC values. Codes and client addresses are
  not exposed in object names.
- IPv4 matching is exact; IPv6 privacy addresses are grouped by canonical /64.
- Lookup errors are deliberately generic.

The signed route cookie is valid for at most 180 days. Pairing-code expiry closes
only the code entry window; it does not invalidate an already remembered browser
route. Each successfully paired browser receives an independent route nonce, so
pairing a new browser does not revoke existing browser sessions.

Cookie format v3 authenticates both issuance and expiry time. Existing v2 cookies
can reconnect but cannot authorize a new `/api/pair` request. The Worker exposes
`protocolVersion` in its health response and WebSocket `ready` frame so clients
can detect an incompatible implementation.

## Signaling envelope

Messages are UTF-8 JSON text objects no larger than 192 KiB.
`requestId` must match `^[A-Za-z0-9_-]{16,64}$`.

Browser-to-device request:

```json
{
  "type": "rpc_request",
  "requestId": "YzM2N2Q5NTU2YjI4M2Q4MG",
  "method": "POST",
  "target": "/api/webrtc/session?codec=auto",
  "headers": {
    "accept": "application/json",
    "content-type": "application/sdp"
  },
  "bodyBase64": "dj0wLi4u"
}
```

Device-to-browser response:

```json
{
  "type": "rpc_response",
  "requestId": "YzM2N2Q5NTU2YjI4M2Q4MG",
  "status": 200,
  "contentType": "application/json; charset=utf-8",
  "bodyBase64": "e30="
}
```

Only connection status, pairing, viewport metadata, and SDP/ICE endpoints are
accepted by the cloud relay. Touch RPC and general application payloads are
rejected before they reach the device. Binary frames, malformed JSON, oversized
messages, wrong-direction envelopes, and duplicate in-flight identifiers receive
the close or HTTP status defined by the protocol tests.

## Compatibility endpoint

The URL-routed `/ws/browser/<room-id>` endpoint is disabled by default. It exists
only for deployments that explicitly set
`ALLOW_LEGACY_BROWSER_ROOM_ROUTE=true`. New deployments should use the signed
cookie route.
