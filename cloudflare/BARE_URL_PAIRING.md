# Bare URL browser pairing

The production page can be opened as `https://navonweb.com` without putting a
device identifier in the URL. Android uses the `signal.navonweb.com` Worker
custom domain, while browsers use the same-origin `https://navonweb.com/_nw/*`
route. The same-origin browser route avoids third-party request blocking and
keeps the signed route cookie host-only.

## Required Worker configuration

Keep the route-signing key out of source control and configure it separately in
every Worker environment:

```powershell
npx wrangler secret put BOOTSTRAP_HMAC_KEY --config worker/wrangler.toml
```

Use at least 32 cryptographically random bytes. Also add the exact Pages/custom
domain origin to `ALLOWED_BROWSER_ORIGINS`. Pairing fails closed if the secret or
origin allowlist is missing.

## Protocol

1. Android maintains its authenticated device WebSocket. It registers a
   one-time code only during the initial ten-minute window or after the user
   taps `Pair a new browser` in the phone app:

   ```http
   POST /bootstrap/device
   Authorization: Bearer <device-secret>
   X-NavOnWeb-Pairing-Epoch: <monotonic-positive-integer>
   X-NavOnWeb-Pairing-Ttl-Millis: <remaining-duration-minus-transport-guard>
   Content-Type: text/plain; charset=utf-8

   12345678
   ```

2. The browser submits the same code from the phone hotspot connection:

   ```http
   POST /_nw/bootstrap/pair
   Origin: https://navonweb.com
   Content-Type: text/plain; charset=utf-8

   12345678
   ```

   Browser requests must include credentials. A successful response is `204`
   and sets the `__Host-navonweb_route` cookie.

3. The browser opens `wss://navonweb.com/_nw/ws/browser`. The Worker verifies
   the `HttpOnly; Secure; SameSite=Strict` signed route cookie and selects the
   Durable Object room without exposing its identifier in the URL.

The Android and browser registration requests are matched using keyed HMAC
identifiers derived from the room, publication epoch, current eight-digit code,
and normalized Cloudflare client egress address. IPv4 is exact and IPv6 uses
its `/64` prefix. Android persists a strictly increasing publication epoch and
reuses it only while retrying the same code. The room accepts only a newer epoch
or an exact retry, so a delayed request for an older code cannot reactivate it.

The lookup slot lasts at most ten minutes, is consumed once, and retains a
`consumed` tombstone until its original expiry. A device reconnect therefore
cannot republish an already used code. Failed browser lookups are generic and
the slots never form a global code directory. Browser installations are limited
to 12 lookup attempts per ten-minute window. Devices are limited to 12 distinct
code publications in that window; exact registration retries reuse the same
publication budget but still count against the separate high network/replay
ceiling. This avoids self-throttling during transient failures without creating
an unlimited OSS replay path.

The phone owns the pairing deadline using its monotonic clock. Every registration
retry sends only the remaining duration and never creates a new ten-minute window.
Android subtracts the registration call's 20-second timeout before publishing the
duration; if 20 seconds or less remain, it fails closed without sending a request.
The Worker fixes the absolute expiry on the first reservation, returns that same
expiry on exact retries, and propagates it through consumption and the pending
browser route. Consequently, a browser route prepared just before expiry cannot be
promoted at or after expiry. Duration transfer avoids wall-clock skew between the
phone and Cloudflare; the transport guard prevents request transit from making the
Worker deadline outlive the phone deadline.

## Breaking protocol rollout

The TTL header and eight-digit code are mandatory and intentionally have no legacy
fallback. The Android app and Worker must therefore be released in one coordinated
maintenance window. Neither mixed order supports new pairing: an old app sends a
six-digit code without the TTL header, while a new app sends an eight-digit code an
old Worker rejects. Existing remembered sessions may also require the recovery flow
because protocol v2 invalidates v1 route cookies. For the initial/internal release,
stage both artifacts, deploy Worker protocol v2 and install the matching Android
build back-to-back, then verify an unused code expires and a consumed code remains
unavailable across reconnects. Do not leave mixed versions in production.
The Worker reports `protocolVersion: 2` in its health response and WebSocket ready
frame so a mixed or stale deployment can be identified operationally.

The remembered signed route expires after 180 days; the phone's existing
browser credential remains the application-level authorization. Code expiry
closes only the pairing window and does not expire either remembered
authorization.

The legacy `/ws/browser/<room-id>` endpoint is disabled by default. It can be
opened only for a controlled migration by explicitly setting
`ALLOW_LEGACY_BROWSER_ROOM_ROUTE=true`; production pages use the cookie-routed
endpoint.
