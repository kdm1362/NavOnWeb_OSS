# NavOnWeb modifications

Exact upstream repositories, revisions, and retained-file hashes are recorded in `third_party/UPSTREAM.lock`. License notices are collected in `THIRD_PARTY_NOTICES.md`.

## OpenAuto-derived interfaces

Selected OpenAuto projection interface and service files are retained under `third_party/openauto-reference/` with their original notices.

`app/src/main/cpp/port/ProjectionTypes.hpp` adapts viewport, margin, and touch-coordinate concepts to dependency-free C++ types suitable for Android JNI checks. The Android application does not build the upstream Qt user interface.

## AASDK protocol adaptation

The Kotlin classes under `app/src/main/java/com/eigenkodex/navonweb/openauto/protocol/` implement the framing, bootstrap, TLS channel, service discovery, channel lifecycle, and media packet behavior used by the Android runtime.

The upstream AASDK C++ library is not linked or bundled. Android Auto identity material is supplied separately from source and build metadata.

## NavOnWeb implementation

NavOnWeb adds the Android UI and foreground-service lifecycle, MediaCodec projection pipeline, local and relay browser signaling, WebRTC video, browser audio and microphone handling, pairing/session management, touch and viewport mapping, automation, billing integration, notices, and opt-in diagnostics.

These NavOnWeb-specific sources are licensed GPL-3.0-or-later.
