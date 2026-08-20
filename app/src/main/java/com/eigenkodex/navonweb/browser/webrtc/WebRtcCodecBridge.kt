/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.eigenkodex.navonweb.browser.webrtc

import com.eigenkodex.navonweb.browser.BrowserWebRtcCodec
import com.eigenkodex.navonweb.browser.webrtc.codec.WebRtcCodecPreference
import com.eigenkodex.navonweb.browser.webrtc.codec.WebRtcOutboundCodecPolicy
import com.eigenkodex.navonweb.browser.webrtc.codec.WebRtcVideoCodec
import org.webrtc.RtpCapabilities
import org.webrtc.VideoCodecInfo
import org.webrtc.VideoEncoder
import org.webrtc.VideoEncoderFactory

/** Keeps factory registration deterministic; per-session preference is set on the transceiver. */
internal class CodecOrderedVideoEncoderFactory(
    private val delegate: VideoEncoderFactory,
    private val codecOrder: List<WebRtcVideoCodec>,
    private val enabledCodecs: Set<WebRtcVideoCodec> = WebRtcOutboundCodecPolicy.enabledCodecs,
) : VideoEncoderFactory {
    override fun createEncoder(info: VideoCodecInfo): VideoEncoder? {
        val codec = WebRtcCodecBridge.fromSdpName(info.name) ?: return null
        if (codec !in enabledCodecs) return null
        val encoder = delegate.createEncoder(info) ?: return null
        // The SDK's HardwareVideoEncoder leaves MediaCodec's profile
        // unconstrained for a BASELINE negotiation, so the vendor encoder may
        // emit Main/High regardless of the SDP (observed in the field: the
        // head unit refused every stream one day after 800x480 worked the
        // day before). Enforcement is PER SESSION: only a session that
        // negotiated baseline is pinned to baseline. A 640c1f negotiation is
        // left alone - the SDK's own KEY_PROFILE=High branch is correct - so
        // High-capable peers keep receiving High. Fail-open either way.
        if (codec == WebRtcVideoCodec.H264 &&
            WebRtcCodecBridge.isBaselineH264(info.params)
        ) {
            org.webrtc.NavOnWebH264BaselineEnforcer.enforce(encoder)
        }
        return encoder
    }

    override fun getSupportedCodecs(): Array<VideoCodecInfo> = delegate.supportedCodecs
        .filter { info ->
            WebRtcCodecBridge.fromSdpName(info.name)?.let(enabledCodecs::contains) == true
        }
        .sortedWith(compareBy<VideoCodecInfo> { codecRank(it.name) }.thenBy { it.name })
        .toTypedArray()

    private fun codecRank(name: String): Int {
        val codec = WebRtcCodecBridge.fromSdpName(name) ?: return Int.MAX_VALUE
        return codecOrder.indexOf(codec).takeIf { it >= 0 } ?: Int.MAX_VALUE - 1
    }
}

internal object WebRtcCodecBridge {
    /** SDP fmtp key for the H.264 parameter set. */
    const val H264_PROFILE_LEVEL_ID_KEY = "profile-level-id"

    /**
     * TRUE when this H.264 parameter set negotiates (constrained) baseline:
     * profile_idc 0x42 in the first profile-level-id byte, or an absent
     * profile-level-id, which RFC 6184 defines as baseline. Only such
     * sessions get configure-time profile enforcement; 640c1f (High) and
     * anything else is left to the SDK's own handling.
     */
    fun isBaselineH264(params: Map<String, String>): Boolean {
        val profileLevelId = params[H264_PROFILE_LEVEL_ID_KEY] ?: return true
        return profileLevelId.length >= 2 &&
            profileLevelId.substring(0, 2).equals("42", ignoreCase = true)
    }

    private val rtpMapPattern = Regex(
        pattern = "^a=rtpmap:([0-9]{1,3})[ \\t]+([^/ \\t]+)/",
        option = RegexOption.IGNORE_CASE,
    )

    fun parseRemoteVideoCodecs(sdp: String): Set<WebRtcVideoCodec> {
        var inVideoSection = false
        return buildSet {
            sdp.lineSequence().forEach { rawLine ->
                val line = rawLine.trim()
                if (line.startsWith("m=")) {
                    inVideoSection = line.startsWith("m=video ", ignoreCase = true)
                } else if (inVideoSection) {
                    val codecName = rtpMapPattern.find(line)?.groupValues?.get(2)
                    fromSdpName(codecName)?.let(::add)
                }
            }
        }
    }

