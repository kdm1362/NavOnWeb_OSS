/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.pebble.tecomheadunit.automation.bluetooth

import android.Manifest
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.pebble.tecomheadunit.R

data class BondedBluetoothDeviceOption(
    /** App-private stable selection key. Do not place this value in logs. */
    val address: String,
    val displayName: String,
    val addressHint: String,
)

sealed interface BondedBluetoothDeviceCatalogResult {
    data class Available(val devices: List<BondedBluetoothDeviceOption>) :
        BondedBluetoothDeviceCatalogResult

    data object PermissionRequired : BondedBluetoothDeviceCatalogResult

    data object BluetoothUnavailable : BondedBluetoothDeviceCatalogResult

    data object BluetoothDisabled : BondedBluetoothDeviceCatalogResult

    data object PlatformFailure : BondedBluetoothDeviceCatalogResult
}

object BluetoothConnectPermission {
    fun runtimePermission(): String? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        Manifest.permission.BLUETOOTH_CONNECT
    } else {
        null
    }

    fun isGranted(context: Context): Boolean {
        val permission = runtimePermission() ?: return true
        return ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    }
}

class AndroidBondedBluetoothDeviceCatalog(context: Context) {
    private val applicationContext = context.applicationContext

    fun load(): BondedBluetoothDeviceCatalogResult {
        if (!BluetoothConnectPermission.isGranted(applicationContext)) {
            return BondedBluetoothDeviceCatalogResult.PermissionRequired
        }
        val adapter = applicationContext.getSystemService(BluetoothManager::class.java)?.adapter
            ?: return BondedBluetoothDeviceCatalogResult.BluetoothUnavailable
        return try {
            if (!adapter.isEnabled) return BondedBluetoothDeviceCatalogResult.BluetoothDisabled
            val devices = adapter.bondedDevices.orEmpty()
                .mapNotNull { device ->
                    val address = normalizeBluetoothAddress(device.address) ?: return@mapNotNull null
                    val name = sanitizeBluetoothDeviceName(device.name.orEmpty())
                        .ifBlank { applicationContext.getString(R.string.bluetooth_unnamed_device) }
                    BondedBluetoothDeviceOption(
                        address = address,
                        displayName = name,
                        addressHint = address.takeLast(8).padStart(17, '•'),
                    )
                }
                .distinctBy(BondedBluetoothDeviceOption::address)
                .sortedWith(
                    compareBy<BondedBluetoothDeviceOption, String>(String.CASE_INSENSITIVE_ORDER) {
                        it.displayName
                    }.thenBy { it.address },
                )
            BondedBluetoothDeviceCatalogResult.Available(devices)
        } catch (_: SecurityException) {
            BondedBluetoothDeviceCatalogResult.PermissionRequired
        } catch (_: RuntimeException) {
            BondedBluetoothDeviceCatalogResult.PlatformFailure
        }
    }
}
