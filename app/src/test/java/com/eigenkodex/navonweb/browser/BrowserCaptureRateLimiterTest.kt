/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.eigenkodex.navonweb.browser

import org.junit.Assert.assertEquals
import org.junit.Test

class BrowserCaptureRateLimiterTest {
    @Test
    fun `first subscriber can capture immediately`() {
        val limiter = BrowserCaptureRateLimiter(intervalMillis = 200L)

        assertEquals(0L, limiter.remainingDelayMillis(nowMillis = 10_000L))
    }

    @Test
    fun `rapid unsubscribe and resubscribe cannot exceed global rate`() {
        val limiter = BrowserCaptureRateLimiter(intervalMillis = 200L)
        limiter.markRequestStarted(nowMillis = 1_000L)

        assertEquals(190L, limiter.remainingDelayMillis(nowMillis = 1_010L))
        assertEquals(1L, limiter.remainingDelayMillis(nowMillis = 1_199L))
        assertEquals(0L, limiter.remainingDelayMillis(nowMillis = 1_200L))
    }

    @Test
    fun `each actual request advances the rate window`() {
        val limiter = BrowserCaptureRateLimiter(intervalMillis = 100L)
        limiter.markRequestStarted(nowMillis = 5_000L)
        limiter.markRequestStarted(nowMillis = 5_100L)

        assertEquals(100L, limiter.remainingDelayMillis(nowMillis = 5_100L))
        assertEquals(0L, limiter.remainingDelayMillis(nowMillis = 5_200L))
    }
}
