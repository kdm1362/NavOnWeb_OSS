/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.pebble.tecomheadunit.browser.webrtc

import com.pebble.tecomheadunit.browser.BrowserWebRtcCodec
import com.pebble.tecomheadunit.browser.webrtc.codec.WebRtcCodecPreference
import com.pebble.tecomheadunit.browser.webrtc.codec.WebRtcOutboundCodecPolicy
import com.pebble.tecomheadunit.browser.webrtc.codec.WebRtcVideoCodec
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
        return if (codec in enabledCodecs) delegate.createEncoder(info) else null
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
