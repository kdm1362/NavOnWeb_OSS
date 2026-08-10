/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.pebble.tecomheadunit.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserConnectionLinkPolicyTest {
    @Test
    fun `only a bounded numeric http address can be revealed`() {
        assertEquals(
            "http://192.168.50.10:8787",
            numericLocalBrowserUrlOrNull(" http://192.168.50.10:8787 "),
        )
        assertEquals(
            "http://127.0.0.1:8787/",
            numericLocalBrowserUrlOrNull("http://127.0.0.1:8787/"),
        )
        assertNull(numericLocalBrowserUrlOrNull("https://navonweb.com"))
        assertNull(numericLocalBrowserUrlOrNull("http://local.test:8787"))
        assertNull(numericLocalBrowserUrlOrNull("http://256.1.1.1:8787"))
        assertNull(numericLocalBrowserUrlOrNull("http://192.168.50.2:0"))
        assertNull(numericLocalBrowserUrlOrNull("http://192.168.50.2:8787/admin"))
    }

    @Test
    fun `normal address remains selected until a continuous three second hold`() {
        val cloud = "https://navonweb.com"
        val local = "http://192.168.50.10:8787"

        assertEquals(3_000L, LOCAL_BROWSER_URL_REVEAL_HOLD_MILLIS)
        assertFalse(shouldRevealLocalBrowserUrl(true, local))
        assertFalse(shouldRevealLocalBrowserUrl(false, local))
        assertTrue(shouldRevealLocalBrowserUrl(null, local))
        assertFalse(shouldRevealLocalBrowserUrl(null, "http://local.test:8787"))

        assertEquals(
            BrowserLinkPresentation(cloud, isDebugLocal = false),
            browserLinkPresentation(cloud, local, revealLocalUrl = false),
        )
        assertEquals(
            BrowserLinkPresentation(local, isDebugLocal = true),
            browserLinkPresentation(cloud, local, revealLocalUrl = true),
        )
    }
}
