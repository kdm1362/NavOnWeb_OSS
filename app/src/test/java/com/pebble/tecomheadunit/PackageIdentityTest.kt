/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.pebble.tecomheadunit

import org.junit.Assert.assertEquals
import org.junit.Test

class PackageIdentityTest {
    @Test
    fun androidApplicationIdUsesEigenkodexNamespace() {
        assertEquals("com.eigenkodex.navonweb", BuildConfig.APPLICATION_ID)
    }
}
