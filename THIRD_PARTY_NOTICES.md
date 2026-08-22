# Third-party notices

Exact upstream revisions and retained-file hashes are recorded in [third_party/UPSTREAM.lock](third_party/UPSTREAM.lock).

## OpenAuto

- Project: `f1xpl/openauto`
- Upstream: https://github.com/f1xpl/openauto
- License: GNU General Public License v3.0 or later
- Use: selected projection interfaces and service sources are retained under `third_party/openauto-reference/`; NavOnWeb provides an Android-oriented implementation.

Retained files keep their original notices. NavOnWeb is distributed under GPL-3.0-or-later.

## AASDK

- Project: `f1xpl/aasdk`
- Upstream: https://github.com/f1xpl/aasdk
- License: GNU General Public License v3.0 or later
- Use: protocol behavior and channel handling inform the Kotlin implementation under `app/src/main/java/com/eigenkodex/navonweb/openauto/`
- Bundled upstream source: no

## open-headunit

- Project: `andreknieriem/open-headunit`
- Upstream: https://github.com/andreknieriem/open-headunit
- License: GNU Affero General Public License v3.0 or later
- Original notice: Headunit for Android Auto, copyright 2011-2015 Michael A. Reid
- Use: architecture and interoperability reference
- Bundled upstream source: no

## WebRTC Android SDK

- Maven coordinate: `io.github.webrtc-sdk:android:144.7559.09`
- Project: https://github.com/webrtc-sdk/android
- Upstream WebRTC: https://webrtc.googlesource.com/src/
- License: BSD 3-Clause, plus the notices distributed with the artifact and upstream source
- Bundled license text: [`third_party/licenses/WebRTC-BSD-3-Clause.txt`](third_party/licenses/WebRTC-BSD-3-Clause.txt)

## OkHttp and Okio

- OkHttp coordinate: `com.squareup.okhttp3:okhttp:5.3.0`
- OkHttp project: https://github.com/square/okhttp
- Okio project: https://github.com/square/okio
- License: Apache License 2.0

OkHttp bundles a compiled copy of the Public Suffix List. The list is maintained at
https://publicsuffix.org/list/ and is licensed under MPL-2.0; its notice is preserved in
[`third_party/licenses/Public-Suffix-List-NOTICE.txt`](third_party/licenses/Public-Suffix-List-NOTICE.txt)
and the complete license text is in
[`third_party/licenses/MPL-2.0.txt`](third_party/licenses/MPL-2.0.txt).

## AndroidX, Jetpack Compose, Kotlin, and Kotlin coroutines

Versions are declared in `gradle/libs.versions.toml` and resolved through Gradle. These projects are generally licensed under Apache License 2.0; artifact metadata and included notices control where a component differs.

## Google Play Billing Library

- Coordinate: `com.android.billingclient:billing:9.1.0`
- Provider: Google
- Terms and notices: distributed with the library and its documentation

## JUnit

- Coordinate: `junit:junit:4.13.2`
- Project: https://github.com/junit-team/junit4
- License: Eclipse Public License 1.0

## JSON in Java

- Coordinate: `org.json:json:20240303`
- Project: https://github.com/stleary/JSON-java
- Scope: local JVM tests only; it is not an application runtime dependency
- License declaration: the published Maven POM identifies the release as Public Domain

## Gradle Wrapper

- Project: https://github.com/gradle/gradle
- Distribution selected by `gradle/wrapper/gradle-wrapper.properties`: Gradle 8.13
- Tracked bootstrap component: `gradle/wrapper/gradle-wrapper.jar`
- License: Apache License 2.0
- Gradle distribution license and notice:
  [`third_party/licenses/Gradle-8.13-LICENSE.txt`](third_party/licenses/Gradle-8.13-LICENSE.txt) and
  [`third_party/licenses/Gradle-8.13-NOTICE.txt`](third_party/licenses/Gradle-8.13-NOTICE.txt)

The complete Apache License 2.0 text used by the Gradle wrapper, OkHttp/Okio, AndroidX,
Jetpack Compose, Kotlin, and other identified Apache-2.0 components is preserved in
[`third_party/licenses/Apache-2.0.txt`](third_party/licenses/Apache-2.0.txt).

## Cloudflare development dependencies

The project under `cloudflare/` uses Wrangler and its transitive packages for local development, testing, and deployment commands. `cloudflare/package-lock.json` fixes the resolved package versions. Each package remains subject to the license declared by that package in the lockfile or its published package metadata; this notice does not replace those package-specific terms.

## Android packaging note

The Android build excludes duplicate merged dependency resources named `/META-INF/AL2.0` and
`/META-INF/LGPL2.1`. It separately packages this notice and the NavOnWeb GPL license as application assets;
the canonical third-party license texts are provided under `third_party/licenses/` in the
corresponding source repository.
Distributors should still inspect resolved artifacts and preserve any additional notices required
by an individual dependency.

## Trademarks

Android, Android Auto, Google Play, WebRTC, Cloudflare, and Supabase are trademarks of their respective owners. Their names identify compatibility targets or external services and do not imply endorsement.
