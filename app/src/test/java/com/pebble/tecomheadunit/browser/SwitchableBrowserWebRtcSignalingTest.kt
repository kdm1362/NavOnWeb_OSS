package com.pebble.tecomheadunit.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class SwitchableBrowserWebRtcSignalingTest {
    @Test
    fun replacementIsAtomicAndFutureCallsUseNewSender() {
        val first = FakeSignaling("first")
        val second = FakeSignaling("second")
        val switchable = SwitchableBrowserWebRtcSignaling(first)

        assertEquals("first", switchable.capabilities().detail)
        assertSame(first, switchable.replace(second))
        assertEquals("second", switchable.capabilities().detail)

        val result = switchable.openSession(
            BrowserWebRtcOffer(BrowserWebRtcCodec.AUTO, "v=0\r\n"),
        ) as BrowserWebRtcOpenResult.Rejected
        assertEquals("second", result.detail)
        assertEquals(0, first.offerCount)
        assertEquals(1, second.offerCount)
        assertEquals(true, switchable.revokeActiveSessionForCredentialChange())
        assertEquals(0, first.revocationCount)
        assertEquals(1, second.revocationCount)
    }

    private class FakeSignaling(private val name: String) : BrowserWebRtcSignaling {
        var offerCount = 0
        var revocationCount = 0

        override fun capabilities(): BrowserWebRtcCapabilities =
            BrowserWebRtcCapabilities(available = true, detail = name)

        override fun openSession(offer: BrowserWebRtcOffer): BrowserWebRtcOpenResult {
            offerCount += 1
            return BrowserWebRtcOpenResult.Rejected(name)
        }

        override fun session(sessionId: String): BrowserWebRtcSessionSnapshot? = null

        override fun closeSession(sessionId: String): Boolean = false

        override fun revokeActiveSessionForCredentialChange(): Boolean {
            revocationCount += 1
            return true
        }
    }
}
