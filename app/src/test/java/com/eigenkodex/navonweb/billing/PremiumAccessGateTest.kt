/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.eigenkodex.navonweb.billing

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PremiumAccessGateTest {
    @Test
    fun `free install cannot pass synchronous premium gate`() {
        assertFalse(
            resolvePremiumAccess(
                debugBenchOverride = false,
                cachedPlayOwnership = false,
                livePlayOwnership = false,
                reviewPromoAccess = false,
            ),
        )
    }

    @Test
    fun `Play ownership or explicit debug bench override grants access`() {
        assertTrue(
            resolvePremiumAccess(
                debugBenchOverride = false,
                cachedPlayOwnership = true,
                livePlayOwnership = false,
                reviewPromoAccess = false,
            ),
        )
        assertTrue(
            resolvePremiumAccess(
                debugBenchOverride = true,
                cachedPlayOwnership = false,
                livePlayOwnership = false,
                reviewPromoAccess = false,
            ),
        )
        assertTrue(
            resolvePremiumAccess(
                debugBenchOverride = false,
                cachedPlayOwnership = false,
                livePlayOwnership = true,
                reviewPromoAccess = false,
            ),
        )
        assertTrue(
            resolvePremiumAccess(
                debugBenchOverride = false,
                cachedPlayOwnership = false,
                livePlayOwnership = false,
                reviewPromoAccess = true,
            ),
        )
    }
}
