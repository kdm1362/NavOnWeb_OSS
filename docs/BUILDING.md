# Building and testing

## Prerequisites

- JDK 17
- Android SDK Platform 36 and Android SDK Build Tools
- Node.js 22 or newer for the Cloudflare project

Use `ANDROID_HOME`, `ANDROID_SDK_ROOT`, or an untracked `local.properties` file containing `sdk.dir=<path>` to locate the Android SDK.

On Windows, an ASCII-only checkout path such as `C:\src\NavOnWeb_OSS` avoids class-loading problems in some JDK and Gradle combinations.

## Android application

Windows:

```powershell
.\gradlew.bat testDebugUnitTest lintDebug assembleDebug
```

macOS or Linux:

```sh
./gradlew testDebugUnitTest lintDebug assembleDebug
```

Debug output is written below `app/build/outputs/`. Build outputs are ignored by Git.

### Release source URL

An unsigned release bundle can be compiled without signing or AASDK identity files, but every release build must identify its immutable public source revision:

```powershell
.\gradlew.bat bundleRelease `
  -PsourceCodeUrl=https://github.com/kdm1362/NavOnWeb_OSS/tree/v0.1.13-p0-source
```

Debug builds use the repository root by default. Release builds accept only a versioned source tag such as `v0.1.13-p0-source` or a full 40-character commit under the repository's `/tree/` path.

Signing is optional for compilation. To sign a release, set `NAVONWEB_PLAY_UPLOAD_KEYSTORE`, `NAVONWEB_PLAY_UPLOAD_PASSWORD`, and optionally `NAVONWEB_PLAY_UPLOAD_KEY_ALIAS`. The keystore must remain outside the checkout.

### Public client configuration

The following optional Gradle properties configure external services. They can be supplied with `-P<name>=<value>` or through an untracked `local.properties` file:

- `supabaseUrl` and `supabasePublishableKey`
- `premiumProductId` and `premiumPurchaseOptionId`
- `cloudBrowserPageUrl` and `cloudSignalingWebSocketUrl`
- `reviewPromoApiUrl`, `reviewPromoEs256KeyId`, and `reviewPromoEs256PublicKeyDerBase64`

The public endpoint and verification values embedded in versionCode 23 are recorded in
[`config/public-client.properties.example`](../config/public-client.properties.example). Copy
those entries into an untracked `local.properties` file when reproducing the distributed client,
or provide a different compatible public client configuration through the same property names.

Service URLs, publishable client keys, key identifiers, and signature-verification public keys are public client configuration. Administrative tokens, service-role keys, signing private keys, database contents, and deployed secrets are not client configuration and must not be added to the repository.

Android Auto identity material and Android/Play signing material are also external build inputs. Use only credentials that you are authorized to use, and keep their files outside the checkout.

To include an externally supplied AASDK identity in a release build, provide all of the following Gradle properties together:

- `bundledAasdkCertificatePemPath`
- `bundledAasdkPrivateKeyPemPath`
- `aasdkIdentityLeafSha256`
- `aasdkIdentityAnchorSha256`

The PEM files must remain outside the checkout. Builds that omit these properties still compile, but the bundled identity provider remains unavailable at runtime.

### Optional native scaffold

The small JNI scaffold under `app/src/main/cpp/` can be compiled with:

```powershell
.\gradlew.bat assembleDebug -PenableNativeOpenAuto=true
```

This checks the native coordinate and viewport types. It does not link an external OpenAuto runtime; the application uses the Kotlin protocol implementation by default.

## Cloudflare Worker and Pages

The Worker, Pages client, and local tests are under `cloudflare/`:

```powershell
Set-Location cloudflare
npm ci
npm run check
npm test
```

Cloudflare account credentials, Wrangler state, environment files, and deployed secrets are not needed for local tests and must remain untracked.

## Dependency inventory

Android versions are declared in `gradle/libs.versions.toml`. To inspect the resolved graph:

```powershell
.\gradlew.bat :app:dependencies
```
