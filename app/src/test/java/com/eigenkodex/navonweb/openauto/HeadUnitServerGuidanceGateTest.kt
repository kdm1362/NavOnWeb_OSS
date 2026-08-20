/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.eigenkodex.navonweb.openauto

import com.eigenkodex.navonweb.session.AndroidAutoConnectionReason
import com.eigenkodex.navonweb.session.AndroidAutoConnectionState
import com.eigenkodex.navonweb.session.AndroidAutoConnectionStatus
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HeadUnitServerGuidanceGateTest {
    private val stopped = AndroidAutoConnectionStatus(
        state = AndroidAutoConnectionState.DISCONNECTED,
        reason = AndroidAutoConnectionReason.HEAD_UNIT_SERVER_STOPPED,
    )

    @Test
    fun `failure is ignored until a service start arms guidance`() {
        val gate = HeadUnitServerGuidanceGate()

        assertFalse(gate.consume(stopped))
    }

    @Test
    fun `armed head unit server failure is consumed once`() {
        val gate = HeadUnitServerGuidanceGate()
        gate.arm()

        assertTrue(gate.consume(stopped))
        assertFalse(gate.consume(stopped))
    }

    @Test
    fun `transient connection states keep guidance armed`() {
        val gate = HeadUnitServerGuidanceGate()
        gate.arm()

        assertFalse(
            gate.consume(
                AndroidAutoConnectionStatus(
                    state = AndroidAutoConnectionState.RECONNECTING,
                    reason = AndroidAutoConnectionReason.WAITING_FOR_PHONE,
                ),
            ),
        )
        assertTrue(gate.consume(stopped))
    }

    @Test
    fun `successful connection disarms a pending warning`() {
        val gate = HeadUnitServerGuidanceGate()
        gate.arm()

        assertFalse(
            gate.consume(
                AndroidAutoConnectionStatus(
                    state = AndroidAutoConnectionState.CONNECTED,
                    reason = AndroidAutoConnectionReason.STREAMING,
                ),
            ),
        )
        assertFalse(gate.consume(stopped))
    }

    @Test
    fun `manual stop disarms guidance`() {
        val gate = HeadUnitServerGuidanceGate()
        gate.arm()
        gate.disarm()

        assertFalse(gate.consume(stopped))
    }
}
