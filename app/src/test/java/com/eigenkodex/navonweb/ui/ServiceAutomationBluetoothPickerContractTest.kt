/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.eigenkodex.navonweb.ui

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ServiceAutomationBluetoothPickerContractTest {
    @Test
    fun `selected Bluetooth device row always opens the paired-device picker for premium`() {
        val ui = readSource("main/java/com/eigenkodex/navonweb/ui/NavOnWebApp.kt")
        val card = ui.between(
            "private fun ServiceAutomationCard(",
            "private fun PremiumPurchaseCard(",
        )

        assertTrue(card.contains("enabled = premiumEntitled,"))
        assertTrue(card.contains("onClick = onSelectBluetoothDevice,"))
        assertFalse(card.contains("enabled = premiumEntitled && state.selectedBluetoothDeviceName != null"))
    }

    @Test
    fun `picker lists paired devices marks selection and supports clearing`() {
        val ui = readSource("main/java/com/eigenkodex/navonweb/ui/NavOnWebApp.kt")
        val picker = ui.between(
            "private fun BluetoothDevicePickerDialog(",
            "private fun DiagnosticLogCard(",
        )
        val catalog = readSource(
            "main/java/com/eigenkodex/navonweb/automation/bluetooth/BluetoothDeviceCatalog.kt",
        )

        assertTrue(catalog.contains("adapter.bondedDevices.orEmpty()"))
        assertTrue(picker.contains("devices.forEach { device ->"))
        assertTrue(picker.contains("selected = device.id == selectedDeviceId"))
        assertTrue(picker.contains("onSelected(device.id)"))
        assertTrue(picker.contains("onClearSelection"))
        assertTrue(picker.contains("service_automation_no_bonded_devices"))
    }

    @Test
    fun `clearing an active Bluetooth selection disables its automation before deleting it`() {
        val activity = readSource("main/java/com/eigenkodex/navonweb/MainActivity.kt")
        val clear = activity.between(
            "private fun clearBluetoothDeviceSelection()",
            "private fun requestBluetoothCompanionAssociation()",
        )

        val disableMode = clear.indexOf("selectMode(AutomationTriggerMode.NONE)")
        val clearSelection = clear.indexOf("bluetoothAutomationStore.clearSelection()")
        val stopObserving = clear.indexOf("bluetoothCompanionPresenceManager.stopObserving(selected)")
        assertTrue(disableMode >= 0)
        assertTrue(clearSelection > disableMode)
        assertTrue(stopObserving > clearSelection)
    }

    private fun String.between(startMarker: String, endMarker: String): String {
        val start = indexOf(startMarker)
        val end = indexOf(endMarker, start + startMarker.length)
        require(start >= 0 && end > start) { "missing source markers: $startMarker .. $endMarker" }
        return substring(start, end)
    }

    private fun readSource(relativePath: String): String {
        val candidates = listOf(
            File("app/src/$relativePath"),
            File("src/$relativePath"),
        )
        val source = candidates.firstOrNull(File::isFile)
            ?: error("missing source $relativePath")
        return source.readText(Charsets.UTF_8)
    }
}
