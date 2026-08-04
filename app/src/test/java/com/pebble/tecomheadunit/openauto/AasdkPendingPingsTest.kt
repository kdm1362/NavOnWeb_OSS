/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.pebble.tecomheadunit.openauto

import com.pebble.tecomheadunit.openauto.protocol.AasdkProtocolException
import com.pebble.tecomheadunit.openauto.protocol.AasdkTcpTransport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AasdkPendingPingsTest {
    @Test
    fun `delayed response still matches request sent before the latest request`() {
        val pending = pendingPings()
        pending.recordRequest(100L)
        pending.recordRequest(200L)

        assertEquals(100L, pending.acknowledge(100L))
        assertEquals(1, pending.size)
        assertEquals(200L, pending.acknowledge(200L))
        assertEquals(0, pending.size)
    }

    @Test
    fun `timestamped responses can acknowledge outstanding requests out of order`() {
        val pending = pendingPings()
        pending.recordRequest(100L)
        pending.recordRequest(200L)

        assertEquals(200L, pending.acknowledge(200L))
        assertEquals(100L, pending.acknowledge(100L))
        assertEquals(0, pending.size)
    }

    @Test
    fun `timestamp-less response acknowledges the oldest request`() {
        val pending = pendingPings()
        pending.recordRequest(100L)
        pending.recordRequest(200L)

        assertEquals(100L, pending.acknowledge(null))
        assertEquals(1, pending.size)
        assertEquals(200L, pending.acknowledge(null))
        assertEquals(0, pending.size)
    }

    @Test
    fun `unknown timestamp is rejected without discarding valid requests`() {
        val pending = pendingPings()
        pending.recordRequest(100L)
        pending.recordRequest(200L)

        assertThrows(AasdkProtocolException::class.java) {
            pending.acknowledge(300L)
        }

        assertEquals(2, pending.size)
        assertEquals(100L, pending.acknowledge(100L))
        assertEquals(200L, pending.acknowledge(200L))
    }

    @Test
    fun `unsolicited response and duplicate request token are rejected`() {
        val pending = pendingPings()

        assertThrows(AasdkProtocolException::class.java) {
            pending.acknowledge(100L)
        }

        pending.recordRequest(100L)
        assertThrows(AasdkProtocolException::class.java) {
            pending.recordRequest(100L)
        }
        assertEquals(1, pending.size)
    }

    @Test
    fun `pending request window is bounded and an acknowledgement frees capacity`() {
        val pending = AasdkPendingPings(maxSize = 2)
        pending.recordRequest(100L)
        pending.recordRequest(200L)

        assertTrue(pending.isFull)
        assertThrows(AasdkProtocolException::class.java) {
            pending.recordRequest(300L)
        }
        assertEquals(2, pending.size)

        pending.acknowledge(100L)
        assertFalse(pending.isFull)
        pending.recordRequest(300L)
        assertEquals(2, pending.size)
    }

    @Test
    fun `runtime tolerates at least thirty seconds and socket timeout remains longer`() {
        assertEquals(5_000L, AASDK_PING_INTERVAL_MILLIS)
        assertTrue(AASDK_MIN_PING_TOLERANCE_MILLIS >= 30_000L)
        assertEquals(
            AASDK_MIN_PING_TOLERANCE_MILLIS,
            AASDK_PING_INTERVAL_MILLIS * AASDK_MAX_PENDING_PINGS,
        )
        assertTrue(
            AasdkTcpTransport.DEFAULT_READ_TIMEOUT_MILLIS > AASDK_MIN_PING_TOLERANCE_MILLIS,
        )
    }

    private fun pendingPings(): AasdkPendingPings =
        AasdkPendingPings(maxSize = AASDK_MAX_PENDING_PINGS)
}
