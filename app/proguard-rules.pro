-keepclasseswithmembernames class * {
    native <methods>;
}

# libwebrtc's generated JNI bridge reaches Java callbacks from native code.
# Keep the upstream API surface until the pinned AAR supplies consumer R8 rules.
-keep class org.webrtc.** { *; }

# Newer libwebrtc builds bootstrap JNI through the standalone jni_zero package.
# Native code looks up JniInit by its original name, so R8 must not remove or rename it.
-keep class org.jni_zero.** { *; }

# The WebRTC bridge is reached through JNI and reflection-sensitive callbacks.
# Keep its public API in release builds.
-keep class com.pebble.tecomheadunit.browser.webrtc.** { *; }
