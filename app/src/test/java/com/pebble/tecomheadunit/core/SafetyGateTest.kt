package com.pebble.tecomheadunit.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SafetyGateTest {
    private val gate = SafetyGate()

    @Test
    fun unknownStateFailsClosed() {
        assertFalse(gate.canStart(VehicleState.UNKNOWN).allowed)
        assertFalse(gate.canSendInput(VehicleState.UNKNOWN).allowed)
    }

    @Test
    fun movingStateFailsClosed() {
        assertFalse(gate.canStart(VehicleState.MOVING).allowed)
        assertFalse(gate.canSendInput(VehicleState.MOVING).allowed)
    }

    @Test
    fun benchStationaryAllowsP0() {
        assertTrue(gate.canStart(VehicleState.BENCH_STATIONARY).allowed)
        assertTrue(gate.canSendInput(VehicleState.BENCH_STATIONARY).allowed)
    }
}
