package com.eigenkodex.navonweb.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserWebRtcSignalingTest {
    @Test
    fun browserCredentialOwnerKeyIsOpaqueStableAndBounded() {
        val credential = "a".repeat(43)
        val ownerKey = browserWebRtcOwnerKey(credential)

        assertEquals(ownerKey, browserWebRtcOwnerKey(credential))
        assertEquals(43, ownerKey.length)
        assertTrue(Regex("^[A-Za-z0-9_-]{43}$").matches(ownerKey))
        assertFalse(ownerKey.contains(credential))
        assertFalse(ownerKey == browserWebRtcOwnerKey("b".repeat(43)))
        assertThrows(IllegalArgumentException::class.java) {
            BrowserWebRtcOffer(
                preferredCodec = BrowserWebRtcCodec.AUTO,
                sdp = "v=0\r\n",
                ownerKey = "not-a-sha256-owner-key",
            )
        }
    }

    @Test
    fun sameAuthenticatedBrowserCanReplaceSessionBeforeStaleDeleteArrives() {
        val ownerKey = browserWebRtcOwnerKey("a".repeat(43))

        assertEquals(
            BrowserWebRtcSessionAdmission.Open,
            browserWebRtcSessionAdmission(
                activeSessionId = null,
                activeOwnerKey = null,
                requestedOwnerKey = ownerKey,
            ),
        )
        assertEquals(
            BrowserWebRtcSessionAdmission.Replace("old_session_123"),
            browserWebRtcSessionAdmission(
                activeSessionId = "old_session_123",
                activeOwnerKey = ownerKey,
                requestedOwnerKey = ownerKey,
            ),
        )
        assertFalse(
            browserWebRtcSessionCloseMatches(
                activeSessionId = "new_session_456",
                requestedSessionId = "old_session_123",
            ),
        )
        assertTrue(
            browserWebRtcSessionCloseMatches(
                activeSessionId = "new_session_456",
                requestedSessionId = "new_session_456",
            ),
        )
    }

    @Test
    fun differentAuthenticatedBrowserRemainsBusy() {
        assertEquals(
            BrowserWebRtcSessionAdmission.Busy,
            browserWebRtcSessionAdmission(
                activeSessionId = "active_session_123",
                activeOwnerKey = browserWebRtcOwnerKey("a".repeat(43)),
                requestedOwnerKey = browserWebRtcOwnerKey("b".repeat(43)),
            ),
        )
    }

    @Test
    fun rewritesOnlyMdnsUdpHostCandidateAddressFromDirectLanPeer() {
        val offer =
            "v=0\r\n" +
                "m=video 9 UDP/TLS/RTP/SAVPF 96\r\n" +
                "a=candidate:1234 1 UDP 2122260223 7cdb74ee-1234-5678-abcd-123456789abc.local 54400 " +
                "typ host generation 0 ufrag browserUfrag network-cost 999\r\n" +
                "a=end-of-candidates\r\n"
        val expected = offer.replace(
            "7cdb74ee-1234-5678-abcd-123456789abc.local",
            "10.0.0.220",
        )

        assertEquals(
            expected,
            BrowserProbeServer.rewriteMdnsHostCandidateAddresses(offer, "10.0.0.220"),
        )
        assertTrue(expected.contains("54400 typ host generation 0 ufrag browserUfrag network-cost 999"))
        assertTrue(expected.endsWith("a=end-of-candidates\r\n"))
    }

    @Test
    fun candidateRewriteLeavesNonMdnsNonUdpAndNonHostCandidatesUntouched() {
        val offer =
            "a=candidate:tcp 1 tcp 1518280447 browser.local 9 typ host tcptype active\r\n" +
                "a=candidate:srflx 1 udp 1686052607 browser.local 40000 typ srflx raddr 0.0.0.0 rport 0\r\n" +
                "a=candidate:ipv4 1 udp 2122260223 10.0.0.220 54400 typ host generation 0\r\n" +
                "a=ice-ufrag:browserUfrag\r\n"

        assertEquals(
            offer,
            BrowserProbeServer.rewriteMdnsHostCandidateAddresses(offer, "10.0.0.220"),
        )
    }

    @Test
    fun numericUdpHostCandidateDisablesMdnsRewriteForEntireOffer() {
        val offer =
            "a=candidate:mdns 1 udp 2122260223 browser.local 54400 typ host generation 0\r\n" +
                "a=candidate:numeric 1 udp 2122194687 10.0.0.220 54401 typ host " +
                "generation 0 ufrag alreadyReachable\r\n"

        assertEquals(
            offer,
            BrowserProbeServer.rewriteMdnsHostCandidateAddresses(offer, "10.0.0.220"),
        )
        assertTrue(offer.contains("browser.local 54400"))
    }

    @Test
    fun cloudOfferInfersOnePrivateSrflxAddressFromMatchingMdnsPorts() {
        val offer =
            "a=candidate:host-video 1 udp 2122260223 video.local 54400 typ host generation 0\r\n" +
                "a=candidate:srflx-video 1 udp 1686052607 192.168.0.220 54400 typ srflx " +
                "raddr 0.0.0.0 rport 0\r\n" +
                "a=candidate:host-data 1 udp 2122260223 data.local 54401 typ host generation 0\r\n" +
                "a=candidate:srflx-data 1 udp 1686052607 192.168.0.220 54401 typ srflx " +
                "raddr 0.0.0.0 rport 0\r\n" +
                "a=candidate:host-bundled 1 udp 2122260223 bundled.local 54402 typ host generation 0\r\n"

        val inferred = BrowserProbeServer.inferDirectPeerIpv4FromPrivateSrflx(offer)

        assertEquals("192.168.0.220", inferred)
        val rewritten = BrowserProbeServer.rewriteMdnsHostCandidateAddresses(offer, inferred)
        assertFalse(rewritten.contains("video.local"))
        assertFalse(rewritten.contains("data.local"))
        assertFalse(rewritten.contains("bundled.local"))
        assertEquals(5, Regex("192\\.168\\.0\\.220").findAll(rewritten).count())
    }

    @Test
    fun cloudOfferDoesNotInferAddressFromAmbiguousOrPortMismatchedSrflxCandidates() {
        val missingPortMatch =
            "a=candidate:host 1 udp 2122260223 browser.local 54400 typ host\r\n" +
                "a=candidate:srflx 1 udp 1686052607 192.168.0.220 54401 typ srflx\r\n"
        val conflictingAddresses =
            "a=candidate:host 1 udp 2122260223 browser.local 54400 typ host\r\n" +
                "a=candidate:srflx-a 1 udp 1686052607 192.168.0.220 54400 typ srflx\r\n" +
                "a=candidate:srflx-b 1 udp 1686052606 10.0.0.8 54400 typ srflx\r\n"

        assertNull(BrowserProbeServer.inferDirectPeerIpv4FromPrivateSrflx(missingPortMatch))
        assertNull(BrowserProbeServer.inferDirectPeerIpv4FromPrivateSrflx(conflictingAddresses))
    }

    @Test
    fun cloudOfferDoesNotInferPublicLoopbackOrMalformedSrflxAddress() {
        val host = "a=candidate:host 1 udp 2122260223 browser.local 54400 typ host\r\n"
        listOf("203.0.113.10", "127.0.0.1", "192.168.031.220").forEach { address ->
            val offer = host +
                "a=candidate:srflx 1 udp 1686052607 $address 54400 typ srflx\r\n"
            assertNull(BrowserProbeServer.inferDirectPeerIpv4FromPrivateSrflx(offer))
        }
    }

    @Test
    fun adbLoopbackAndUnsafePeerAddressesFailSafeWithoutSdpChanges() {
        val offer = "a=candidate:1 1 udp 2122260223 browser.local 54400 typ host ufrag keep-me\r\n"
        val unsafePeers = listOf(
            null,
            "",
            "127.0.0.1",
            "0.0.0.0",
            "224.0.0.1",
            "239.255.255.250",
            "::1",
            "10.00.0.220",
            "10.0.0.256",
            "8.8.8.8",
            "203.0.113.10",
        )

        unsafePeers.forEach { peer ->
            assertFalse(BrowserProbeServer.isEligibleDirectPeerIpv4(peer))
            assertEquals(offer, BrowserProbeServer.rewriteMdnsHostCandidateAddresses(offer, peer))
        }
        assertTrue(BrowserProbeServer.isEligibleDirectPeerIpv4("10.0.0.220"))
        assertTrue(BrowserProbeServer.isEligibleDirectPeerIpv4("10.0.0.2"))
        assertTrue(BrowserProbeServer.isEligibleDirectPeerIpv4("172.16.0.2"))
        assertTrue(BrowserProbeServer.isEligibleDirectPeerIpv4("172.31.255.254"))
        assertFalse(BrowserProbeServer.isEligibleDirectPeerIpv4("172.32.0.2"))
    }

    @Test
    fun codecPreferenceAcceptsOnlyKnownWireNames() {
        assertEquals(BrowserWebRtcCodec.AUTO, BrowserProbeServer.parseWebRtcCodec(null))
        assertEquals(BrowserWebRtcCodec.H264, BrowserProbeServer.parseWebRtcCodec("H264"))
        assertEquals(BrowserWebRtcCodec.VP8, BrowserProbeServer.parseWebRtcCodec(" vp8 "))
        assertEquals(BrowserWebRtcCodec.VP9, BrowserProbeServer.parseWebRtcCodec("vp9"))
        assertEquals(BrowserWebRtcCodec.AV1, BrowserProbeServer.parseWebRtcCodec("av1"))
        assertNull(BrowserProbeServer.parseWebRtcCodec("h265"))
        assertNull(BrowserProbeServer.parseWebRtcCodec(""))
    }

    @Test
    fun sessionRoutesAcceptOnlyOpaqueBoundedIdentifiers() {
        val id = "session_123456789"
        assertTrue(BrowserProbeServer.isValidWebRtcSessionId(id))
        assertEquals(id, BrowserProbeServer.webRtcSessionId("/api/webrtc/session/$id"))

        assertFalse(BrowserProbeServer.isValidWebRtcSessionId("short"))
        assertFalse(BrowserProbeServer.isValidWebRtcSessionId("session/../../escape"))
        assertFalse(BrowserProbeServer.isValidWebRtcSessionId("세션_1234567890123"))
        assertNull(BrowserProbeServer.webRtcSessionId("/api/webrtc/session/session%2Fescape"))
        assertNull(BrowserProbeServer.webRtcSessionId("/api/webrtc/session/"))
    }

    @Test
    fun offerRequiresRecvOnlyVideoIceAndFingerprint() {
        val valid = """
            v=0
            o=- 1 1 IN IP4 127.0.0.1
            m=video 9 UDP/TLS/RTP/SAVPF 96
            a=recvonly
            a=ice-ufrag:test
            a=fingerprint:sha-256 00:11
        """.trimIndent()

        assertTrue(BrowserProbeServer.isValidWebRtcOfferSdp(valid))
        assertFalse(BrowserProbeServer.isValidWebRtcOfferSdp(valid.replace("a=recvonly", "a=sendrecv")))
        assertFalse(BrowserProbeServer.isValidWebRtcOfferSdp(valid.replace("a=fingerprint:", "a=x-fingerprint:")))
        assertFalse(BrowserProbeServer.isValidWebRtcOfferSdp("$valid\u0000"))
    }

    @Test
    fun capabilitiesKeepNativeCodecPreferenceOrder() {
        val capabilities = BrowserWebRtcCapabilities(
            available = true,
            codecs = listOf(BrowserWebRtcCodec.H264, BrowserWebRtcCodec.VP9),
            outputAudioDataChannelsV1 = listOf("media", "speech", "system"),
        )

        assertEquals(listOf(BrowserWebRtcCodec.H264, BrowserWebRtcCodec.VP9), capabilities.codecs)
        assertEquals(listOf("media", "speech", "system"), capabilities.outputAudioDataChannelsV1)
        assertThrows(IllegalArgumentException::class.java) {
            BrowserWebRtcCapabilities(
                available = true,
                codecs = listOf(BrowserWebRtcCodec.H264, BrowserWebRtcCodec.H264),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            BrowserWebRtcCapabilities(
                available = true,
                outputAudioDataChannelsV1 = listOf("media", "media"),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            BrowserWebRtcCapabilities(
                available = true,
                outputAudioDataChannelsV1 = listOf("unknown"),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            BrowserWebRtcCapabilities(
                available = true,
                codecs = listOf(BrowserWebRtcCodec.AUTO),
            )
        }
    }

    @Test
    fun readySessionRequiresAnswerAndNegotiatedCodecCannotBeAuto() {
        val ready = BrowserWebRtcSessionSnapshot(
            sessionId = "session_123456789",
            state = BrowserWebRtcSessionState.READY,
            selectedCodec = BrowserWebRtcCodec.H264,
            answerSdp = "v=0\r\nm=video 9 UDP/TLS/RTP/SAVPF 96\r\n",
        )
        assertEquals(BrowserWebRtcCodec.H264, ready.selectedCodec)

        assertThrows(IllegalArgumentException::class.java) {
            BrowserWebRtcSessionSnapshot(
                sessionId = "session_123456789",
                state = BrowserWebRtcSessionState.READY,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            BrowserWebRtcSessionSnapshot(
                sessionId = "session_123456789",
                state = BrowserWebRtcSessionState.PENDING,
                selectedCodec = BrowserWebRtcCodec.AUTO,
            )
        }
    }
}
