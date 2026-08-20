/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.eigenkodex.navonweb.browser.webrtc

import com.eigenkodex.navonweb.browser.webrtc.codec.WebRtcVideoCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.webrtc.VideoCodecInfo
import org.webrtc.VideoEncoder
import org.webrtc.VideoEncoderFactory
import org.webrtc.RtpCapabilities

class WebRtcCodecBridgeTest {
    @Test
    fun `parses codecs only from video media sections`() {
        val offer = """
            v=0
            m=audio 9 UDP/TLS/RTP/SAVPF 111
            a=rtpmap:111 AV1/48000/2
            m=video 9 UDP/TLS/RTP/SAVPF 96 98 100 102
            a=rtpmap:96 VP8/90000
            a=rtpmap:98 VP9/90000
            a=rtpmap:100 H264/90000
            a=rtpmap:102 AV1/90000
            m=application 9 UDP/DTLS/SCTP webrtc-datachannel
            a=rtpmap:120 VP8/90000
        """.trimIndent()

        assertEquals(
            setOf(
                WebRtcVideoCodec.VP8,
                WebRtcVideoCodec.VP9,
                WebRtcVideoCodec.H264,
                WebRtcVideoCodec.AV1,
            ),
            WebRtcCodecBridge.parseRemoteVideoCodecs(offer),
        )
    }

    @Test
    fun `ignores malformed and auxiliary codecs`() {
        val offer = """
            v=0
            m=video 9 UDP/TLS/RTP/SAVPF 96 97 98
            a=rtpmap:96 rtx/90000
            a=rtpmap:97 red/90000
            a=rtpmap:not-a-payload VP9/90000
        """.trimIndent()

        assertEquals(emptySet<WebRtcVideoCodec>(), WebRtcCodecBridge.parseRemoteVideoCodecs(offer))
    }

    @Test
    fun `outbound encoder factory advertises and delegates H264`() {
        val createdCodecNames = mutableListOf<String>()
        val delegate = object : VideoEncoderFactory {
            override fun createEncoder(info: VideoCodecInfo): VideoEncoder? {
                createdCodecNames += info.name
                return null
            }

            override fun getSupportedCodecs(): Array<VideoCodecInfo> = arrayOf(
                VideoCodecInfo("H264", emptyMap<String, String>(), emptyList()),
                VideoCodecInfo("VP8", emptyMap<String, String>(), emptyList()),
                VideoCodecInfo("VP9", emptyMap<String, String>(), emptyList()),
            )
        }
        val factory = CodecOrderedVideoEncoderFactory(
            delegate = delegate,
            codecOrder = listOf(
                WebRtcVideoCodec.H264,
                WebRtcVideoCodec.VP8,
                WebRtcVideoCodec.VP9,
                WebRtcVideoCodec.AV1,
            ),
        )

        assertEquals(listOf("H264", "VP8", "VP9"), factory.supportedCodecs.map { it.name })
        assertNull(
            factory.createEncoder(VideoCodecInfo("H264", emptyMap<String, String>(), emptyList())),
        )
        assertEquals(listOf("H264"), createdCodecNames)
        assertNull(
            factory.createEncoder(VideoCodecInfo("VP8", emptyMap<String, String>(), emptyList())),
        )
        assertEquals(listOf("H264", "VP8"), createdCodecNames)
    }

    @Test
    fun `outbound encoder factory keeps advertising High so capable peers negotiate it`() {
        val delegate = object : VideoEncoderFactory {
            override fun createEncoder(info: VideoCodecInfo): VideoEncoder? = null

            override fun getSupportedCodecs(): Array<VideoCodecInfo> = arrayOf(
                VideoCodecInfo("H264", mapOf("profile-level-id" to "640c1f"), emptyList()),
                VideoCodecInfo("H264", mapOf("profile-level-id" to "42e01f"), emptyList()),
                VideoCodecInfo("VP8", emptyMap<String, String>(), emptyList()),
            )
        }
        val factory = CodecOrderedVideoEncoderFactory(
            delegate = delegate,
            codecOrder = listOf(WebRtcVideoCodec.H264, WebRtcVideoCodec.VP8),
        )

        // Baseline enforcement is per NEGOTIATED SESSION, never by removing
        // capability: a baseline-only receiver must not level every other
        // peer down, so both parameter sets stay advertised.
        assertEquals(
            listOf("640c1f", "42e01f"),
            factory.supportedCodecs
                .filter { it.name == "H264" }
                .map { it.params["profile-level-id"] },
        )
    }

    @Test
    fun `baseline detection gates enforcement by the negotiated parameter set`() {
        // Enforced: (constrained) baseline negotiations, incl. the RFC 6184
        // absent-parameter default.
        assertEquals(true, WebRtcCodecBridge.isBaselineH264(mapOf("profile-level-id" to "42e01f")))
        assertEquals(true, WebRtcCodecBridge.isBaselineH264(mapOf("profile-level-id" to "42001f")))
        assertEquals(true, WebRtcCodecBridge.isBaselineH264(emptyMap()))
        // Left alone: High/Main negotiations - the SDK sets KEY_PROFILE
        // itself for 640c1f, and capable peers keep receiving High.
        assertEquals(false, WebRtcCodecBridge.isBaselineH264(mapOf("profile-level-id" to "640c1f")))
        assertEquals(false, WebRtcCodecBridge.isBaselineH264(mapOf("profile-level-id" to "4d001f")))
    }

    @Test
    fun `sender capabilities retain H264 primary and its RTX payload`() {
        val capabilities = listOf(
            capability(payloadType = 100, name = "H264"),
            capability(payloadType = 101, name = "rtx", parameters = mapOf("apt" to "100")),
            capability(payloadType = 96, name = "VP8"),
            capability(payloadType = 97, name = "rtx", parameters = mapOf("apt" to "96")),
            capability(payloadType = 98, name = "red"),
        )

        val selected = WebRtcCodecBridge.orderedCapabilities(
            capabilities = capabilities,
            preferredCodecs = listOf(WebRtcVideoCodec.VP8, WebRtcVideoCodec.H264),
        )

        assertEquals(listOf(96, 97, 100, 101, 98), selected.map { it.preferredPayloadType })
    }

    private fun capability(
        payloadType: Int,
        name: String,
        parameters: Map<String, String> = emptyMap(),
    ): RtpCapabilities.CodecCapability = RtpCapabilities.CodecCapability().apply {
        preferredPayloadType = payloadType
        this.name = name
        this.parameters = parameters
    }
}
