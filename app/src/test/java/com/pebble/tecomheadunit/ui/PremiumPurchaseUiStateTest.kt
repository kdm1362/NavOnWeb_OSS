/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.pebble.tecomheadunit.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PremiumPurchaseUiStateTest {
    @Test
    fun `free user can retry purchase even before product details are available`() {
        assertTrue(PremiumPurchaseUiState().canLaunchPurchase)
        assertTrue(
            PremiumPurchaseUiState(productAvailable = true).canLaunchPurchase,
        )
    }

    @Test
    fun `entitled or busy state prevents duplicate purchase flow`() {
        assertFalse(
            PremiumPurchaseUiState(
                entitled = true,
                productAvailable = true,
            ).canLaunchPurchase,
        )
        assertFalse(
            PremiumPurchaseUiState(
                productAvailable = true,
                purchaseInProgress = true,
            ).canLaunchPurchase,
        )
        assertFalse(
            PremiumPurchaseUiState(
                productAvailable = true,
                purchaseRefreshInProgress = true,
            ).canLaunchPurchase,
        )
    }

    @Test
    fun `purchase refresh is disabled only while another billing action is active`() {
        assertTrue(PremiumPurchaseUiState().canRefreshPurchases)
        assertFalse(
            PremiumPurchaseUiState(purchaseInProgress = true).canRefreshPurchases,
        )
        assertFalse(
            PremiumPurchaseUiState(purchaseRefreshInProgress = true).canRefreshPurchases,
        )
    }

    @Test
    fun `only delayed and failed confirmation dialogs can be dismissed`() {
        assertFalse(
            PremiumPurchaseUiState(
                confirmationDialog = PremiumPurchaseConfirmationDialog.VERIFYING,
            ).confirmationCanBeDismissed,
        )
        assertTrue(
            PremiumPurchaseUiState(
                confirmationDialog = PremiumPurchaseConfirmationDialog.DELAYED,
            ).confirmationCanBeDismissed,
        )
        assertTrue(
            PremiumPurchaseUiState(
                confirmationDialog = PremiumPurchaseConfirmationDialog.FAILED,
            ).confirmationCanBeDismissed,
        )
    }
}
