/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.pebble.tecomheadunit.browser.cloud

import org.junit.Assert.assertEquals
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
    fun `first failed probe after reachable switches to local immediately`() {
        val tracker = CloudBrowserPageAvailabilityTracker()
        tracker.record(reachable = true)

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
        const val LOCAL_URL = "http://192.168.50.10:8080"
    }
}
