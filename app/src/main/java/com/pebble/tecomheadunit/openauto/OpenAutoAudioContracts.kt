/*
 * Android adaptation of OpenAuto projection audio interfaces.
 * Copyright (C) 2018 f1x.studio (Michal Szwaj)
 * Modifications Copyright (C) 2026 Tecom Head Unit contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.pebble.tecomheadunit.openauto

import java.io.IOException

/**
 * Audio channels in the pinned OpenAuto service discovery response.
 *
 * The pinned protocol calls channel 5 SPEECH. [GUIDANCE] is the public Android-facing name used
 * here because that mono stream carries navigation/assistant guidance in the target integration.
 */
enum class OpenAutoAudioChannel(
    val aasdkChannelId: Int,
    val format: OpenAutoPcmFormat,
) {
    MEDIA(
        aasdkChannelId = 4,
        format = OpenAutoPcmFormat(sampleRateHz = 48_000, channelCount = 2),
    ),
    GUIDANCE(
        aasdkChannelId = 5,
        format = OpenAutoPcmFormat(sampleRateHz = 16_000, channelCount = 1),
    ),
    SYSTEM(
        aasdkChannelId = 6,
        format = OpenAutoPcmFormat(sampleRateHz = 16_000, channelCount = 1),
    );

    companion object {
        fun fromAasdkChannelId(channelId: Int): OpenAutoAudioChannel? =
            entries.firstOrNull { it.aasdkChannelId == channelId }
    }
}

/** Only the codec advertised by the pinned OpenAuto/AASDK audio descriptors. */
enum class OpenAutoAudioCodec {
    PCM_SIGNED_16_LE,
}

data class OpenAutoPcmFormat(
    val sampleRateHz: Int,
    val channelCount: Int,
    val bitDepthBits: Int = 16,
    val codec: OpenAutoAudioCodec = OpenAutoAudioCodec.PCM_SIGNED_16_LE,
) {
    init {
        require(sampleRateHz > 0) { "sample rate must be positive" }
        require(channelCount in 1..2) { "unsupported PCM channel count" }
        require(bitDepthBits == 16) { "only signed 16-bit PCM is advertised" }
        require(codec == OpenAutoAudioCodec.PCM_SIGNED_16_LE) {
            "only little-endian PCM is advertised"
        }
    }

    val bytesPerFrame: Int = channelCount * (bitDepthBits / 8)
}

/**
 * One owned PCM packet. [bytes] contains only sample data, never the AASDK id or timestamp.
 * [rawTimestamp] is the optional unsigned AASDK transport timestamp constrained to [Long].
 */
data class OpenAutoPcmFrame(
    val channel: OpenAutoAudioChannel,
    val format: OpenAutoPcmFormat,
    val rawTimestamp: Long?,
    val bytes: ByteArray,
) {
    init {
        require(format == channel.format) { "PCM format does not match channel advertisement" }
        require(rawTimestamp == null || rawTimestamp >= 0L) { "invalid PCM timestamp" }
        require(bytes.isNotEmpty()) { "PCM packet is empty" }
        require(bytes.size % format.bytesPerFrame == 0) { "PCM packet is not sample-frame aligned" }
    }

    val sampleFrameCount: Int = bytes.size / format.bytesPerFrame
}

enum class OpenAutoAudioFocus {
    NONE,
    GAIN,
    GAIN_TRANSIENT,
    GAIN_NAVIGATION,
    RELEASE,
}

/**
 * Synchronous callback boundary for a PCM consumer such as a WebRTC source or debug AudioTrack.
 *
 * Returning true means the consumer accepted the operation. The wire controller emits a media
 * ACK only after [onPcmFrame] returns true, preserving the pinned max-unacked=1 backpressure.
 */
interface OpenAutoPcmSink {
    fun onOpen(channel: OpenAutoAudioChannel, format: OpenAutoPcmFormat): Boolean
    fun onStart(channel: OpenAutoAudioChannel): Boolean
    fun onPcmFrame(frame: OpenAutoPcmFrame): Boolean
    fun onSuspend(channel: OpenAutoAudioChannel)
    fun onClose(channel: OpenAutoAudioChannel)
    fun onAudioFocusChanged(focus: OpenAutoAudioFocus)
}

internal enum class AasdkAudioSinkFailure {
    START_REJECTED,
    PCM_REJECTED,
    CALLBACK_FAILED,
}

/** A local PCM consumer failure; it is not misreported as malformed peer data. */
internal class AasdkAudioSinkException(
    val failure: AasdkAudioSinkFailure,
    cause: Throwable? = null,
) : IOException("AASDK audio sink failure: ${failure.name}", cause)
