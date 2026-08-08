/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.pebble.tecomheadunit.service

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PortraitProtocolFallbackPolicyTest {
    @Test
    fun `watchdog is armed by service discovery and cancelled by accepted video`() {
        val service = readSource("main/java/com/pebble/tecomheadunit/service/ProjectionService.kt")

        assertTrue(service.contains("status.startsWith(SERVICE_DISCOVERY_RESPONSE_SENT_STATUS)"))
        assertTrue(service.contains("status.startsWith(VIDEO_MEDIA_ACCEPTED_STATUS)"))
        assertTrue(service.contains("delay(PORTRAIT_MEDIA_ACCEPTANCE_TIMEOUT_MILLIS)"))
        assertTrue(service.contains("disablePortraitEncodedViewportsForSession()"))
        assertTrue(service.contains("portraitMediaAcceptanceWatchdogJob?.cancel()"))
        assertTrue(service.contains("serviceDiscoveryResponseSent &&"))
        assertTrue(service.contains("!videoMediaAccepted"))
        assertTrue(service.contains("reason=TERMINAL_BEFORE_VIDEO"))
    }

    @Test
    fun `prepared viewport is committed after teardown even if a newer request arrives`() {
        val service = readSource("main/java/com/pebble/tecomheadunit/service/ProjectionService.kt")
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
