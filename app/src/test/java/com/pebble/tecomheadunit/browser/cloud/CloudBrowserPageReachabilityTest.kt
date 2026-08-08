/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.pebble.tecomheadunit.browser.cloud

import org.junit.Assert.assertEquals
import org.junit.Test

class CloudBrowserPageReachabilityTest {
    @Test
    fun `unknown and reachable public page prefer cloud address`() {
        assertEquals(
            CLOUD_URL,
            BrowserAddressPolicy.select(
                cloudUrl = CLOUD_URL,
                localUrl = LOCAL_URL,
                cloudAvailability = CloudBrowserPageAvailability.UNKNOWN,
            ),
        )
        assertEquals(
            CLOUD_URL,
            BrowserAddressPolicy.select(
                cloudUrl = CLOUD_URL,
                localUrl = LOCAL_URL,
                cloudAvailability = CloudBrowserPageAvailability.REACHABLE,
            ),
        )
    }

    @Test
    fun `local address is used only after public page is confirmed unreachable`() {
        val tracker = CloudBrowserPageAvailabilityTracker(failuresBeforeFallback = 2)

        assertEquals(
            CloudBrowserPageAvailability.UNKNOWN,
            tracker.record(reachable = false),
        )
        assertEquals(
            CLOUD_URL,
            BrowserAddressPolicy.select(CLOUD_URL, LOCAL_URL, tracker.availability),
        )
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
    fun `one successful public page probe restores cloud address immediately`() {
        val tracker = CloudBrowserPageAvailabilityTracker(failuresBeforeFallback = 2)
        tracker.record(reachable = false)
        tracker.record(reachable = false)

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
        const val LOCAL_URL = "http://192.168.31.231:8080"
    }
}
