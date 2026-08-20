/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.eigenkodex.navonweb.automation.bluetooth

import android.content.Context
import java.util.Locale

data class SelectedBluetoothDevice(
    val address: String,
    val displayName: String,
    val companionAssociationId: Int? = null,
) {
    init {
        require(normalizeBluetoothAddress(address) == address)
        require(displayName.isNotBlank())
        require(displayName.codePointCount(0, displayName.length) <= MAX_DEVICE_NAME_CODE_POINTS)
        require(companionAssociationId == null || companionAssociationId >= 0)
    }
}

data class BluetoothAutomationState(
    val selectedDevice: SelectedBluetoothDevice? = null,
    /** Last matching broadcast/presence event; null is not equivalent to disconnected. */
    val lastSelectedDeviceConnected: Boolean? = null,
)

/** App-private store. Device identifiers must never be copied to diagnostics or public logs. */
class BluetoothAutomationStore internal constructor(
    private val readState: () -> BluetoothAutomationState,
    private val persistState: (BluetoothAutomationState) -> Boolean,
) {
    constructor(context: Context) : this(
        readState = {
            val preferences = context.applicationContext.getSharedPreferences(
                PREFERENCES_NAME,
                Context.MODE_PRIVATE,
            )
            val address = preferences.getString(KEY_ADDRESS, null)
                ?.let(::normalizeBluetoothAddress)
            val displayName = preferences.getString(KEY_DISPLAY_NAME, null)
                ?.let(::sanitizeBluetoothDeviceName)
                ?.takeIf(String::isNotBlank)
            val associationId = preferences.getInt(KEY_ASSOCIATION_ID, NO_ASSOCIATION_ID)
                .takeIf { it >= 0 }
            val selected = if (address != null && displayName != null) {
                SelectedBluetoothDevice(address, displayName, associationId)
            } else {
                null
            }
            BluetoothAutomationState(
                selectedDevice = selected,
                lastSelectedDeviceConnected = if (
                    selected != null && preferences.contains(KEY_LAST_CONNECTED)
                ) {
                    preferences.getBoolean(KEY_LAST_CONNECTED, false)
                } else {
                    null
                },
            )
        },
        persistState = { state ->
            val preferences = context.applicationContext.getSharedPreferences(
                PREFERENCES_NAME,
                Context.MODE_PRIVATE,
            )
            val editor = preferences.edit()
            val selected = state.selectedDevice
            if (selected == null) {
                editor
                    .remove(KEY_ADDRESS)
                    .remove(KEY_DISPLAY_NAME)
                    .remove(KEY_ASSOCIATION_ID)
                    .remove(KEY_LAST_CONNECTED)
            } else {
                editor
                    .putString(KEY_ADDRESS, selected.address)
                    .putString(KEY_DISPLAY_NAME, selected.displayName)
                    .putInt(KEY_ASSOCIATION_ID, selected.companionAssociationId ?: NO_ASSOCIATION_ID)
                if (state.lastSelectedDeviceConnected == null) {
                    editor.remove(KEY_LAST_CONNECTED)
                } else {
                    editor.putBoolean(KEY_LAST_CONNECTED, state.lastSelectedDeviceConnected)
                }
            }
            editor.commit()
        },
    )

    fun load(): BluetoothAutomationState = readState().normalized()

    /** Changing the device clears event state; it does not synthesize a disconnect. */
    fun selectDevice(device: SelectedBluetoothDevice): Boolean {
        val address = normalizeBluetoothAddress(device.address) ?: return false
        val displayName = sanitizeBluetoothDeviceName(device.displayName)
            .takeIf(String::isNotBlank) ?: return false
        val previous = load().selectedDevice
        val associationId = device.companionAssociationId
            ?: previous?.takeIf { it.address == address }?.companionAssociationId
        return persistState(
            BluetoothAutomationState(
                selectedDevice = SelectedBluetoothDevice(address, displayName, associationId),
                lastSelectedDeviceConnected = null,
            ),
        )
    }

    fun clearSelection(): Boolean = persistState(BluetoothAutomationState())

    /**
     * Attaches a system-owned companion association to the device that initiated the request.
     *
     * Association callbacks are asynchronous. Requiring the expected address prevents a delayed
     * callback from attaching an association to a device the user selected in the meantime.
     */
    fun attachCompanionAssociation(
        expectedAddress: String,
        associationId: Int,
    ): Boolean {
        if (associationId < 0) return false
        val normalizedExpectedAddress = normalizeBluetoothAddress(expectedAddress) ?: return false
        val current = load()
        val selected = current.selectedDevice ?: return false
        if (selected.address != normalizedExpectedAddress) return false
        return persistState(
            current.copy(
                selectedDevice = selected.copy(companionAssociationId = associationId),
            ),
        )
    }

    fun recordMatchingConnectionEvent(connected: Boolean): Boolean {
        val current = load()
        if (current.selectedDevice == null) return false
        return persistState(current.copy(lastSelectedDeviceConnected = connected))
    }

    private fun BluetoothAutomationState.normalized(): BluetoothAutomationState {
        val selected = selectedDevice ?: return BluetoothAutomationState()
        val address = normalizeBluetoothAddress(selected.address) ?: return BluetoothAutomationState()
        val name = sanitizeBluetoothDeviceName(selected.displayName).takeIf(String::isNotBlank)
            ?: return BluetoothAutomationState()
        val associationId = selected.companionAssociationId?.takeIf { it >= 0 }
        return copy(selectedDevice = SelectedBluetoothDevice(address, name, associationId))
    }

    private companion object {
        const val PREFERENCES_NAME = "bluetooth_service_automation"
        const val KEY_ADDRESS = "selected_device_address_v1"
        const val KEY_DISPLAY_NAME = "selected_device_name_v1"
        const val KEY_ASSOCIATION_ID = "companion_association_id_v1"
        const val KEY_LAST_CONNECTED = "last_selected_device_connected_v1"
        const val NO_ASSOCIATION_ID = -1
    }
}

internal const val MAX_DEVICE_NAME_CODE_POINTS = 80
private val BLUETOOTH_ADDRESS_PATTERN = Regex("^[0-9A-F]{2}(?::[0-9A-F]{2}){5}$")

internal fun normalizeBluetoothAddress(value: String): String? = value
    .trim()
    .uppercase(Locale.ROOT)
    .takeIf(BLUETOOTH_ADDRESS_PATTERN::matches)

internal fun sanitizeBluetoothDeviceName(value: String): String {
    val withoutControls = buildString(value.length) {
        value.codePoints().forEach { codePoint ->
            if (!Character.isISOControl(codePoint)) appendCodePoint(codePoint)
        }
    }.trim()
    if (withoutControls.codePointCount(0, withoutControls.length) <= MAX_DEVICE_NAME_CODE_POINTS) {
        return withoutControls
    }
    val end = withoutControls.offsetByCodePoints(0, MAX_DEVICE_NAME_CODE_POINTS)
    return withoutControls.substring(0, end)
}
