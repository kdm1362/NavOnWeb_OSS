-keepclasseswithmembernames class * {
    native <methods>;
}

# libwebrtc's generated JNI bridge reaches Java callbacks from native code.
# Keep the upstream API surface until the pinned AAR supplies consumer R8 rules.
-keep class org.webrtc.** { *; }

# The native sender is connected to ProjectionService in a later wiring step. Keep its public
# bridge API in release builds even while the current service still uses the JPEG debug fallback.
-keep class com.pebble.tecomheadunit.browser.webrtc.** { *; }
