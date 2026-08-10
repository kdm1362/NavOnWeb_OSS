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
            BrowserWebRtcOffer(
                preferredCodec = BrowserWebRtcCodec.AUTO,
                sdp = "v=0\r\n",
                ownerKey = browserWebRtcOwnerKey("a".repeat(43)),
            ),
        ) as BrowserWebRtcOpenResult.Rejected
        assertEquals("second", result.detail)
        assertEquals(0, first.offerCount)
        assertEquals(1, second.offerCount)
        assertEquals(1, switchable.closeSessionsForOwner(browserWebRtcOwnerKey("a".repeat(43))))
        assertEquals(0, first.revocationCount)
        assertEquals(1, second.revocationCount)
        assertEquals(
            1,
            switchable.updateDeviceDisplayName("browser_0000000000000001", "Renamed"),
        )
        assertEquals(0, first.renameCount)
        assertEquals(1, second.renameCount)
        assertEquals("Renamed", second.lastDisplayName)
    }

    @Test
    fun replacementCanBeGuardedByDelegateIdentity() {
        val first = FakeSignaling("first")
        val stale = FakeSignaling("stale")
        val second = FakeSignaling("second")
        val switchable = SwitchableBrowserWebRtcSignaling(first)

        assertEquals(false, switchable.replaceIfCurrent(stale, second))
        assertEquals("first", switchable.capabilities().detail)
        assertEquals(true, switchable.replaceIfCurrent(first, second))
        assertEquals("second", switchable.capabilities().detail)
    }

    @Test
    fun unavailableRenameIsAContainedNoOp() {
        assertEquals(
            0,
            BrowserWebRtcSignaling.Unavailable.updateDeviceDisplayName(
                "browser_0000000000000001",
                "Renamed",
            ),
        )
    }

    private class FakeSignaling(private val name: String) : BrowserWebRtcSignaling {
        var offerCount = 0
        var revocationCount = 0
        var renameCount = 0
        var lastDisplayName: String? = null

        override fun capabilities(): BrowserWebRtcCapabilities =
            BrowserWebRtcCapabilities(available = true, detail = name)

        override fun openSession(offer: BrowserWebRtcOffer): BrowserWebRtcOpenResult {
            offerCount += 1
            return BrowserWebRtcOpenResult.Rejected(name)
        }

        override fun session(sessionId: String, ownerKey: String): BrowserWebRtcSessionSnapshot? = null

        override fun closeSession(sessionId: String, ownerKey: String): Boolean = false

        override fun closeSessionsForOwner(ownerKey: String): Int {
            revocationCount += 1
            return 1
        }

        override fun closeSessionsForDevice(deviceId: String): Int = 0

        override fun updateDeviceDisplayName(deviceId: String, displayName: String): Int {
            renameCount += 1
            lastDisplayName = displayName
            return 1
        }

        override fun activeSessionMetadata(ownerKey: String): BrowserWebRtcSessionPublicMetadata? = null

        override fun oldestInteractiveDeviceId(): String? = null

        override fun connectedDeviceIds(): Set<String> = emptySet()

        override fun connectedSessionMetadata(): List<BrowserWebRtcSessionPublicMetadata> = emptyList()

        override fun publishTouchPresence(
            sourceOwnerKey: String,
            event: BrowserTouchPresenceEvent,
        ): Int = 0

        override fun reconcileSessionLimit(maximumSessions: Int, preferredMainDeviceId: String?): Int = 0
    }
}
