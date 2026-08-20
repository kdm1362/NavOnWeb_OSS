/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.eigenkodex.navonweb.billing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PremiumEntitlementStoreTest {
    @Test
    fun ownedPurchasePersistsOnlyConfiguredProductDigestAndTimestamp() {
        var persisted: CachedPremiumEntitlement? = null
        val store = PremiumEntitlementStore(
            read = { persisted },
            persist = { next -> persisted = next; true },
        )

        val result = store.recordOwned(
            productId = "navonweb_premium",
            purchaseToken = "raw-play-purchase-token",
            observedAtEpochMillis = 123_456L,
        )

        assertEquals(result, persisted)
        assertEquals("navonweb_premium", persisted?.productId)
        assertEquals(64, persisted?.purchaseTokenSha256?.length)
        assertNotEquals("raw-play-purchase-token", persisted?.purchaseTokenSha256)
        assertEquals(123_456L, persisted?.observedAtEpochMillis)
        assertTrue(store.isEntitled("navonweb_premium"))
        assertFalse(store.isEntitled("different_product"))
    }

    @Test
    fun malformedOrMismatchedCacheFailsClosed() {
        var persisted = CachedPremiumEntitlement(
            productId = "navonweb_premium",
            purchaseTokenSha256 = "NOT-A-DIGEST",
            observedAtEpochMillis = 123L,
        )
        val store = PremiumEntitlementStore(
            read = { persisted },
            persist = { next ->
                persisted = next ?: CachedPremiumEntitlement("invalid", "", 0L)
                true
            },
        )

        assertNull(store.load("navonweb_premium"))
        assertFalse(store.isEntitled("navonweb_premium"))
        assertNull(store.entitlementEvidenceDigest("navonweb_premium"))
    }

    @Test
    fun failedPersistenceNeverCreatesRestartEntitlement() {
        val store = PremiumEntitlementStore(
            read = { null },
            persist = { false },
        )

        assertNull(
            store.recordOwned(
                productId = "navonweb_premium",
                purchaseToken = "token",
                observedAtEpochMillis = 1L,
            ),
        )
        assertFalse(store.isEntitled("navonweb_premium"))
    }

    @Test
    fun successfulMissingOwnershipCanClearPriorGrant() {
        var persisted: CachedPremiumEntitlement? = null
        val store = PremiumEntitlementStore(
            read = { persisted },
            persist = { next -> persisted = next; true },
        )
        store.recordOwned("navonweb_premium", "token", 1L)

        assertTrue(store.clear())
        assertNull(persisted)
        assertFalse(store.isEntitled("navonweb_premium"))
    }

    @Test
    fun invalidProductOrBlankTokenIsRejectedWithoutWrite() {
        var writes = 0
        val store = PremiumEntitlementStore(
            read = { null },
            persist = { writes += 1; true },
        )

        assertNull(store.recordOwned("INVALID-ID", "token", 1L))
        assertNull(store.recordOwned("navonweb_premium", "", 1L))
        assertNull(store.recordOwned("navonweb_premium", "token", 0L))
        assertEquals(0, writes)
    }
}
