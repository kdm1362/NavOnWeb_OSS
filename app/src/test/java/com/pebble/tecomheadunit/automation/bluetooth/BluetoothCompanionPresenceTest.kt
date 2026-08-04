/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.pebble.tecomheadunit.automation.bluetooth

import android.companion.DevicePresenceEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BluetoothCompanionPresenceTest {
    @Test
    fun `association callback can only attach to device that requested it`() {
        var state = BluetoothAutomationState(
            selectedDevice = SelectedBluetoothDevice(SELECTED_ADDRESS, "Car audio"),
            lastSelectedDeviceConnected = true,
        )
        val store = BluetoothAutomationStore(
            readState = { state },
            persistState = {
                state = it
                true
            },
        )

        assertFalse(store.attachCompanionAssociation("00:11:22:33:44:55", 17))
        assertNull(store.load().selectedDevice?.companionAssociationId)
        assertTrue(store.attachCompanionAssociation(SELECTED_ADDRESS.lowercase(), 17))
        assertEquals(17, store.load().selectedDevice?.companionAssociationId)
        assertEquals(true, store.load().lastSelectedDeviceConnected)
    }

    @Test
    fun `api 36 accepts only exact association and classic bluetooth events`() {
        assertEquals(
            BluetoothConnectionEvent.CONNECTED,
            CompanionPresenceEventMatcher.api36(
                selectedAssociationId = 17,
                eventAssociationId = 17,
                event = DevicePresenceEvent.EVENT_BT_CONNECTED,
            ),
        )
        assertEquals(
            BluetoothConnectionEvent.DISCONNECTED,
            CompanionPresenceEventMatcher.api36(
                selectedAssociationId = 17,
                eventAssociationId = 17,
                event = DevicePresenceEvent.EVENT_BT_DISCONNECTED,
            ),
        )
        assertNull(
            CompanionPresenceEventMatcher.api36(
                selectedAssociationId = 17,
                eventAssociationId = 18,
                event = DevicePresenceEvent.EVENT_BT_CONNECTED,
            ),
        )
        assertNull(
            CompanionPresenceEventMatcher.api36(
                selectedAssociationId = 17,
                eventAssociationId = 17,
                event = DevicePresenceEvent.EVENT_BLE_APPEARED,
            ),
        )
    }

    @Test
    fun `legacy callbacks match address and association without polling`() {
        val selected = SelectedBluetoothDevice(SELECTED_ADDRESS, "Car audio", 17)

        assertEquals(
            BluetoothConnectionEvent.CONNECTED,
            CompanionPresenceEventMatcher.legacy(
                selected = selected,
                eventAddress = SELECTED_ADDRESS.lowercase(),
                eventAssociationId = 17,
                connected = true,
            ),
        )
        assertNull(
            CompanionPresenceEventMatcher.legacy(
                selected = selected,
                eventAddress = SELECTED_ADDRESS,
                eventAssociationId = 18,
                connected = false,
            ),
        )
        assertNull(
            CompanionPresenceEventMatcher.legacy(
                selected = selected,
                eventAddress = "00:11:22:33:44:55",
                eventAssociationId = 17,
                connected = false,
            ),
        )
    }

    private companion object {
        const val SELECTED_ADDRESS = "AA:BB:CC:DD:EE:FF"
    }
}
