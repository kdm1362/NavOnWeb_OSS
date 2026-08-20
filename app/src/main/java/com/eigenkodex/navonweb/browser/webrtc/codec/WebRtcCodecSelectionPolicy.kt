/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.eigenkodex.navonweb.browser.webrtc.codec

internal enum class CodecSelectionWarning {
    REQUESTED_CODEC_UNAVAILABLE,
    SOFTWARE_ENCODER_SELECTED,
    ENCODER_ACCELERATION_UNKNOWN,
}

internal data class SelectedWebRtcCodec(
    val codec: WebRtcVideoCodec,
    val encoder: MediaCodecEncoderCapability,
    val usedFallback: Boolean,
    val warnings: Set<CodecSelectionWarning>,
)

internal sealed interface WebRtcCodecSelectionResult {
    data class Selected(val value: SelectedWebRtcCodec) : WebRtcCodecSelectionResult

    /** WebRTC video must not start; the caller may keep the authenticated JPEG debug fallback. */
    data class Unavailable(
        val attemptedCodecs: List<WebRtcVideoCodec>,
    ) : WebRtcCodecSelectionResult
}

/**
 * Produces a deterministic codec order after intersecting Android encoder and browser decoder
 * capabilities.
 *
 * AUTO never picks a software AV1/VP9 encoder: their bitrate gain is not worth unbounded CPU,
 * thermal and latency risk on an embedded head unit. An explicit user choice may still select
 * AV1/VP9 and returns a warning when software-backed. AUTO prefers hardware H.264 for broad
 * browser compatibility and falls back to the mandatory VP8 baseline.
 */
internal class WebRtcCodecSelectionPolicy {
    fun select(
        preference: WebRtcCodecPreference,
        local: MediaCodecCapabilitySnapshot,
        remote: RemoteVideoCodecCapabilities,
        allowSoftwareForExplicitChoice: Boolean = true,
    ): WebRtcCodecSelectionResult {
        val candidates = rankedCandidates(
            preference = preference,
            local = local,
            remote = remote,
            allowSoftwareForExplicitChoice = allowSoftwareForExplicitChoice,
        )
        val requested = preference.requestedCodec
        val selected = candidates.firstOrNull()
            ?: return WebRtcCodecSelectionResult.Unavailable(
                attemptedCodecs = attemptedCodecOrder(preference),
            )

        val warnings = buildSet {
            if (requested != null && selected.codec != requested) {
                add(CodecSelectionWarning.REQUESTED_CODEC_UNAVAILABLE)
            }
            when (selected.acceleration) {
                EncoderAcceleration.SOFTWARE -> add(CodecSelectionWarning.SOFTWARE_ENCODER_SELECTED)
                EncoderAcceleration.UNKNOWN -> add(CodecSelectionWarning.ENCODER_ACCELERATION_UNKNOWN)
                EncoderAcceleration.HARDWARE -> Unit
            }
        }
        return WebRtcCodecSelectionResult.Selected(
            SelectedWebRtcCodec(
                codec = selected.codec,
                encoder = selected,
                usedFallback = requested != null && selected.codec != requested,
                warnings = warnings,
            ),
        )
    }

    /** Ordered encoder candidates suitable for setCodecPreferences and local encoder selection. */
    fun rankedCandidates(
        preference: WebRtcCodecPreference,
        local: MediaCodecCapabilitySnapshot,
        remote: RemoteVideoCodecCapabilities,
        allowSoftwareForExplicitChoice: Boolean = true,
    ): List<MediaCodecEncoderCapability> {
        val usableByCodec = local.encoders
            .asSequence()
            .filter(MediaCodecEncoderCapability::isUsable)
            .filter { WebRtcOutboundCodecPolicy.isEncoderEnabled(it.codec) }
            .filter { it.codec in remote.supportedCodecs }
            .groupBy(MediaCodecEncoderCapability::codec)

        return if (preference == WebRtcCodecPreference.AUTO) {
            autoCandidates(usableByCodec)
        } else {
            explicitCandidates(
                requested = checkNotNull(preference.requestedCodec),
                usableByCodec = usableByCodec,
                allowSoftwareForExplicitChoice = allowSoftwareForExplicitChoice,
            )
        }
    }

