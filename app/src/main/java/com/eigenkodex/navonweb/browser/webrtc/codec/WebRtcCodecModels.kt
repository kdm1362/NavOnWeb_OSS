/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.eigenkodex.navonweb.browser.webrtc.codec

/** Video codecs that can be offered by the browser WebRTC projection path. */
internal enum class WebRtcVideoCodec(
    val mimeType: String,
    val storageValue: String,
) {
    H264(mimeType = "video/avc", storageValue = "h264"),
    VP8(mimeType = "video/x-vnd.on2.vp8", storageValue = "vp8"),
    VP9(mimeType = "video/x-vnd.on2.vp9", storageValue = "vp9"),
    AV1(mimeType = "video/av01", storageValue = "av1"),
}

/**
 * Compile-time policy for the browser-facing WebRTC encoder.
 *
 * Android Auto projection still arrives as H.264 and is decoded by [MediaCodecVideoSink] before
 * this pipeline sees a GPU texture. The output encoder remains separately selectable so the
 * browser-facing stream can use H.264 when both Android and the browser advertise support.
 */
internal object WebRtcOutboundCodecPolicy {
    const val H264_ENCODER_ENABLED: Boolean = true

    val enabledCodecs: Set<WebRtcVideoCodec> = WebRtcVideoCodec.entries
        .filterTo(linkedSetOf(), ::isEncoderEnabled)

    fun isEncoderEnabled(codec: WebRtcVideoCodec): Boolean = when (codec) {
        WebRtcVideoCodec.H264 -> H264_ENCODER_ENABLED
        WebRtcVideoCodec.VP8,
        WebRtcVideoCodec.VP9,
        WebRtcVideoCodec.AV1,
        -> true
    }
}

/** User-visible codec choice. AUTO deliberately has no fixed codec. */
internal enum class WebRtcCodecPreference(
    val storageValue: String,
) {
    AUTO("auto"),
    H264("h264"),
    VP8("vp8"),
    VP9("vp9"),
    AV1("av1");

    val requestedCodec: WebRtcVideoCodec?
        get() = when (this) {
            AUTO -> null
            H264 -> WebRtcVideoCodec.H264
            VP8 -> WebRtcVideoCodec.VP8
            VP9 -> WebRtcVideoCodec.VP9
            AV1 -> WebRtcVideoCodec.AV1
        }

    companion object {
        /** Unknown, corrupt, or disabled persisted values fail closed to adaptive AUTO. */
        fun fromStorageValue(value: String?): WebRtcCodecPreference {
            val normalized = value?.trim()?.lowercase().orEmpty()
            val parsed = entries.firstOrNull { it.storageValue == normalized } ?: AUTO
            return parsed.takeIf { preference ->
                preference.requestedCodec?.let(WebRtcOutboundCodecPolicy::isEncoderEnabled) != false
            } ?: AUTO
        }
    }
}

/**
 * UNKNOWN is kept distinct from hardware. In particular, an encoder on Android 8/9 must not be
 * advertised as hardware accelerated merely because it is not a known Google software codec.
 */
internal enum class EncoderAcceleration {
    HARDWARE,
    SOFTWARE,
    UNKNOWN,
}

internal data class MediaCodecEncoderCapability(
    val codec: WebRtcVideoCodec,
    val encoderName: String,
    val acceleration: EncoderAcceleration,
    val supportsSurfaceInput: Boolean,
    val supportsRequestedSizeAndRate: Boolean,
) {
    init {
        require(encoderName.isNotBlank()) { "encoder name is blank" }
    }

    val isUsable: Boolean
        get() = supportsSurfaceInput && supportsRequestedSizeAndRate
}

/** Immutable result of one MediaCodecList probe for the intended WebRTC output format. */
internal data class MediaCodecCapabilitySnapshot(
    val width: Int,
    val height: Int,
    val framesPerSecond: Int,
    val encoders: List<MediaCodecEncoderCapability>,
) {
    init {
        require(width > 0) { "width must be positive" }
        require(height > 0) { "height must be positive" }
        require(framesPerSecond > 0) { "framesPerSecond must be positive" }
    }
}

/** Browser capabilities must come from its SDP/capability probe, not its user-agent string. */
internal data class RemoteVideoCodecCapabilities(
    val supportedCodecs: Set<WebRtcVideoCodec>,
) {
    companion object {
        /** Conservative pre-negotiation baseline shared by the supported WebRTC clients. */
        fun unverifiedBaseline(): RemoteVideoCodecCapabilities = RemoteVideoCodecCapabilities(
            supportedCodecs = setOf(WebRtcVideoCodec.H264, WebRtcVideoCodec.VP8),
        )
    }
}
