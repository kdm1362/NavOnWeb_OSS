/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.eigenkodex.navonweb.openauto.credential

import android.security.keystore.KeyProperties
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidKeyStoreCredentialProviderTest {
    @Test
    fun `api 31 and newer accept only trusted environment or StrongBox`() {
        assertTrue(
            acceptsHardwareSecurity(
                sdkInt = 31,
                securityLevel = KeyProperties.SECURITY_LEVEL_TRUSTED_ENVIRONMENT,
                legacyInsideSecureHardware = false,
            ),
        )
        assertTrue(
            acceptsHardwareSecurity(
                sdkInt = 36,
                securityLevel = KeyProperties.SECURITY_LEVEL_STRONGBOX,
                legacyInsideSecureHardware = false,
            ),
        )
        assertFalse(
            acceptsHardwareSecurity(
                sdkInt = 31,
                securityLevel = KeyProperties.SECURITY_LEVEL_SOFTWARE,
                legacyInsideSecureHardware = true,
            ),
        )
        assertFalse(
            acceptsHardwareSecurity(
                sdkInt = 31,
                securityLevel = KeyProperties.SECURITY_LEVEL_UNKNOWN_SECURE,
                legacyInsideSecureHardware = true,
            ),
        )
        assertFalse(
            acceptsHardwareSecurity(
                sdkInt = 31,
                securityLevel = null,
                legacyInsideSecureHardware = true,
            ),
        )
    }

    @Test
    fun `api 26 through 30 use the legacy secure hardware flag`() {
        assertTrue(
            acceptsHardwareSecurity(
                sdkInt = 30,
                securityLevel = null,
                legacyInsideSecureHardware = true,
            ),
        )
        assertFalse(
            acceptsHardwareSecurity(
                sdkInt = 26,
                securityLevel = KeyProperties.SECURITY_LEVEL_STRONGBOX,
                legacyInsideSecureHardware = false,
            ),
        )
    }
}
