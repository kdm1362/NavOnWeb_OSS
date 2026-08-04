package com.pebble.tecomheadunit.browser.cloud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudRelayRetryBackoffTest {
    @Test
    fun reconnectBackoffStaysInsideOneToFifteenSeconds() {
        val delays = (0..20).flatMap { attempt ->
            listOf(-1.0, 0.0, 1.0).map { jitter ->
                CloudRelayRetryBackoff.delayMillis(
                    attempt = attempt,
                    baseMillis = 1_000L,
                    maxMillis = 15_000L,
                    jitterUnit = jitter,
                )
            }
        }

        assertTrue(delays.all { it in 1_000L..15_000L })
        assertEquals(1_000L, delays.first())
        assertEquals(15_000L, delays.last())
    }

    @Test
    fun jitterSeparatesClientsWithoutBreakingExponentialGrowth() {
        assertEquals(1_600L, delay(attempt = 1, jitter = -1.0))
        assertEquals(2_000L, delay(attempt = 1, jitter = 0.0))
        assertEquals(2_400L, delay(attempt = 1, jitter = 1.0))
        assertEquals(12_000L, delay(attempt = 4, jitter = -1.0))
        assertEquals(15_000L, delay(attempt = 4, jitter = 1.0))
    }

    private fun delay(attempt: Int, jitter: Double): Long =
        CloudRelayRetryBackoff.delayMillis(
            attempt = attempt,
            baseMillis = 1_000L,
            maxMillis = 15_000L,
            jitterUnit = jitter,
        )
}
