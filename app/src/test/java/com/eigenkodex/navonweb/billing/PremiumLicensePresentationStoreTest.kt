/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.eigenkodex.navonweb.billing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PremiumLicensePresentationStoreTest {
    @Test
    fun `scheduling never consumes evidence before presentation completes`() {
        var persisted: String? = null
        var writes = 0
        val store = PremiumLicensePresentationStore(
            readPresentedDigest = { persisted },
            persistPresentedDigest = { digest ->
                writes += 1
                persisted = digest
                true
            },
        )
        val digest = "ab".repeat(32)

        assertTrue(store.shouldPresent(digest))
        assertTrue(store.shouldPresent(digest))
        assertEquals(0, writes)
        assertTrue(store.recordPresented(digest))
        assertEquals(1, writes)
        assertTrue(store.wasPresented(digest))
        assertFalse(store.shouldPresent(digest.uppercase()))
        assertTrue(store.recordPresented(digest))
        assertEquals(1, writes)
    }

    @Test
    fun `new purchase evidence can receive a new presentation`() {
        var persisted: String? = "AA".repeat(32)
        val store = PremiumLicensePresentationStore(
            readPresentedDigest = { persisted },
            persistPresentedDigest = { digest -> persisted = digest; true },
        )

        assertTrue(store.shouldPresent("BB".repeat(32)))
        assertTrue(store.recordPresented("BB".repeat(32)))
    }

    @Test
    fun `invalid or unpersisted evidence is never consumed`() {
        val store = PremiumLicensePresentationStore(
            readPresentedDigest = { null },
            persistPresentedDigest = { false },
        )

        assertFalse(store.shouldPresent(null))
        assertFalse(store.recordPresented("raw-purchase-token"))
        assertFalse(store.recordPresented("AA".repeat(32)))
        assertTrue(store.shouldPresent("AA".repeat(32)))
    }
}
