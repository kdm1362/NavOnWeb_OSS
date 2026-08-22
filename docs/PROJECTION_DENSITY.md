# Profile-specific Android Auto projection density

## Purpose

NavOnWeb lets the user tune Android Auto UI scale independently for the Basic, 720p, and 1080p
video profiles. Density affects Android Auto layout only; it does not change encoded resolution,
WebRTC bitrate, premium entitlement, or browser authorization.

The recommendations are **140 DPI for Basic (800x480), 200 DPI for 720p, and 220 DPI for 1080p**.
The logical Android Auto workspace widens as encoded resolution grows: roughly 914, 1024, and
1396 density-independent pixels. Higher profiles therefore show more content at a smaller
apparent UI size rather than the same layout at a larger frame.
“Use recommended” removes the stored override instead of writing another fixed value, so a later
recommendation can take effect automatically.

## Trusted settings path

1. The Android settings screen accepts every whole-number DPI from 72 through 320. It keeps the
   user's draft unchanged while typing, then validates it on Apply, IME completion, or focus loss.
2. `ProjectionDpiSettingsManager` stores one app-local override per profile. Missing, corrupt, or
   out-of-range values fail closed to that profile's recommendation.
3. A stored value is the profile's nominal landscape reference. Basic uses a 720x1280 protocol
   frame in portrait. That is a 720p-class frame, so its wire DPI follows the 720p profile's
   recommendation: `round(configured * 200 / 140)`, clamped to the same 72–320 range. The
   baseline is read from the 720p profile rather than duplicated, so changing one recommendation
   cannot leave the other stale. The settings row shows both actual values; for example, the
   recommended Basic setting applies as 140 DPI in landscape and 200 DPI in portrait, while 78
   applies as 78 and 111 DPI. The other profiles use their stored value in both orientations.
4. The effective value is copied into `ProjectionViewportLayout` and `OpenAutoConfig` only after
   inclusive range validation succeeds at the manager, layout, and protocol boundaries.
5. Android Auto Service Discovery advertises the effective density with the active resolution and
   margins. The browser may read this metadata but has no API for changing it.

The browser continues to report only viewport width, height, and bounded device-pixel ratio.
Browser geometry selects margins and landscape/portrait encoded geometry; it never derives or
supplies DPI.

## Runtime behavior

Changing the active profile's DPI requires Android Auto Service Discovery to run again. NavOnWeb
therefore reconnects only the Android Auto runtime while preserving the existing WebRTC sender,
HTTP server, pairing state, browser media peer, and current frame. Changing an inactive profile
only saves its value; the value is applied when that profile next becomes active.

Editing does not change a running session. The value is committed only on Apply, IME completion,
or focus loss. Rapid accepted changes are still coalesced by the viewport apply scheduler.

## Safety limits

- Density: every integer from 72 through 320 DPI, inclusive.
- Recommended values: Basic 140 DPI, 720p 200 DPI, and 1080p 220 DPI.
- Browser edges: 1 through 16,384 CSS pixels.
- Browser DPR: finite and 0.5 through 8.0; it does not alter the selected DPI.
- Remaining Android Auto content: at least 216×240 encoded pixels.
- Margin quantum/hysteresis: 8/16 pixels.
- Arbitrary browser-supplied density is never accepted.

## Verification

Unit coverage includes independent profile persistence, corrupt-value fallback, recommendation
reset, persistence failures, inclusive-range enforcement, viewport scheduling, Service Discovery
protobuf output, live browser metadata, and preservation of WebRTC on DPI-only changes. Physical
device testing should additionally confirm the preferred UI scale for each target head unit.
