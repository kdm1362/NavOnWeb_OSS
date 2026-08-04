# Building and testing

## Prerequisites

- Windows, macOS, or Linux
- JDK 17
- Android SDK Platform 36 and Android SDK Build Tools installed through Android Studio

The Gradle wrapper and dependency versions are committed. Configure the Android SDK with `ANDROID_HOME`/`ANDROID_SDK_ROOT`, or create an untracked `local.properties` containing `sdk.dir=<path>`.

On Windows, place the checkout in an ASCII-only path such as `C:\src\NavOnWeb_OSS`. Some JDK/Gradle test runners cannot load compiled test classes when the checkout path contains non-ASCII characters.

## Android application

On Windows:

```powershell
.\gradlew.bat testDebugUnitTest lintDebug assembleDebug
```

On macOS or Linux:

```sh
./gradlew testDebugUnitTest lintDebug assembleDebug
```

To compile an unsigned release bundle without embedding deployment secrets:

```powershell
.\gradlew.bat bundleRelease `
  -PsourceCodeUrl=https://github.com/kdm1362/NavOnWeb_OSS
```

Signing material must be supplied outside the repository. If the optional release identity pin properties are used, pass only certificate fingerprints as Gradle properties; never add certificates or private keys to the source tree:

```text
productionAasdkIdentityLeafSha256
productionAasdkIdentityAnchorSha256
productionAasdkPhonePeerLeafSha256
```

The Android Auto identity itself is a runtime/deployment input. A build without a provisioned identity can compile and run its non-projection functions but cannot authenticate an Android Auto projection session.

The optional JNI scaffold can be compiled with:

```powershell
.\gradlew.bat assembleDebug -PenableNativeOpenAuto=true
```

This flag compiles the repository's JNI scaffold; it does not link an external OpenAuto runtime.

The preferred launcher artwork is `app/src/main/res/drawable-nodpi/navonweb_icon.png`. Density-specific launcher PNGs can be regenerated on Windows with:

```powershell
.\tools\generate-launcher-icons.ps1
```

## Dependency inventory

Android dependency versions are declared in `gradle/libs.versions.toml`. Generate a fresh Android dependency report with:

```powershell
.\gradlew.bat :app:dependencies
```
