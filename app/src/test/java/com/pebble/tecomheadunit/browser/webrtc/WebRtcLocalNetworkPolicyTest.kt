package com.pebble.tecomheadunit.browser.webrtc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory

class WebRtcLocalNetworkPolicyTest {
    @Test
    fun `factory ignores carrier and vpn adapters but retains local adapters`() {
        val mask = WebRtcLocalNetworkPolicy.peerConnectionFactoryOptions().networkIgnoreMask

        assertTrue(mask and PeerConnectionFactory.Options.ADAPTER_TYPE_CELLULAR != 0)
        assertTrue(mask and PeerConnectionFactory.Options.ADAPTER_TYPE_VPN != 0)
        assertEquals(0, mask and PeerConnectionFactory.Options.ADAPTER_TYPE_WIFI)
        assertEquals(0, mask and PeerConnectionFactory.Options.ADAPTER_TYPE_ETHERNET)
        assertEquals(0, mask and PeerConnectionFactory.Options.ADAPTER_TYPE_LOOPBACK)
    }

    @Test
    fun `selected carrier variants and vpn fail closed`() {
        val forbidden = listOf(
            PeerConnection.AdapterType.CELLULAR,
            PeerConnection.AdapterType.CELLULAR_2G,
            PeerConnection.AdapterType.CELLULAR_3G,
            PeerConnection.AdapterType.CELLULAR_4G,
            PeerConnection.AdapterType.CELLULAR_5G,
            PeerConnection.AdapterType.VPN,
        )

        forbidden.forEach { adapter ->
            assertTrue(adapter.name, WebRtcLocalNetworkPolicy.isForbiddenSelectedAdapter(adapter))
        }
        assertFalse(WebRtcLocalNetworkPolicy.isForbiddenSelectedAdapter(PeerConnection.AdapterType.WIFI))
        assertFalse(WebRtcLocalNetworkPolicy.isForbiddenSelectedAdapter(PeerConnection.AdapterType.ETHERNET))
        assertFalse(WebRtcLocalNetworkPolicy.isForbiddenSelectedAdapter(PeerConnection.AdapterType.LOOPBACK))
        assertFalse(WebRtcLocalNetworkPolicy.isForbiddenSelectedAdapter(PeerConnection.AdapterType.UNKNOWN))
    }
}
