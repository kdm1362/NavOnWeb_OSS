/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.eigenkodex.navonweb.automation

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HotspotAutomationNotificationActionContractTest {
    @Test
    fun `notification keeps settings tap and exposes immutable service disable action`() {
        val source = readSource(
            "main/java/com/eigenkodex/navonweb/automation/HotspotAutomationMonitorService.kt",
        )

        assertTrue(source.contains("PendingIntent.getActivity("))
        assertTrue(source.contains(".setAction(ACTION_OPEN_AUTOMATION_SETTINGS)"))
        assertTrue(source.contains(".setContentIntent(openSettings)"))
        assertTrue(source.contains("PendingIntent.getService("))
        assertTrue(source.contains(".setAction(ACTION_DISABLE_HOTSPOT_AUTOMATION)"))
        assertTrue(source.contains("PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE"))
        assertTrue(source.contains("R.string.hotspot_automation_disable_action"))
    }

    @Test
    fun `disable action persists none and conditionally stops only the owned session`() {
        val monitor = readSource(
            "main/java/com/eigenkodex/navonweb/automation/HotspotAutomationMonitorService.kt",
        )
        val projection = readSource(
            "main/java/com/eigenkodex/navonweb/service/ProjectionService.kt",
        )

        assertTrue(monitor.contains("disableModeIfSelected(AutomationTriggerMode.HOTSPOT)"))
        assertTrue(monitor.contains("ProjectionService.stopIfStartedByAutomation("))
        assertFalse(monitor.contains("onTriggerStateChanged(\n                source = AutomationTriggerMode.HOTSPOT"))
        assertTrue(projection.contains("ProjectionAutomationSessionOwnership.clearForManualStart()"))
        assertTrue(projection.contains("ProjectionAutomationSessionOwnership.matches(owner)"))
        assertTrue(projection.contains("isAutomationProjectionStartAuthorized("))
    }

    @Test
    fun `monitor service remains private to this application`() {
        val manifest = readSource("main/AndroidManifest.xml")
        val serviceStart = manifest.indexOf("android:name=\".automation.HotspotAutomationMonitorService\"")
        assertTrue(serviceStart >= 0)
        val serviceEnd = manifest.indexOf("</service>", serviceStart)
        assertTrue(serviceEnd > serviceStart)
        val serviceDeclaration = manifest.substring(serviceStart, serviceEnd)

        assertTrue(serviceDeclaration.contains("android:exported=\"false\""))
    }

    private fun readSource(relativePath: String): String {
        val candidates = listOf(
            File("app/src/$relativePath"),
            File("src/$relativePath"),
        )
        return candidates.firstOrNull(File::isFile)?.readText(Charsets.UTF_8)
            ?: error("source file not found: $relativePath")
    }
}
