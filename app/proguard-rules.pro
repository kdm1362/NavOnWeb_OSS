-keepclasseswithmembernames class * {
    native <methods>;
}

# libwebrtc's generated JNI bridge reaches Java callbacks from native code.
# Keep the upstream API surface until the pinned AAR supplies consumer R8 rules.
-keep class org.webrtc.** { *; }

# Newer libwebrtc builds bootstrap JNI through the standalone jni_zero package.
# Native code looks up JniInit by its original name, so R8 must not remove or rename it.
-keep class org.jni_zero.** { *; }

# The native sender is connected to ProjectionService in a later wiring step. Keep its public
# bridge API in release builds even while the current service still uses the JPEG debug fallback.
-keep class com.pebble.tecomheadunit.browser.webrtc.** { *; }
