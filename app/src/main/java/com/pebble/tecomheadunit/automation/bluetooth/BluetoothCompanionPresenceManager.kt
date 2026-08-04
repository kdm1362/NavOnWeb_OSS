/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.pebble.tecomheadunit.automation.bluetooth

import android.companion.CompanionDeviceManager
import android.companion.DeviceNotAssociatedException
import android.companion.ObservingDevicePresenceRequest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.annotation.RequiresApi

enum class CompanionPresenceObservationFailure {
    NO_SELECTED_DEVICE,
    ASSOCIATION_REQUIRED,
    FEATURE_UNAVAILABLE,
    PLATFORM_UNSUPPORTED,
    SECURITY_RESTRICTION,
    PLATFORM_REJECTED,
}

sealed interface CompanionPresenceObservationResult {
    data object Started : CompanionPresenceObservationResult

    data object Stopped : CompanionPresenceObservationResult

    data class Failed(val reason: CompanionPresenceObservationFailure) :
        CompanionPresenceObservationResult
}

/** Event-driven presence registration. This component never polls connection state. */
class BluetoothCompanionPresenceManager(context: Context) {
    private val applicationContext = context.applicationContext
    private val store = BluetoothAutomationStore(applicationContext)

    fun startObserving(): CompanionPresenceObservationResult = changeObservation(start = true)

    fun stopObserving(): CompanionPresenceObservationResult = changeObservation(start = false)

    fun stopObserving(selectedDevice: SelectedBluetoothDevice): CompanionPresenceObservationResult =
        changeObservation(start = false, selectedOverride = selectedDevice)

    private fun changeObservation(
        start: Boolean,
        selectedOverride: SelectedBluetoothDevice? = null,
    ): CompanionPresenceObservationResult {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            return CompanionPresenceObservationResult.Failed(
                CompanionPresenceObservationFailure.PLATFORM_UNSUPPORTED,
            )
        }
        val selected = selectedOverride ?: store.load().selectedDevice
            ?: return CompanionPresenceObservationResult.Failed(
                CompanionPresenceObservationFailure.NO_SELECTED_DEVICE,
            )
        if (!applicationContext.packageManager.hasSystemFeature(
                PackageManager.FEATURE_COMPANION_DEVICE_SETUP,
            )
        ) {
            return CompanionPresenceObservationResult.Failed(
                CompanionPresenceObservationFailure.FEATURE_UNAVAILABLE,
            )
        }
        val manager = applicationContext.getSystemService(CompanionDeviceManager::class.java)
            ?: return CompanionPresenceObservationResult.Failed(
                CompanionPresenceObservationFailure.FEATURE_UNAVAILABLE,
            )

        return try {
            if (Build.VERSION.SDK_INT >= 36) {
                val associationId = resolveApi36AssociationId(manager, selected)
                    ?: return CompanionPresenceObservationResult.Failed(
                        CompanionPresenceObservationFailure.ASSOCIATION_REQUIRED,
                    )
                Api36PresenceRegistration.change(manager, associationId, start)
            } else {
                @Suppress("DEPRECATION")
                if (start) {
                    manager.startObservingDevicePresence(selected.address)
                } else {
                    manager.stopObservingDevicePresence(selected.address)
                }
            }
            if (start) {
                CompanionPresenceObservationResult.Started
            } else {
                CompanionPresenceObservationResult.Stopped
            }
        } catch (_: DeviceNotAssociatedException) {
            CompanionPresenceObservationResult.Failed(
                CompanionPresenceObservationFailure.ASSOCIATION_REQUIRED,
            )
        } catch (_: SecurityException) {
            CompanionPresenceObservationResult.Failed(
                CompanionPresenceObservationFailure.SECURITY_RESTRICTION,
            )
        } catch (_: RuntimeException) {
            CompanionPresenceObservationResult.Failed(
                CompanionPresenceObservationFailure.PLATFORM_REJECTED,
            )
        }
    }

    @RequiresApi(36)
    private fun resolveApi36AssociationId(
        manager: CompanionDeviceManager,
        selected: SelectedBluetoothDevice,
    ): Int? {
        val associations = manager.myAssociations
        val storedId = selected.companionAssociationId
        val matched = associations.firstOrNull { association ->
            val address = normalizeBluetoothAddress(
                association.deviceMacAddress?.toString().orEmpty(),
            )
            address == selected.address && (storedId == null || association.id == storedId)
        } ?: if (storedId != null) {
            // Recover if an app restore retained an obsolete ID but the system kept the address.
            associations.firstOrNull { association ->
                normalizeBluetoothAddress(
                    association.deviceMacAddress?.toString().orEmpty(),
                ) == selected.address
            }
        } else {
            null
        }
        val associationId = matched?.id ?: return null
        if (storedId != associationId && !store.attachCompanionAssociation(selected.address, associationId)) {
            return null
        }
        return associationId
    }
}

private object Api36PresenceRegistration {
    @RequiresApi(36)
    fun change(
        manager: CompanionDeviceManager,
        associationId: Int,
        start: Boolean,
    ) {
        val request = ObservingDevicePresenceRequest.Builder()
            .setAssociationId(associationId)
            .build()
        if (start) {
            manager.startObservingDevicePresence(request)
        } else {
            manager.stopObservingDevicePresence(request)
        }
    }
}
