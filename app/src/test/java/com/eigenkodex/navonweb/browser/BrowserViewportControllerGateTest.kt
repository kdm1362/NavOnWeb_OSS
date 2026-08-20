/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.eigenkodex.navonweb.browser

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserViewportControllerGateTest {
    @Test
    fun `same page refreshes while another modern page waits for expiry`() {
        var now = 1_000L
        val gate = BrowserViewportControllerGate(nowMillis = { now }, leaseMillis = 15_000L)

        assertTrue(gate.tryAcquire("credential-a", "page-a"))
        now += 10_000L
        assertTrue(gate.refresh("credential-a", "page-a"))
        now += 10_000L
        assertFalse(gate.tryAcquire("credential-b", "page-b"))
        now += 5_001L
        assertTrue(gate.tryAcquire("credential-b", "page-b"))
    }

    @Test
    fun `one live page cannot preempt another live page`() {
        val gate = BrowserViewportControllerGate(nowMillis = { 1_000L })

        assertTrue(gate.tryAcquire("modern-credential", "modern-page"))
        assertFalse(gate.tryAcquire("other-credential", "other-page"))
    }

    @Test
    fun `refresh rejects non owner`() {
        val gate = BrowserViewportControllerGate(nowMillis = { 1_000L })
        assertTrue(gate.tryAcquire("credential-a", "page-a"))
        assertFalse(gate.refresh("credential-a", "page-b"))
        assertFalse(gate.refresh("credential-b", "page-a"))
    }
}
