/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.eigenkodex.navonweb.service

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PortraitProtocolFallbackPolicyTest {
    @Test
    fun `watchdog is armed by service discovery and cancelled by accepted video`() {
        val service = readSource("main/java/com/eigenkodex/navonweb/service/ProjectionService.kt")

        assertTrue(service.contains("status.startsWith(SERVICE_DISCOVERY_RESPONSE_SENT_STATUS)"))
        assertTrue(service.contains("status.startsWith(VIDEO_MEDIA_ACCEPTED_STATUS)"))
        assertTrue(service.contains("delay(PORTRAIT_MEDIA_ACCEPTANCE_TIMEOUT_MILLIS)"))
        assertTrue(service.contains("disablePortraitEncodedViewportsForSession()"))
        assertTrue(service.contains("portraitMediaAcceptanceWatchdogJob?.cancel()"))
        assertTrue(service.contains("serviceDiscoveryResponseSent &&"))
        assertTrue(service.contains("!videoMediaAccepted"))
        assertTrue(service.contains("TERMINAL_BEFORE_VIDEO_"))
        assertTrue(service.contains("PORTRAIT_MEDIA_ACCEPTANCE_TIMEOUT_MILLIS = 5_000L"))
        assertTrue(service.contains("portraitFallbackAttemptGeneration"))
        assertTrue(
            service.indexOf("disablePortraitEncodedViewportsForSession()") <
                service.indexOf("runtime.close()", service.indexOf("fun triggerPortraitProtocolFallback")),
        )
    }

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
    fun `media acceptance anchor advances only for the first frame of a runtime`() {
        val service = readSource("main/java/com/eigenkodex/navonweb/service/ProjectionService.kt")
        val branchStart = service.indexOf("if (status.startsWith(VIDEO_MEDIA_ACCEPTED_STATUS))")
        val branchEnd = service.indexOf("} else if (", branchStart)
        val branch = service.substring(branchStart, branchEnd)

        assertTrue(branch.contains("if (mediaAttemptState.markVideoMediaAccepted())"))
        assertEquals(
            1,
            Regex("lastProjectionMediaAcceptedElapsedRealtime =")
                .findAll(branch)
                .count(),
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

    @Test
    fun `prepared viewport is committed after teardown even if a newer request arrives`() {
        val service = readSource("main/java/com/eigenkodex/navonweb/service/ProjectionService.kt")
        val teardown = service.indexOf("previousRuntime?.close()", service.indexOf("applyProjectionViewport("))
        val committed = service.indexOf("manager.confirmTransportActive(layout)", teardown)

        assertTrue(teardown >= 0)
        assertTrue(committed > teardown)
        assertFalse(
            service.substring(teardown, committed).contains("requestedLayout != layout"),
        )
    }

    private fun readSource(relativePath: String): String {
        val candidates = listOf(
            File("app/src/$relativePath"),
            File("src/$relativePath"),
        )
        return requireNotNull(candidates.firstOrNull(File::isFile)) {
            "source file not found: $relativePath"
        }.readText()
    }
}
