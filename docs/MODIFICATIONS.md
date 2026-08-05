# Modifications and provenance

This document distinguishes retained upstream source, adapted behavior, and reference-only material. Exact revisions and hashes are recorded in `third_party/UPSTREAM.lock`.

Last updated: 2026-08-05.

## OpenAuto

Upstream: `f1xpl/openauto` at `aa90412bf93b5a5078495ea85ac9270c6297d369`.

- `third_party/openauto-reference/**` retains selected upstream projection interface and service files. These files are included for attribution and source correspondence; their SHA-256 values are locked.
- `app/src/main/cpp/port/ProjectionTypes.hpp` is an Android-oriented adaptation of the upstream projection input and viewport concepts. It replaces Qt/desktop types with dependency-free C++ value types and coordinate conversion helpers.
- Android code under `app/src/main/java/com/pebble/tecomheadunit/openauto/` implements projection, audio, microphone, input, sensor, and session behavior for Android rather than building the upstream Qt application.

## AASDK

Upstream: `f1xpl/aasdk` at `046b3b381595509d0939fa84b14a90978f46ff63`.

- The upstream C++ library is not linked or bundled.
- Kotlin source under `app/src/main/java/com/pebble/tecomheadunit/openauto/protocol/` adapts the wire framing, bootstrap, TLS channel, service/channel lifecycle, and media packet behavior needed by the Android implementation.
- `AasdkOpenAutoProtocol.kt` records both the AASDK and OpenAuto revisions used for protocol constants and the audited service-discovery fixture.
- Runtime certificates and private keys are not derived source and are deliberately excluded.

## open-headunit

Upstream: `andreknieriem/open-headunit` at `738b07ff7e765d9b570d27dee6d15901ad30b80c`, revived from `mikereidis/headunit`.

- Relationship: architecture and interoperability reference only.
- No open-headunit implementation source, fixture, build file, or credential asset is included in this curated public source tree or in the Android package inputs under `app/src/`.
- Its upstream AGPL-3.0-or-later license and Michael A. Reid attribution are recorded in the notices for provenance; NavOnWeb does not claim that material as GPL-licensed NavOnWeb code.

## NavOnWeb-specific implementation

The Android UI, foreground service and automation, WebRTC browser bridge, local HTTP control surface, pairing/session management, billing integration, diagnostics client, viewport and touch mapping, and bundled browser client were implemented for NavOnWeb and are licensed GPL-3.0-or-later.
