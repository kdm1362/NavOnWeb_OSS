/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.eigenkodex.navonweb.ui

import com.eigenkodex.navonweb.session.BrowserDevicePermission
import com.eigenkodex.navonweb.session.PairedBrowserDevice
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PairedBrowserDeviceUiPolicyTest {
    @Test
    fun onlyPremiumControlDeviceCanBeExplicitlySelectedAsMain() {
        val control = device(BrowserDevicePermission.CONTROL)
        val readOnly = device(BrowserDevicePermission.READ_ONLY)

        assertTrue(canSelectPairedBrowserAsMain(control, premiumEntitled = true))
        assertFalse(canSelectPairedBrowserAsMain(control, premiumEntitled = false))
        assertFalse(canSelectPairedBrowserAsMain(readOnly, premiumEntitled = true))
        assertFalse(
            canSelectPairedBrowserAsMain(
                control,
                premiumEntitled = true,
                managementReady = false,
            ),
        )
    }

    @Test
    fun activityDoesNotQueueSecurityMutationsWhileServiceIsUnavailable() {
        val activitySource = sourceFile("MainActivity.kt").readText(Charsets.UTF_8)
        val uiSource = sourceFile("ui/NavOnWebApp.kt").readText(Charsets.UTF_8)

        assertFalse(activitySource.contains("pendingPairedBrowserMutations"))
        assertFalse(activitySource.contains("PendingPairedBrowserMutation"))
        assertFalse(activitySource.contains("browserTrustStore.updateAccess("))
        assertFalse(activitySource.contains("browserTrustStore.setPreferredMain("))
        assertFalse(activitySource.contains("browserTrustStore.remove("))
        assertFalse(activitySource.contains("browserTrustStore.rename"))
        assertTrue(activitySource.contains("pairedBrowserManagementReady = bound"))
        assertTrue(activitySource.contains("binder.renamePairedBrowserDevice(deviceId, displayName)"))
        assertTrue(uiSource.contains("if (!managementReady)"))
        assertTrue(uiSource.contains("enabled = managementReady"))
    }

    @Test
    fun pairedDevicesUseSavedChildDepthWithBackAndOverviewCounts() {
        val uiSource = sourceFile("ui/NavOnWebApp.kt").readText(Charsets.UTF_8)

        assertTrue(uiSource.contains("SettingsSubpage.PAIRED_BROWSER_DEVICES"))
        assertTrue(uiSource.contains("var settingsSubpageName by rememberSaveable"))
        assertTrue(uiSource.contains("BackHandler("))
        assertTrue(uiSource.contains("onBack = returnToSettingsRoot"))
        assertTrue(uiSource.contains("onDismissRequest = {"))
        assertTrue(uiSource.contains("returnToSettingsRoot()"))
        assertTrue(uiSource.contains("R.string.ui_paired_browser_overview_counts"))
        assertTrue(uiSource.contains("role = Role.Button"))
    }

    @Test
    fun renameValidationNormalizesWhitespaceAndCountsUnicodeCodePoints() {
        val valid = validatePairedBrowserDisplayName("  Living   room tablet  ")
        assertTrue(valid.isValid)
        assertEquals("Living room tablet", valid.normalizedName)
        assertNull(valid.failure)

        assertEquals(
            PairedBrowserDisplayNameFailure.EMPTY,
            validatePairedBrowserDisplayName("   ").failure,
        )
        assertEquals(
            PairedBrowserDisplayNameFailure.CONTROL_CHARACTER,
            validatePairedBrowserDisplayName("Living\nroom").failure,
        )
        assertTrue(validatePairedBrowserDisplayName("😀".repeat(64)).isValid)
        assertEquals(
            PairedBrowserDisplayNameFailure.TOO_MANY_CODE_POINTS,
            validatePairedBrowserDisplayName("😀".repeat(65)).failure,
        )
    }

    @Test
    fun renameUiDisablesUnchangedInvalidAndUnboundSubmissions() {
        val uiSource = sourceFile("ui/NavOnWebApp.kt").readText(Charsets.UTF_8)

        assertTrue(uiSource.contains("validation.normalizedName != originalName"))
        assertTrue(uiSource.contains("val canRename = managementReady"))
        assertTrue(uiSource.contains("enabled = canRename"))
        assertTrue(uiSource.contains("isError = validationMessage != null"))
        assertTrue(uiSource.contains("keyboardActions = KeyboardActions(onDone"))
        assertTrue(uiSource.contains("key(device.deviceId)"))
        assertTrue(uiSource.contains("contentDescription = colorDescription"))
        assertTrue(uiSource.contains("contentDescription = renameDescription"))
        assertTrue(uiSource.contains("contentDescription = deleteDescription"))
        assertTrue(uiSource.contains("contentDescription = mainDescription"))
        assertTrue(uiSource.contains("contentDescription = readOnlyDescription"))
        assertTrue(uiSource.contains(".selectable("))
        assertTrue(uiSource.contains("role = Role.RadioButton"))
        assertTrue(uiSource.contains(".toggleable("))
        assertTrue(uiSource.contains("role = Role.Switch"))
        assertTrue(uiSource.contains("onClick = null"))
        assertTrue(uiSource.contains("onCheckedChange = null"))
    }

    @Test
    fun publicUiIdIsShortAndDoesNotExposeCredentialMaterial() {
        assertEquals("ABC123", pairedBrowserPublicId("browser_0123456789ABC123"))
        assertEquals("ABC123", pairedBrowserPublicId("0123456789ABC123"))
    }

    private fun device(permission: BrowserDevicePermission) = PairedBrowserDevice(
        deviceId = "browser_0123456789ABCDEF",
        displayName = "Browser",
        permission = permission,
        preferredMain = false,
        colorSlot = 0,
        createdAtEpochMillis = 1L,
        lastSeenAtEpochMillis = 1L,
    )

    private fun sourceFile(relativePath: String): File = listOf(
        File("app/src/main/java/com/eigenkodex/navonweb/$relativePath"),
        File("src/main/java/com/eigenkodex/navonweb/$relativePath"),
    ).first(File::isFile)
}
