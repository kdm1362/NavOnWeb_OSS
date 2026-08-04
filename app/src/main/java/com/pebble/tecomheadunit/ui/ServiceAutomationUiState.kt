/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.pebble.tecomheadunit.ui

import com.pebble.tecomheadunit.automation.AutomationTriggerMode

data class BluetoothDeviceOptionUi(
    /** App-private selection key. It must never be rendered or copied into diagnostics. */
    val id: String,
    val name: String,
    val addressHint: String,
)

data class ServiceAutomationUiState(
    val mode: AutomationTriggerMode = AutomationTriggerMode.NONE,
    val selectedBluetoothDeviceName: String? = null,
    val selectedBluetoothAddressHint: String? = null,
    val bluetoothDevices: List<BluetoothDeviceOptionUi> = emptyList(),
    val bluetoothPickerVisible: Boolean = false,
    val hotspotSupported: Boolean = false,
    val feedback: String = "",
)
