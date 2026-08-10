# NavOnWeb

NavOnWeb is an Android application that receives an Android Auto projection session on a phone and makes the session available to a web browser on the same local network. A paired browser can display the projected video, send touch input, and use the browser audio and microphone paths supported by the active session.

The repository contains the Android application, the browser client packaged with it, the public Cloudflare Worker/Pages implementation, build files, tests, and third-party notices.

Google Play: listing URL to be added after publication.

## Features

- Same-phone connection to Android Auto's Developer Head Unit Server
- Pairing-code protected browser access
- WebRTC video with automatic codec selection and a JPEG fallback
- Touch forwarding and fullscreen/orientation-aware viewport handling
- Basic, 720p, and 1080p projection profiles with per-profile density settings
- Browser audio and microphone data paths
- Optional Bluetooth or Wi-Fi hotspot automation
- Local-network operation with an optional configured public browser relay

See [same-phone mode](docs/SAME_PHONE_MODE.md), [projection architecture](docs/OPENAUTO_PORT.md), and [projection profiles](docs/PROJECTION_PROFILES.md) for current behavior and limitations.

## Requirements

- Android 8.0 or newer on the phone running NavOnWeb
- Android Auto with Developer Head Unit Server support
- A browser reachable over the same local network, unless a relay deployment is configured

Hotspot state automation requires Android 16/API 36. Android and device-vendor background policies can stop services or delay automatic restoration.

## Build

See [docs/BUILDING.md](docs/BUILDING.md) for Android and web build instructions. Signing material, Android Auto identity material, and deployed-service credentials are external inputs and are not stored in this repository.

## License and source

NavOnWeb is licensed under GNU GPL version 3 or, at your option, any later version. See [LICENSE](LICENSE).

Notices for bundled and principal runtime dependencies, together with retained upstream source, are collected in [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md). Exact revisions for the retained and referenced upstream projects are recorded in [third_party/UPSTREAM.lock](third_party/UPSTREAM.lock). NavOnWeb changes are summarized in [docs/MODIFICATIONS.md](docs/MODIFICATIONS.md).

Each distributed binary should identify an immutable commit or tag containing its matching source. The release-source process is described in [docs/SOURCE_DISTRIBUTION.md](docs/SOURCE_DISTRIBUTION.md).

Android, Android Auto, Google Play, Cloudflare, Supabase, WebRTC, and Tesla are trademarks of their respective owners. Their names identify compatible platforms or external services and do not imply endorsement.
