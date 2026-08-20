/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.eigenkodex.navonweb.openauto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayBillingEntitlementGrantTest {
    @Test
    fun cachedPlayOwnershipIsPremiumButHasDistinctProvenanceType() {
        val grant = ServerProjectionEntitlementGrant.clientPlayPurchase("A".repeat(64))

        assertEquals(ProjectionAccessTier.PREMIUM, grant.tier)
        assertTrue(grant is ServerProjectionEntitlementGrant.ClientPlayPurchase)
    }

    @Test
    fun malformedCachedPlayEvidenceFailsClosed() {
        assertThrows(IllegalArgumentException::class.java) {
            ServerProjectionEntitlementGrant.clientPlayPurchase("not-a-token-digest")
        }
    }

    @Test
    fun reviewPromoIsPremiumButHasDistinctNonPurchaseProvenance() {
        val grant: ServerProjectionEntitlementGrant = ServerProjectionEntitlementGrant.ReviewPromo

        assertEquals(ProjectionAccessTier.PREMIUM, grant.tier)
        assertTrue(grant is ServerProjectionEntitlementGrant.ReviewPromo)
    }
}