    private fun autoCandidates(
        usableByCodec: Map<WebRtcVideoCodec, List<MediaCodecEncoderCapability>>,
    ): List<MediaCodecEncoderCapability> {
        val hardware = AUTO_EFFICIENCY_ORDER.mapNotNull { codec ->
            usableByCodec.best(codec, allowed = setOf(EncoderAcceleration.HARDWARE))
        }
        if (hardware.isNotEmpty()) return hardware

        val uncertainBaseline = BASELINE_FALLBACK_ORDER.mapNotNull { codec ->
            usableByCodec.best(codec, allowed = setOf(EncoderAcceleration.UNKNOWN))
        }
        if (uncertainBaseline.isNotEmpty()) return uncertainBaseline

        return BASELINE_FALLBACK_ORDER.mapNotNull { codec ->
            usableByCodec.best(codec, allowed = setOf(EncoderAcceleration.SOFTWARE))
        }
    }

    private fun explicitCandidates(
        requested: WebRtcVideoCodec,
        usableByCodec: Map<WebRtcVideoCodec, List<MediaCodecEncoderCapability>>,
        allowSoftwareForExplicitChoice: Boolean,
    ): List<MediaCodecEncoderCapability> {
        val requestedAcceleration = if (allowSoftwareForExplicitChoice) {
            EncoderAcceleration.entries.toSet()
        } else {
            setOf(EncoderAcceleration.HARDWARE, EncoderAcceleration.UNKNOWN)
        }
        val preferred = usableByCodec.best(requested, allowed = requestedAcceleration)
        if (preferred != null) {
            return listOf(preferred) + baselineFallbacks(usableByCodec, excluding = requested)
        }
        return baselineFallbacks(usableByCodec, excluding = requested)
    }

    private fun baselineFallbacks(
        usableByCodec: Map<WebRtcVideoCodec, List<MediaCodecEncoderCapability>>,
        excluding: WebRtcVideoCodec,
    ): List<MediaCodecEncoderCapability> = ENCODER_ACCELERATION_ORDER.flatMap { acceleration ->
        BASELINE_FALLBACK_ORDER
            .filterNot { it == excluding }
            .mapNotNull { codec -> usableByCodec.best(codec, allowed = setOf(acceleration)) }
    }

    private fun Map<WebRtcVideoCodec, List<MediaCodecEncoderCapability>>.best(
        codec: WebRtcVideoCodec,
        allowed: Set<EncoderAcceleration>,
    ): MediaCodecEncoderCapability? = get(codec)
        .orEmpty()
        .asSequence()
        .filter { it.acceleration in allowed }
        .sortedWith(
            compareBy<MediaCodecEncoderCapability> {
                ENCODER_ACCELERATION_ORDER.indexOf(it.acceleration)
            }.thenBy(MediaCodecEncoderCapability::encoderName),
        )
        .firstOrNull()

    private fun attemptedCodecOrder(preference: WebRtcCodecPreference): List<WebRtcVideoCodec> {
        val requested = preference.requestedCodec
        return if (requested == null) {
            AUTO_EFFICIENCY_ORDER
        } else {
            listOf(requested) + BASELINE_FALLBACK_ORDER.filterNot { it == requested }
        }
    }

    private companion object {
        val AUTO_EFFICIENCY_ORDER = listOf(
            WebRtcVideoCodec.H264,
            WebRtcVideoCodec.VP8,
            WebRtcVideoCodec.VP9,
            WebRtcVideoCodec.AV1,
        )
        val BASELINE_FALLBACK_ORDER = listOf(
            WebRtcVideoCodec.H264,
            WebRtcVideoCodec.VP8,
        )
        val ENCODER_ACCELERATION_ORDER = listOf(
            EncoderAcceleration.HARDWARE,
            EncoderAcceleration.UNKNOWN,
            EncoderAcceleration.SOFTWARE,
        )
    }
}
