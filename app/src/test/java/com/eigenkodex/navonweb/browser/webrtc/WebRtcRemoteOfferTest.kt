/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.eigenkodex.navonweb.browser.webrtc

import org.junit.Assert.assertEquals
import org.junit.Test

class WebRtcRemoteOfferTest {
    @Test
    fun `selects the browser recvonly video mid`() {
        val offer = """
            v=0
            a=sendrecv
            m=audio 9 UDP/TLS/RTP/SAVPF 111
            a=mid:audio
            a=sendrecv
            m=video 9 UDP/TLS/RTP/SAVPF 96
            a=mid:0
            a=recvonly
        """.trimIndent()

        assertEquals(listOf("0"), WebRtcRemoteOffer.receivingVideoMids(offer))
    }

    @Test
    fun `rejects video sections that do not permit local sending`() {
        val offer = """
            v=0
            m=video 9 UDP/TLS/RTP/SAVPF 96
            a=mid:send-only
            a=sendonly
            m=video 9 UDP/TLS/RTP/SAVPF 97
            a=mid:inactive
            a=inactive
            m=video 0 UDP/TLS/RTP/SAVPF 98
            a=mid:rejected
            a=recvonly
        """.trimIndent()

        assertEquals(emptyList<String>(), WebRtcRemoteOffer.receivingVideoMids(offer))
    }

    @Test
    fun `uses session direction and preserves offered mid order`() {
        val offer = """
            v=0
            a=recvonly
            m=video 9 UDP/TLS/RTP/SAVPF 96
            a=mid:first
            m=video 9 UDP/TLS/RTP/SAVPF 97
            a=mid:second
            a=sendrecv
            m=video 9 UDP/TLS/RTP/SAVPF 98
            a=mid:no-send
            a=sendonly
        """.trimIndent()

        assertEquals(listOf("first", "second"), WebRtcRemoteOffer.receivingVideoMids(offer))
    }

    @Test
    fun `skips video sections without a mid`() {
        val offer = """
            v=0
            m=video 9 UDP/TLS/RTP/SAVPF 96
            a=recvonly
        """.trimIndent()

        assertEquals(emptyList<String>(), WebRtcRemoteOffer.receivingVideoMids(offer))
    }
}
