package com.eigenkodex.navonweb.browser.webrtc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.webrtc.PeerConnectionFactory

class WebRtcLocalNetworkPolicyTest {
    /**
     * The phone is the access point in the vehicle, and ConnectivityManager never reports its own
     * softAP interface. With the monitor enabled, gathering sees loopback only and no direct-LAN
     * session can form at all - measured on a real hotspot, where answers advertised 127.0.0.1
     * while the HTTP server was live on swlan0. Enumeration must come from getifaddrs.
     */
    @Test
    fun `factory enumerates interfaces directly so the phone's own hotspot is visible`() {
        val options = WebRtcLocalNetworkPolicy.peerConnectionFactoryOptions()

        assertTrue(
            "hotspot topology requires getifaddrs enumeration",
            options.disableNetworkMonitor,
        )
        assertFalse(
            "the default must be the broken-on-hotspot one, or this assertion proves nothing",
            PeerConnectionFactory.Options().disableNetworkMonitor,
        )
    }

    /**
     * No adapter is filtered. ICE picks a local pair on priority and RTT without help, and the
     * adapter type available here is derived from an interface-name table once the network monitor
     * is off - not dependable enough to exclude anything on, and previously able to kill a healthy
     * session over a naming quirk.
     */
    @Test
    fun `no adapter type is excluded from gathering`() {
        val mask = WebRtcLocalNetworkPolicy.peerConnectionFactoryOptions().networkIgnoreMask

        assertEquals("no adapter class may be filtered out of gathering", 0, mask)
    }

    /**
     * Everything else about the factory must stay at its default. disableEncryption is the one
     * that matters: flipping it would ship unencrypted media while every other test still passed.
     */
    @Test
    fun `the policy changes nothing else about the factory`() {
        val options = WebRtcLocalNetworkPolicy.peerConnectionFactoryOptions()
        val defaults = PeerConnectionFactory.Options()

        assertFalse("media must never be sent unencrypted", options.disableEncryption)
        assertEquals(defaults.disableEncryption, options.disableEncryption)
        assertEquals(defaults.networkIgnoreMask, options.networkIgnoreMask)
    }
}
