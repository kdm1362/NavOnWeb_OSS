package com.pebble.tecomheadunit.browser.webrtc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.webrtc.RTCStats
import org.webrtc.RTCStatsReport

class WebRtcOutboundVideoStatsTest {
    @Test
    fun `parses selected sender path and computes measured bitrate`() {
        val previous = requireNotNull(
            WebRtcOutboundVideoStatsParser.parse(report(10_000_000L, 1_000_000L, 100L)),
        )
        val current = requireNotNull(
            WebRtcOutboundVideoStatsParser.parse(report(15_000_000L, 2_250_000L, 250L)),
        )

        assertEquals(1920, current.frameWidth)
        assertEquals(1080, current.frameHeight)
        assertEquals("video/H264", current.codecMimeType)
        assertEquals(12_000_000L, current.availableOutgoingBitrateBps)
        assertEquals("none", current.qualityLimitationReason)
        assertTrue(current.logSummary(previous).contains("sendMbps=2.00"))
        assertTrue(current.logSummary(previous).contains("avgQp=30.0"))
    }

    private fun report(timestampUs: Long, bytesSent: Long, framesEncoded: Long): RTCStatsReport {
        val outbound = RTCStats(
            timestampUs,
            "outbound-rtp",
            "outbound-video",
            mapOf(
                "kind" to "video",
                "bytesSent" to bytesSent,
                "framesEncoded" to framesEncoded,
                "frameWidth" to 1920L,
                "frameHeight" to 1080L,
                "framesPerSecond" to 30.0,
                "qpSum" to framesEncoded * 30L,
                "targetBitrate" to 8_000_000.0,
                "qualityLimitationReason" to "none",
                "codecId" to "codec",
                "encoderImplementation" to "hardware",
            ),
        )
        val codec = RTCStats(
            timestampUs,
            "codec",
            "codec",
            mapOf("mimeType" to "video/H264"),
        )
        val pair = RTCStats(
            timestampUs,
            "candidate-pair",
            "pair",
            mapOf(
                "state" to "succeeded",
                "selected" to true,
                "availableOutgoingBitrate" to 12_000_000.0,
                "currentRoundTripTime" to 0.008,
            ),
        )
        val remoteInbound = RTCStats(
            timestampUs,
            "remote-inbound-rtp",
            "remote-video",
            mapOf(
                "kind" to "video",
                "packetsLost" to 0L,
                "roundTripTime" to 0.006,
            ),
        )
        return RTCStatsReport(
            timestampUs,
            mapOf(
                outbound.id to outbound,
                codec.id to codec,
                pair.id to pair,
                remoteInbound.id to remoteInbound,
            ),
        )
    }
}
