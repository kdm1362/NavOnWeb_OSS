# Projection profiles and viewport behavior

NavOnWeb uses a closed set of projection profiles. Browser-supplied dimensions can influence orientation and margins, but cannot create arbitrary Android Auto resolutions or change the app's access state.

## Profiles

| Profile | Access | Android Auto frame | AA advertised FPS | WebRTC FPS ceiling | WebRTC bitrate range |
|---|---|---:|---:|---:|---:|
| Basic | Free | 800×480 | 60 | 30 | 0.5–4 Mbps |
| HD | Premium | 1280×720 | 60 | 30 | 1–8 Mbps |
| Full HD | Premium | 1920×1080 | 60 | 30 | 2–14 Mbps |

Premium availability is resolved by the Android application. The browser can read the active profile but cannot grant access or mutate entitlement state.

## Applying a profile

A profile changes the Android Auto service-discovery geometry, decoder, touch mapping, and WebRTC source together. When a running session changes profile, NavOnWeb prepares the new video pipeline and reconnects the Android Auto runtime from service discovery.

The foreground service, HTTP server, pairing state, and browser page remain active during this transition. Codec-only changes recreate the WebRTC media session without restarting the Android Auto connection.

## Orientation and aspect ratio

Each profile supports a landscape and portrait protocol frame. The browser reports a bounded viewport width, height, and device-pixel ratio. NavOnWeb uses those values to choose orientation and a centered content rectangle within the selected profile; it does not accept a browser-provided DPI or output resolution.

The standard browser view predicts the area that will be available after entering fullscreen. This lets the app prepare the matching Android Auto viewport before presentation changes. Native fullscreen and the theater-mode fallback use the same prediction, reducing unnecessary reconnections during entry and exit.

Viewport margins are quantized and use hysteresis so small browser layout fluctuations do not repeatedly reconnect Android Auto. A newer viewport request supersedes an older pending request.

Touch coordinates are mapped only inside the active content rectangle. Starting a second pointer cancels the current single-pointer Android Auto gesture, and an orientation or viewport change cancels any gesture still in progress.

## Codec selection

The phone app's video codec choices are `AUTO`, H.264, VP8, VP9, and AV1; the browser page exposes no profile or codec controls. `AUTO` prefers a hardware H.264 encoder within the intersection of the phone's encoders and the browser's advertised decoders. Explicit VP9 and AV1 choices may use software encoders.

If WebRTC is unavailable, NavOnWeb can show the authenticated JPEG fallback at a much lower frame rate (roughly 3-5 fps depending on the profile); full-rate delivery is provided only by the WebRTC path.

## Density

Density is saved independently for Basic, HD, and Full HD. It changes Android Auto's UI scale without changing the encoded resolution or bitrate. See [PROJECTION_DENSITY.md](PROJECTION_DENSITY.md).

## Limits

- Actual frame rate and bitrate vary by encoder, browser, network quality, and device temperature.
- Full HD has the highest memory, bandwidth, and thermal cost.
- The browser must wait for the video and input channels to become ready before touch input is accepted.
- Orientation and margin changes restart only the Android Auto viewport path, but a short interruption in projected video can still occur.
