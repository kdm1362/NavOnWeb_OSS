/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.pebble.tecomheadunit.ui

import com.pebble.tecomheadunit.session.BrowserDevicePermission
import com.pebble.tecomheadunit.session.PairedBrowserDevice
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
}
