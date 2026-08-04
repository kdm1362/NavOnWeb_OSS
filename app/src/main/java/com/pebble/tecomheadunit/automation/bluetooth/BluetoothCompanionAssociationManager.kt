/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.pebble.tecomheadunit.automation.bluetooth

import android.companion.AssociationInfo
import android.companion.AssociationRequest
import android.companion.BluetoothDeviceFilter
import android.companion.CompanionDeviceManager
import android.content.Context
import android.content.IntentSender
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.annotation.RequiresApi
import java.util.concurrent.Executor

enum class CompanionAssociationFailure {
    NO_SELECTED_DEVICE,
    FEATURE_UNAVAILABLE,
    ASSOCIATION_MISMATCH,
    PERSISTENCE_FAILED,
    SECURITY_RESTRICTION,
    PLATFORM_REJECTED,
}

sealed interface CompanionAssociationEvent {
    /** The caller must launch this sender from a user-visible activity. */
    data class ConfirmationRequired(val intentSender: IntentSender) : CompanionAssociationEvent

    /** Available after system confirmation on Android 13 and later. */
    data class Created(val associationInfo: AssociationInfo) : CompanionAssociationEvent

    data class Failed(val reason: CompanionAssociationFailure) : CompanionAssociationEvent
}

fun interface CompanionAssociationEventCallback {
    fun onEvent(event: CompanionAssociationEvent)
}

sealed interface CompanionAssociationSubmissionResult {
    data object Submitted : CompanionAssociationSubmissionResult

    data class Rejected(val reason: CompanionAssociationFailure) :
        CompanionAssociationSubmissionResult
}

/**
 * Requests a system-confirmed association for the app's currently selected bonded Classic
 * Bluetooth device. The exact-address filter keeps the confirmation scoped to that device.
 */
class BluetoothCompanionAssociationManager(context: Context) {
    private val applicationContext = context.applicationContext
    private val store = BluetoothAutomationStore(applicationContext)

    fun associateSelectedDevice(
        callbackExecutor: Executor,
        callback: CompanionAssociationEventCallback,
    ): CompanionAssociationSubmissionResult {
        val selected = store.load().selectedDevice ?: return CompanionAssociationSubmissionResult.Rejected(
            CompanionAssociationFailure.NO_SELECTED_DEVICE,
        )
        if (!applicationContext.packageManager.hasSystemFeature(
                PackageManager.FEATURE_COMPANION_DEVICE_SETUP,
            )
        ) {
            return CompanionAssociationSubmissionResult.Rejected(
                CompanionAssociationFailure.FEATURE_UNAVAILABLE,
            )
        }
        val manager = applicationContext.getSystemService(CompanionDeviceManager::class.java)
            ?: return CompanionAssociationSubmissionResult.Rejected(
                CompanionAssociationFailure.FEATURE_UNAVAILABLE,
            )
        val request = buildAssociationRequest(selected.address)
        @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
        val platformCallback = object : CompanionDeviceManager.Callback() {
            override fun onAssociationPending(intentSender: IntentSender) {
                callback.onEvent(CompanionAssociationEvent.ConfirmationRequired(intentSender))
            }

            override fun onDeviceFound(chooserLauncher: IntentSender) {
                callback.onEvent(CompanionAssociationEvent.ConfirmationRequired(chooserLauncher))
            }

            @RequiresApi(Build.VERSION_CODES.TIRAMISU)
            override fun onAssociationCreated(associationInfo: AssociationInfo) {
                val associatedAddress = normalizeBluetoothAddress(
                    associationInfo.deviceMacAddress?.toString().orEmpty(),
                )
                if (associatedAddress != selected.address) {
                    callback.onEvent(
                        CompanionAssociationEvent.Failed(
                            CompanionAssociationFailure.ASSOCIATION_MISMATCH,
                        ),
                    )
                    return
                }
                if (!store.attachCompanionAssociation(selected.address, associationInfo.id)) {
                    callback.onEvent(
                        CompanionAssociationEvent.Failed(
                            CompanionAssociationFailure.PERSISTENCE_FAILED,
                        ),
                    )
                    return
                }
                callback.onEvent(CompanionAssociationEvent.Created(associationInfo))
            }

            override fun onFailure(error: CharSequence?) {
                // Do not propagate system text because OEM messages may contain device identifiers.
                callback.onEvent(
                    CompanionAssociationEvent.Failed(
                        CompanionAssociationFailure.PLATFORM_REJECTED,
                    ),
                )
            }

            override fun onFailure(errorCode: Int, error: CharSequence?) {
                onFailure(error)
            }
        }

        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                manager.associate(request, callbackExecutor, platformCallback)
            } else {
                @Suppress("DEPRECATION")
                manager.associate(
                    request,
                    platformCallback,
                    Handler(Looper.getMainLooper()),
                )
            }
            CompanionAssociationSubmissionResult.Submitted
        } catch (_: SecurityException) {
            CompanionAssociationSubmissionResult.Rejected(
                CompanionAssociationFailure.SECURITY_RESTRICTION,
            )
        } catch (_: RuntimeException) {
            CompanionAssociationSubmissionResult.Rejected(
                CompanionAssociationFailure.PLATFORM_REJECTED,
            )
        }
    }

    private fun buildAssociationRequest(address: String): AssociationRequest {
        val deviceFilter = BluetoothDeviceFilter.Builder()
            .setAddress(address)
            .build()
        return AssociationRequest.Builder()
            .addDeviceFilter(deviceFilter)
            .setSingleDevice(true)
            .build()
    }
}
