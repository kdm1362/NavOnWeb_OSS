/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.eigenkodex.navonweb.billing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class PremiumPurchasePolicyTest {
    @Test
    fun purchasedConfiguredProductGrantsPremiumAndRetainsAcknowledgementNeed() {
        val purchase = record(
            state = PremiumPurchaseState.PURCHASED,
            acknowledged = false,
            purchaseTime = 42L,
        )

        val result = PremiumPurchasePolicy.evaluate("navonweb_premium", listOf(purchase))

        assertTrue(result is PremiumPurchaseEvaluation.Purchased)
        assertSame(purchase, (result as PremiumPurchaseEvaluation.Purchased).purchase)
        assertEquals(false, result.purchase.acknowledged)
    }

    @Test
    fun pendingNeverGrantsPremium() {
        assertEquals(
            PremiumPurchaseEvaluation.Pending,
            PremiumPurchasePolicy.evaluate(
                "navonweb_premium",
                listOf(record(state = PremiumPurchaseState.PENDING)),
            ),
        )
    }

    @Test
    fun unrelatedOrMalformedPurchaseFailsClosed() {
        assertEquals(
            PremiumPurchaseEvaluation.Free,
            PremiumPurchasePolicy.evaluate(
                "navonweb_premium",
                listOf(
                    record(productIds = setOf("other_product")),
                    record(purchaseToken = ""),
                    record(state = PremiumPurchaseState.OTHER),
                ),
            ),
        )
        assertEquals(
            PremiumPurchaseEvaluation.Free,
            PremiumPurchasePolicy.evaluate("INVALID-ID", listOf(record())),
        )
    }

    @Test
    fun completedPurchaseWinsOverPendingAndNewestCompletedRecordIsSelected() {
        val old = record(state = PremiumPurchaseState.PURCHASED, purchaseTime = 1L)
        val latest = record(
            state = PremiumPurchaseState.PURCHASED,
            purchaseToken = "latest",
            purchaseTime = 2L,
        )
        val pending = record(state = PremiumPurchaseState.PENDING, purchaseToken = "pending")

        val result = PremiumPurchasePolicy.evaluate(
            "navonweb_premium",
            listOf(old, pending, latest),
        ) as PremiumPurchaseEvaluation.Purchased

        assertSame(latest, result.purchase)
    }

    private fun record(
        productIds: Set<String> = setOf("navonweb_premium"),
        state: PremiumPurchaseState = PremiumPurchaseState.PURCHASED,
        purchaseToken: String = "token",
        acknowledged: Boolean = true,
        purchaseTime: Long = 1L,
    ) = PremiumPurchaseRecord(
        productIds = productIds,
        state = state,
        purchaseToken = purchaseToken,
        acknowledged = acknowledged,
        purchaseTimeEpochMillis = purchaseTime,
    )
}
