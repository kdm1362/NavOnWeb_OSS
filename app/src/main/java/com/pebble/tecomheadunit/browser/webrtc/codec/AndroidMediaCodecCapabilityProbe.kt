/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.pebble.tecomheadunit.browser.webrtc.codec

import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.os.Build

/** Reads the encoder capabilities of the Android device that is running the head-unit app. */
internal class AndroidMediaCodecCapabilityProbe {
    fun probe(
        width: Int,
        height: Int,
        framesPerSecond: Int,
    ): MediaCodecCapabilitySnapshot {
        require(width > 0) { "width must be positive" }
        require(height > 0) { "height must be positive" }
        require(framesPerSecond > 0) { "framesPerSecond must be positive" }

        val encoders = buildList {
            MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos
                .asSequence()
                .filter(MediaCodecInfo::isEncoder)
                .forEach { codecInfo ->
                    val acceleration = codecInfo.acceleration()
                    codecInfo.supportedTypes.forEach { supportedType ->
                        val codec = WebRtcVideoCodec.entries.firstOrNull {
                            it.mimeType.equals(supportedType, ignoreCase = true)
                        } ?: return@forEach
                        add(codecInfo.capabilityFor(codec, supportedType, acceleration, width, height, framesPerSecond))
                    }
                }
        }

        return MediaCodecCapabilitySnapshot(
            width = width,
            height = height,
            framesPerSecond = framesPerSecond,
            encoders = encoders.sortedWith(
                compareBy<MediaCodecEncoderCapability> { it.codec.ordinal }
                    .thenBy { it.encoderName },
            ),
        )
    }

    private fun MediaCodecInfo.capabilityFor(
        codec: WebRtcVideoCodec,
        supportedType: String,
        acceleration: EncoderAcceleration,
        width: Int,
        height: Int,
        framesPerSecond: Int,
    ): MediaCodecEncoderCapability {
        val codecCapabilities = runCatching { getCapabilitiesForType(supportedType) }.getOrNull()
        val supportsSurfaceInput = codecCapabilities?.colorFormats?.any {
            it == MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface
        } == true
        val supportsSizeAndRate = runCatching {
            codecCapabilities?.videoCapabilities?.areSizeAndRateSupported(
                width,
                height,
                framesPerSecond.toDouble(),
            ) == true
        }.getOrDefault(false)

        return MediaCodecEncoderCapability(
            codec = codec,
            encoderName = name,
            acceleration = acceleration,
            supportsSurfaceInput = supportsSurfaceInput,
            supportsRequestedSizeAndRate = supportsSizeAndRate,
        )
    }

    private fun MediaCodecInfo.acceleration(): EncoderAcceleration {
        val platformHardware = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            isHardwareAccelerated
        } else {
            null
        }
        val platformSoftware = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            isSoftwareOnly
        } else {
            null
        }
        return EncoderAccelerationClassifier.classify(
            codecName = name,
            platformHardwareAccelerated = platformHardware,
            platformSoftwareOnly = platformSoftware,
        )
    }
}

/** Pure classifier kept separately so the conservative pre-Android-10 behavior is JVM-testable. */
internal object EncoderAccelerationClassifier {
    private val knownSoftwarePrefixes = listOf(
        "c2.android.",
        "c2.google.",
        "omx.google.",
        "omx.ffmpeg.",
    )

    fun classify(
        codecName: String,
        platformHardwareAccelerated: Boolean?,
        platformSoftwareOnly: Boolean?,
    ): EncoderAcceleration {
        if (platformHardwareAccelerated == true) return EncoderAcceleration.HARDWARE
        if (platformSoftwareOnly == true) return EncoderAcceleration.SOFTWARE

        val normalizedName = codecName.trim().lowercase()
        if (knownSoftwarePrefixes.any(normalizedName::startsWith)) {
            return EncoderAcceleration.SOFTWARE
        }
        return EncoderAcceleration.UNKNOWN
    }
}
