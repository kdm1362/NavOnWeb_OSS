# Third-party notices

This file identifies code and design sources used by NavOnWeb. Exact upstream revisions are recorded in [third_party/UPSTREAM.lock](third_party/UPSTREAM.lock).

## OpenAuto

- Project: `f1xpl/openauto`
- Upstream: https://github.com/f1xpl/openauto
- License: GNU General Public License v3.0 or later
- Use in this repository: selected projection input, sensor, service-factory, and video-service source files are retained under `third_party/openauto-reference/`; NavOnWeb's Android implementation is a Kotlin adaptation and extension.

The selected upstream files retain their original notices. NavOnWeb as a combined work is distributed under GPL-3.0-or-later.

## AASDK

- Project: `f1xpl/aasdk`
- Upstream: https://github.com/f1xpl/aasdk
- License: GNU General Public License v3.0 or later
- Use in this repository: protocol behavior and service/channel handling informed the Kotlin implementation under `app/src/main/java/com/pebble/tecomheadunit/openauto/`.
- Bundled upstream source: no

No AASDK certificate, private key, or other identity credential is included in this repository.

## open-headunit

- Project: `andreknieriem/open-headunit`
- Upstream: https://github.com/andreknieriem/open-headunit
- License: GNU Affero General Public License v3.0 or later
- Original author notice: Headunit for Android Auto, copyright 2011-2015 Michael A. Reid
- Use in this repository: architecture and interoperability reference only
- Included material: no open-headunit program source, generated fixture, UI asset, build script, or credential asset is present in this curated public source tree or the Android package inputs under `app/src/`

Its AGPL license is recorded as provenance and is not presented as the license of NavOnWeb. Shared public protocol constants and standard Android/JDK idioms are interoperability facts rather than imported open-headunit program expression.

## WebRTC Android SDK

- Maven coordinate: `io.github.webrtc-sdk:android:144.7559.09`
- Project: https://github.com/webrtc-sdk/android
- Upstream WebRTC: https://webrtc.googlesource.com/src/
- License: BSD 3-Clause, with additional third-party notices in the distributed artifact and upstream source

## OkHttp and Okio

- OkHttp coordinate: `com.squareup.okhttp3:okhttp:5.3.0`
- Project: https://github.com/square/okhttp
- License: Apache License 2.0
- Okio is resolved transitively by Gradle; project: https://github.com/square/okio; license: Apache License 2.0

## AndroidX, Jetpack Compose, Kotlin, and Kotlin coroutines

AndroidX, Jetpack Compose, Android Gradle Plugin, Kotlin, and Kotlin coroutines are obtained from Google's Maven repository, Maven Central, and the Gradle Plugin Portal at the versions declared in `gradle/libs.versions.toml` and the Gradle dependency graph. These projects are generally distributed under Apache License 2.0; individual artifact metadata and bundled notices control where they differ.

## Google Play Billing Library

- Coordinate: `com.android.billingclient:billing:9.1.0`
- Provider: Google
- Terms and notices: supplied with the library and the Google Play Billing documentation

## JUnit

- Coordinate: `junit:junit:4.13.2`
- Project: https://github.com/junit-team/junit4
- License: Eclipse Public License 1.0

## Trademarks

Android, Android Auto, Google Play, WebRTC, Cloudflare, Supabase, and Tesla are trademarks of their respective owners. References identify compatibility targets and external service providers only and do not imply endorsement.
