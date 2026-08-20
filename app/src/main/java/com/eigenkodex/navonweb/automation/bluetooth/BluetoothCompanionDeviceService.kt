/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.eigenkodex.navonweb.automation.bluetooth

import android.companion.AssociationInfo
import android.companion.CompanionDeviceService
import android.companion.DevicePresenceEvent
import android.os.Build
import androidx.annotation.RequiresApi
import com.eigenkodex.navonweb.automation.AutomationServiceControllerProvider

internal object CompanionPresenceEventMatcher {
    fun api36(
        selectedAssociationId: Int?,
        eventAssociationId: Int,
        event: Int,
    ): BluetoothConnectionEvent? {
        if (selectedAssociationId == null || selectedAssociationId != eventAssociationId) return null
        return when (event) {
            DevicePresenceEvent.EVENT_BT_CONNECTED -> BluetoothConnectionEvent.CONNECTED
            DevicePresenceEvent.EVENT_BT_DISCONNECTED -> BluetoothConnectionEvent.DISCONNECTED
            else -> null
        }
    }

    fun legacy(
        selected: SelectedBluetoothDevice,
        eventAddress: String?,
        eventAssociationId: Int?,
        connected: Boolean,
    ): BluetoothConnectionEvent? {
        val normalizedAddress = eventAddress?.let(::normalizeBluetoothAddress) ?: return null
        if (selected.address != normalizedAddress) return null
        if (
            selected.companionAssociationId != null &&
            eventAssociationId != null &&
            selected.companionAssociationId != eventAssociationId
        ) {
            return null
        }
        return if (connected) BluetoothConnectionEvent.CONNECTED else BluetoothConnectionEvent.DISCONNECTED
    }
}

/** Receives system-owned companion presence callbacks; no connection polling is used. */
@Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
@RequiresApi(Build.VERSION_CODES.S)
class BluetoothCompanionDeviceService : CompanionDeviceService() {
    override fun onDevicePresenceEvent(event: DevicePresenceEvent) {
        if (Build.VERSION.SDK_INT < 36) return
        val selected = BluetoothAutomationStore(this).load().selectedDevice ?: return
        val connectionEvent = CompanionPresenceEventMatcher.api36(
            selectedAssociationId = selected.companionAssociationId,
            eventAssociationId = event.associationId,
            event = event.event,
        ) ?: return
        dispatch(connectionEvent, selected.address)
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    override fun onDeviceAppeared(associationInfo: AssociationInfo) {
        if (Build.VERSION.SDK_INT !in Build.VERSION_CODES.TIRAMISU until 36) return
        dispatchLegacyAssociation(associationInfo, connected = true)
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    override fun onDeviceDisappeared(associationInfo: AssociationInfo) {
        if (Build.VERSION.SDK_INT !in Build.VERSION_CODES.TIRAMISU until 36) return
        dispatchLegacyAssociation(associationInfo, connected = false)
    }

    override fun onDeviceAppeared(deviceAddress: String) {
        if (Build.VERSION.SDK_INT !in Build.VERSION_CODES.S until Build.VERSION_CODES.TIRAMISU) return
        dispatchLegacyAddress(deviceAddress, connected = true)
    }

    override fun onDeviceDisappeared(deviceAddress: String) {
        if (Build.VERSION.SDK_INT !in Build.VERSION_CODES.S until Build.VERSION_CODES.TIRAMISU) return
        dispatchLegacyAddress(deviceAddress, connected = false)
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun dispatchLegacyAssociation(
        associationInfo: AssociationInfo,
        connected: Boolean,
    ) {
        val selected = BluetoothAutomationStore(this).load().selectedDevice ?: return
        val address = associationInfo.deviceMacAddress?.toString()
        val event = CompanionPresenceEventMatcher.legacy(
            selected = selected,
            eventAddress = address,
            eventAssociationId = associationInfo.id,
            connected = connected,
        ) ?: return
        dispatch(event, selected.address)
    }

    private fun dispatchLegacyAddress(deviceAddress: String, connected: Boolean) {
        val selected = BluetoothAutomationStore(this).load().selectedDevice ?: return
        val event = CompanionPresenceEventMatcher.legacy(
            selected = selected,
            eventAddress = deviceAddress,
            eventAssociationId = null,
            connected = connected,
        ) ?: return
        dispatch(event, selected.address)
    }

    private fun dispatch(event: BluetoothConnectionEvent, selectedAddress: String) {
        BluetoothConnectionEventHandler(
            store = BluetoothAutomationStore(this),
            controller = AutomationServiceControllerProvider.get(this),
        ).handle(event, selectedAddress)
    }
}
