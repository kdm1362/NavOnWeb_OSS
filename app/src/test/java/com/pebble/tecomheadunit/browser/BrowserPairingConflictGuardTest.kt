/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.pebble.tecomheadunit.browser

import com.pebble.tecomheadunit.session.PairingCodeLease
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserPairingConflictGuardTest {
    @Test
    fun bootstrapConflictRotatesOnlyTheExactPublishedLease() {
        val current = PairingCodeLease("12345678", 601_000L)

        assertTrue(shouldRotateAfterBootstrapConflict(current, current))
        assertFalse(
            shouldRotateAfterBootstrapConflict(
                current,
                PairingCodeLease("87654321", 602_000L),
            ),
        )
        assertFalse(
            shouldRotateAfterBootstrapConflict(
                current,
                PairingCodeLease("12345678", 602_000L),
            ),
        )
        assertFalse(shouldRotateAfterBootstrapConflict(null, current))
    }
}