    fun orderedCapabilities(
        capabilities: List<RtpCapabilities.CodecCapability>,
        preferredCodecs: List<WebRtcVideoCodec>,
    ): List<RtpCapabilities.CodecCapability> {
        val disabledPayloadTypes = capabilities.mapNotNull { capability ->
            val codec = fromSdpName(capability.name) ?: return@mapNotNull null
            capability.preferredPayloadType.takeIf {
                !WebRtcOutboundCodecPolicy.isEncoderEnabled(codec)
            }
        }.toSet()
        val enabledCapabilities = capabilities.filter { capability ->
            val primaryCodec = fromSdpName(capability.name)
            when {
                primaryCodec != null -> WebRtcOutboundCodecPolicy.isEncoderEnabled(primaryCodec)
                capability.name.equals("rtx", ignoreCase = true) -> {
                    val associatedPayload = capability.parameters["apt"]?.toIntOrNull()
                    associatedPayload != null && associatedPayload !in disabledPayloadTypes
                }
                else -> true
            }
        }
        if (preferredCodecs.isEmpty()) return enabledCapabilities
        val payloadRanks = enabledCapabilities.mapNotNull { capability ->
            val codec = fromSdpName(capability.name) ?: return@mapNotNull null
            val rank = preferredCodecs.indexOf(codec).takeIf { it >= 0 } ?: return@mapNotNull null
            capability.preferredPayloadType to rank
        }.toMap()

        return enabledCapabilities.withIndex()
            .sortedWith(
                compareBy<IndexedValue<RtpCapabilities.CodecCapability>> {
                    capabilityRank(it.value, preferredCodecs, payloadRanks)
                }.thenBy(IndexedValue<RtpCapabilities.CodecCapability>::index),
            )
            .map(IndexedValue<RtpCapabilities.CodecCapability>::value)
    }

    fun fromSdpName(value: String?): WebRtcVideoCodec? = when (value?.trim()?.uppercase()) {
        "H264" -> WebRtcVideoCodec.H264
        "VP8" -> WebRtcVideoCodec.VP8
        "VP9" -> WebRtcVideoCodec.VP9
        "AV1", "AV01" -> WebRtcVideoCodec.AV1
        else -> null
    }

    fun WebRtcVideoCodec.toBrowserCodec(): BrowserWebRtcCodec = when (this) {
        WebRtcVideoCodec.H264 -> BrowserWebRtcCodec.H264
        WebRtcVideoCodec.VP8 -> BrowserWebRtcCodec.VP8
        WebRtcVideoCodec.VP9 -> BrowserWebRtcCodec.VP9
        WebRtcVideoCodec.AV1 -> BrowserWebRtcCodec.AV1
    }

    fun BrowserWebRtcCodec.toPreference(): WebRtcCodecPreference = when (this) {
        BrowserWebRtcCodec.AUTO -> WebRtcCodecPreference.AUTO
        BrowserWebRtcCodec.H264 -> WebRtcCodecPreference.H264
        BrowserWebRtcCodec.VP8 -> WebRtcCodecPreference.VP8
        BrowserWebRtcCodec.VP9 -> WebRtcCodecPreference.VP9
        BrowserWebRtcCodec.AV1 -> WebRtcCodecPreference.AV1
    }

    private fun capabilityRank(
        capability: RtpCapabilities.CodecCapability,
        preferredCodecs: List<WebRtcVideoCodec>,
        payloadRanks: Map<Int, Int>,
    ): Int {
        val primaryCodec = fromSdpName(capability.name)
        if (primaryCodec != null) {
            val rank = preferredCodecs.indexOf(primaryCodec).takeIf { it >= 0 }
                ?: preferredCodecs.size
            return rank * RANK_STRIDE
        }
        if (capability.name.equals("rtx", ignoreCase = true)) {
            val associatedPayload = capability.parameters["apt"]?.toIntOrNull()
            val associatedRank = associatedPayload?.let(payloadRanks::get)
            if (associatedRank != null) return associatedRank * RANK_STRIDE + 1
        }
        return AUXILIARY_CODEC_RANK
    }

    private const val RANK_STRIDE = 4
    private const val AUXILIARY_CODEC_RANK = 10_000
}
