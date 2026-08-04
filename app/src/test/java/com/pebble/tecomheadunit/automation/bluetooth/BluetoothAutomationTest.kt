/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.pebble.tecomheadunit.automation.bluetooth

import com.pebble.tecomheadunit.automation.AutomationControlState
import com.pebble.tecomheadunit.automation.AutomationControlStateStore
import com.pebble.tecomheadunit.automation.AutomationDispatchResult
import com.pebble.tecomheadunit.automation.AutomationRuntimeResult
import com.pebble.tecomheadunit.automation.AutomationServiceRuntime
import com.pebble.tecomheadunit.automation.AutomationTriggerMode
import com.pebble.tecomheadunit.automation.CoordinatingAutomationServiceController
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BluetoothAutomationTest {
    @Test
    fun `single selected device and companion association are app state`() {
        var state = BluetoothAutomationState()
        val store = BluetoothAutomationStore(
            readState = { state },
            persistState = {
                state = it
                true
            },
        )
        val selected = SelectedBluetoothDevice(
            address = "AA:BB:CC:DD:EE:FF",
            displayName = "Car audio",
            companionAssociationId = 42,
        )

        assertTrue(store.selectDevice(selected))
        assertEquals(selected, store.load().selectedDevice)
        assertNull(store.load().lastSelectedDeviceConnected)
        assertTrue(store.recordMatchingConnectionEvent(true))
        assertEquals(true, store.load().lastSelectedDeviceConnected)
        assertTrue(store.selectDevice(selected.copy(displayName = "Renamed car")))
        assertEquals(42, store.load().selectedDevice?.companionAssociationId)
        assertNull(store.load().lastSelectedDeviceConnected)
        assertTrue(store.clearSelection())
        assertNull(store.load().selectedDevice)
    }

    @Test
    fun `device names remove controls and are bounded by code points`() {
        val longName = "A\u0000\n" + "한".repeat(100)
        val sanitized = sanitizeBluetoothDeviceName(longName)

        assertFalse(sanitized.any { Character.isISOControl(it) })
        assertEquals(MAX_DEVICE_NAME_CODE_POINTS, sanitized.codePointCount(0, sanitized.length))
    }

    @Test
    fun `different device broadcast is ignored`() {
        val fixture = Fixture()

        val result = fixture.handler.handle(
            BluetoothConnectionEvent.CONNECTED,
            "00:11:22:33:44:55",
        )

        assertTrue(result is BluetoothConnectionHandlingResult.IgnoredDifferentDevice)
        assertEquals(0, fixture.runtime.starts)
        assertNull(fixture.bluetoothState.lastSelectedDeviceConnected)
    }

    @Test
    fun `matching connect starts and matching disconnect stops`() {
        val fixture = Fixture()

        val connected = fixture.handler.handle(
            BluetoothConnectionEvent.CONNECTED,
            SELECTED_ADDRESS.lowercase(),
        )
        val disconnected = fixture.handler.handle(
            BluetoothConnectionEvent.DISCONNECTED,
            SELECTED_ADDRESS,
        )

        assertTrue(
            (connected as BluetoothConnectionHandlingResult.Dispatched).result is
                AutomationDispatchResult.Started,
        )
        assertTrue(
            (disconnected as BluetoothConnectionHandlingResult.Dispatched).result is
                AutomationDispatchResult.Stopped,
        )
        assertEquals(1, fixture.runtime.starts)
        assertEquals(1, fixture.runtime.stops)
        assertEquals(false, fixture.bluetoothState.lastSelectedDeviceConnected)
    }

    @Test
    fun `matching event is ignored when bluetooth mode is not selected`() {
        val fixture = Fixture(selectBluetoothMode = false)

        val result = fixture.handler.handle(
            BluetoothConnectionEvent.CONNECTED,
            SELECTED_ADDRESS,
        )

        assertTrue(result is BluetoothConnectionHandlingResult.IgnoredInactiveMode)
        assertEquals(0, fixture.runtime.starts)
    }

    private class Fixture(selectBluetoothMode: Boolean = true) {
        var bluetoothState = BluetoothAutomationState(
            selectedDevice = SelectedBluetoothDevice(SELECTED_ADDRESS, "Test car", 7),
        )
        private val bluetoothStore = BluetoothAutomationStore(
            readState = { bluetoothState },
            persistState = {
                bluetoothState = it
                true
            },
        )
        private val controlStore = object : AutomationControlStateStore {
            var value = AutomationControlState()
            override fun load() = value
            override fun save(state: AutomationControlState): Boolean {
                value = state
                return true
            }
        }
        val runtime = FakeRuntime()
        private val controller = CoordinatingAutomationServiceController(controlStore, runtime)
        val handler = BluetoothConnectionEventHandler(bluetoothStore, controller)

        init {
            if (selectBluetoothMode) controller.selectMode(AutomationTriggerMode.BLUETOOTH)
        }
    }

    private class FakeRuntime : AutomationServiceRuntime {
        var running = false
        var starts = 0
        var stops = 0

        override fun isRunning() = running
        override fun start(): AutomationRuntimeResult {
            starts += 1
            running = true
            return AutomationRuntimeResult.Success
        }
        override fun stop(): AutomationRuntimeResult {
            stops += 1
            running = false
            return AutomationRuntimeResult.Success
        }
    }

    private companion object {
        const val SELECTED_ADDRESS = "AA:BB:CC:DD:EE:FF"
    }
}
