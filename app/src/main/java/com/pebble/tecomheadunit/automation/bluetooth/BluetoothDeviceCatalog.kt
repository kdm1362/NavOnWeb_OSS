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

internal data class BondedBluetoothDeviceRecord(
    val address: String?,
    val displayName: String?,
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
            // bondedDevices is the user's complete Android pairing list; it is deliberately not
            // reduced to devices that happen to have an active ACL connection right now.
            val devices = buildBondedBluetoothDeviceOptions(
                records = adapter.bondedDevices.orEmpty().map { device ->
                    BondedBluetoothDeviceRecord(
                        address = device.address,
                        displayName = device.name,
                    )
                },
                unnamedDeviceName = applicationContext.getString(R.string.bluetooth_unnamed_device),
            )
            BondedBluetoothDeviceCatalogResult.Available(devices)
        } catch (_: SecurityException) {
            BondedBluetoothDeviceCatalogResult.PermissionRequired
        } catch (_: RuntimeException) {
            BondedBluetoothDeviceCatalogResult.PlatformFailure
        }
    }
}

internal fun buildBondedBluetoothDeviceOptions(
    records: Iterable<BondedBluetoothDeviceRecord>,
    unnamedDeviceName: String,
): List<BondedBluetoothDeviceOption> = records
    .mapNotNull { record ->
        val address = record.address?.let(::normalizeBluetoothAddress) ?: return@mapNotNull null
        val name = sanitizeBluetoothDeviceName(record.displayName.orEmpty())
            .ifBlank { unnamedDeviceName }
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
