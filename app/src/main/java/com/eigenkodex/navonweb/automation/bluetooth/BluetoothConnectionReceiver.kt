/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.eigenkodex.navonweb.automation.bluetooth

import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import com.eigenkodex.navonweb.automation.AutomationDispatchResult
import com.eigenkodex.navonweb.automation.AutomationServiceController
import com.eigenkodex.navonweb.automation.AutomationServiceControllerProvider
import com.eigenkodex.navonweb.automation.AutomationTriggerMode

enum class BluetoothConnectionEvent {
    CONNECTED,
    DISCONNECTED,
}

sealed interface BluetoothConnectionHandlingResult {
    data object IgnoredNoSelection : BluetoothConnectionHandlingResult

    data object IgnoredDifferentDevice : BluetoothConnectionHandlingResult

    data object IgnoredInactiveMode : BluetoothConnectionHandlingResult

    data object PersistenceFailed : BluetoothConnectionHandlingResult

    data class Dispatched(val result: AutomationDispatchResult) : BluetoothConnectionHandlingResult
}

/** Pure event filter used by both the ACL compatibility receiver and companion presence callbacks. */
class BluetoothConnectionEventHandler(
    private val store: BluetoothAutomationStore,
    private val controller: AutomationServiceController,
) {
    fun handle(
        event: BluetoothConnectionEvent,
        deviceAddress: String,
    ): BluetoothConnectionHandlingResult {
        val selected = store.load().selectedDevice
            ?: return BluetoothConnectionHandlingResult.IgnoredNoSelection
        val normalizedAddress = normalizeBluetoothAddress(deviceAddress)
            ?: return BluetoothConnectionHandlingResult.IgnoredDifferentDevice
        if (normalizedAddress != selected.address) {
            return BluetoothConnectionHandlingResult.IgnoredDifferentDevice
        }

        val controllerState = controller.snapshot()
        if (controllerState.mode != AutomationTriggerMode.BLUETOOTH) {
            return BluetoothConnectionHandlingResult.IgnoredInactiveMode
        }
        val connected = event == BluetoothConnectionEvent.CONNECTED
        if (!store.recordMatchingConnectionEvent(connected)) {
            return BluetoothConnectionHandlingResult.PersistenceFailed
        }
        return BluetoothConnectionHandlingResult.Dispatched(
            controller.onTriggerStateChanged(
                source = AutomationTriggerMode.BLUETOOTH,
                generation = controllerState.generation,
                active = connected,
            ),
        )
    }
}

/**
 * ACL broadcast compatibility path for API 26-35.
 *
 * Manifest integration must declare BLUETOOTH_CONNECT (plus legacy BLUETOOTH with maxSdkVersion
 * 30) and include only the platform-protected ACTION_ACL_CONNECTED/DISCONNECTED actions.
 * Android 12+ requires user-granted BLUETOOTH_CONNECT before this receiver can inspect a device.
 * Production background starts should prefer CompanionDeviceService presence callbacks; the shared
 * controller still catches background foreground-service restrictions and posts a tap fallback.
 */
class BluetoothConnectionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val event = when (intent.action) {
            BluetoothDevice.ACTION_ACL_CONNECTED -> BluetoothConnectionEvent.CONNECTED
            BluetoothDevice.ACTION_ACL_DISCONNECTED -> BluetoothConnectionEvent.DISCONNECTED
            else -> return
        }
        if (!BluetoothConnectPermission.isGranted(context)) return
        val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
        } ?: return
        val address = runCatching { device.address }.getOrNull() ?: return
        BluetoothConnectionEventHandler(
            store = BluetoothAutomationStore(context),
            controller = AutomationServiceControllerProvider.get(context),
        ).handle(event, address)
    }
}
