/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.eigenkodex.navonweb.browser.cloud

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudBrowserPageReachabilityTest {
    @Test
    fun `unknown displays local until one successful public probe`() {
        assertEquals(
            LOCAL_URL,
            BrowserAddressPolicy.select(
                cloudUrl = CLOUD_URL,
                localUrl = LOCAL_URL,
                cloudAvailability = CloudBrowserPageAvailability.UNKNOWN,
            ),
        )
        val tracker = CloudBrowserPageAvailabilityTracker()
        assertEquals(
            CloudBrowserPageAvailability.REACHABLE,
            tracker.record(reachable = true),
        )
        assertEquals(
            CLOUD_URL,
            BrowserAddressPolicy.select(CLOUD_URL, LOCAL_URL, tracker.availability),
        )
    }

    @Test
    fun `an isolated failed probe keeps the public address`() {
        val tracker = CloudBrowserPageAvailabilityTracker()
        tracker.record(reachable = true)

        // A relayed origin shares the radio with the projection stream, so one failure is not
        // evidence the page is down and must not move the address the user is reading.
        assertEquals(
            CloudBrowserPageAvailability.REACHABLE,
            tracker.record(reachable = false),
        )
        assertEquals(
            CLOUD_URL,
            BrowserAddressPolicy.select(CLOUD_URL, LOCAL_URL, tracker.availability),
        )
    }

    @Test
    fun `two consecutive failures switch to local`() {
        val tracker = CloudBrowserPageAvailabilityTracker()
        tracker.record(reachable = true)
        tracker.record(reachable = false)

        assertEquals(
            CloudBrowserPageAvailability.UNREACHABLE,
            tracker.record(reachable = false),
        )
        assertEquals(
            LOCAL_URL,
            BrowserAddressPolicy.select(CLOUD_URL, LOCAL_URL, tracker.availability),
        )
    }

    @Test
    fun `a success between failures prevents the switch`() {
        val tracker = CloudBrowserPageAvailabilityTracker()
        tracker.record(reachable = true)
        tracker.record(reachable = false)
        tracker.record(reachable = true)

        assertEquals(
            CloudBrowserPageAvailability.REACHABLE,
            tracker.record(reachable = false),
        )
    }

    @Test
    fun `an unknown tracker stays unknown until the failure streak completes`() {
        val tracker = CloudBrowserPageAvailabilityTracker()

        assertEquals(
            CloudBrowserPageAvailability.UNKNOWN,
            tracker.record(reachable = false),
        )
        assertEquals(
            CloudBrowserPageAvailability.UNREACHABLE,
            tracker.record(reachable = false),
        )
    }

    @Test
    fun `one successful probe restores cloud immediately after failure`() {
        val tracker = CloudBrowserPageAvailabilityTracker()
        tracker.record(reachable = false)

        assertEquals(CloudBrowserPageAvailability.REACHABLE, tracker.record(reachable = true))
        assertEquals(
            CLOUD_URL,
            BrowserAddressPolicy.select(CLOUD_URL, LOCAL_URL, tracker.availability),
        )
    }

    @Test
    fun `confirmed states are reprobed every five seconds`() {
        val source = projectionServiceSource().readText(Charsets.UTF_8)
        val reachabilityPolicySource = reachabilitySource().readText(Charsets.UTF_8)

        assertTrue(source.contains("CLOUD_BROWSER_REACHABLE_CHECK_MILLIS = 5_000L"))
        assertTrue(source.contains("CLOUD_BROWSER_FALLBACK_CHECK_MILLIS = 5_000L"))
        assertTrue(reachabilityPolicySource.contains("PROBE_TIMEOUT_SECONDS = 5L"))
    }

    @Test
    fun `build without cloud configuration always uses local address`() {
        assertEquals(
            LOCAL_URL,
            BrowserAddressPolicy.select(
                cloudUrl = null,
                localUrl = LOCAL_URL,
                cloudAvailability = CloudBrowserPageAvailability.UNKNOWN,
            ),
        )
    }

    private companion object {
        const val CLOUD_URL = "https://navonweb.com"
        const val LOCAL_URL = "http://192.168.0.231:8080"
    }

    private fun projectionServiceSource(): File = listOf(
        File("app/src/main/java/com/eigenkodex/navonweb/service/ProjectionService.kt"),
        File("src/main/java/com/eigenkodex/navonweb/service/ProjectionService.kt"),
    ).first(File::isFile)

    private fun reachabilitySource(): File = listOf(
        File("app/src/main/java/com/eigenkodex/navonweb/browser/cloud/CloudBrowserPageReachability.kt"),
        File("src/main/java/com/eigenkodex/navonweb/browser/cloud/CloudBrowserPageReachability.kt"),
    ).first(File::isFile)
}
