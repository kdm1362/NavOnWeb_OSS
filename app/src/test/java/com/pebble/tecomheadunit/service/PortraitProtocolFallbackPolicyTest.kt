/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.pebble.tecomheadunit.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PortraitProtocolFallbackPolicyTest {
    @Test
    fun `disconnect and video stop trigger only before first portrait frame`() {
        for (status in listOf(
            "DISCONNECTED reason=CONNECT_FAILED retry=1 delayMs=500",
            "AV_STREAM_STOPPED channel=3",
        )) {
            assertTrue(
                shouldTriggerPortraitPreVideoFallback(
                    portraitViewport = true,
                    serviceDiscoveryResponseSent = true,
                    videoMediaAccepted = false,
                    runtimeStatus = status,
                ),
            )
        }
        assertFalse(
            shouldTriggerPortraitPreVideoFallback(true, false, false, "DISCONNECTED reason=EOF"),
        )
        assertFalse(
            shouldTriggerPortraitPreVideoFallback(false, true, false, "DISCONNECTED reason=EOF"),
        )
        assertFalse(
            shouldTriggerPortraitPreVideoFallback(true, true, true, "DISCONNECTED reason=EOF"),
        )
        assertFalse(
            shouldTriggerPortraitPreVideoFallback(true, true, false, "AV_STREAM_STOPPED channel=5"),
        )
    }

    @Test
    fun `each reconnect owns a fresh first-video decision and stale watchdog is rejected`() {
        val state = ProjectionMediaAttemptState()
        val firstAttempt = state.beginAttempt()
        state.markServiceDiscoveryResponseSent()
        assertTrue(state.isAwaitingFirstVideo(firstAttempt))
        assertTrue(state.markVideoMediaAccepted())
        assertFalse(state.markVideoMediaAccepted())
        assertFalse(state.isAwaitingFirstVideo(firstAttempt))

        val secondAttempt = state.beginAttempt()
        assertFalse(state.isAwaitingFirstVideo(firstAttempt))
        assertFalse(state.isAwaitingFirstVideo(secondAttempt))
        state.markServiceDiscoveryResponseSent()
        assertTrue(state.isAwaitingFirstVideo(secondAttempt))
        assertTrue(state.markVideoMediaAccepted())
        assertFalse(state.markVideoMediaAccepted())
    }
}
