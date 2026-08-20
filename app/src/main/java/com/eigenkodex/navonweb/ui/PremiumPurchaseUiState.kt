/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.eigenkodex.navonweb.ui

enum class PremiumPurchaseConfirmationDialog {
    HIDDEN,
    VERIFYING,
    DELAYED,
    FAILED,
}

/**
 * Billing-library-independent purchase state consumed by Compose.
 *
 * Purchase tokens, receipts and account identifiers must never enter this UI model. The Android
 * billing boundary owns those values and exposes only the display-safe result below.
 */
data class PremiumPurchaseUiState(
    val entitled: Boolean = false,
    val productAvailable: Boolean = false,
    val formattedPrice: String? = null,
    val purchaseInProgress: Boolean = false,
    val purchaseRefreshInProgress: Boolean = false,
    val statusMessage: String = "",
    val confirmationDialog: PremiumPurchaseConfirmationDialog =
        PremiumPurchaseConfirmationDialog.HIDDEN,
    /** One-shot event set only after a new Play purchase-evidence digest is persisted as seen. */
    val licenseConfirmationPending: Boolean = false,
) {
    val busy: Boolean
        get() = purchaseInProgress || purchaseRefreshInProgress

    val canLaunchPurchase: Boolean
        // A tap is also a useful retry when Play has not returned product details yet. The billing
        // boundary reconnects and queries the product before attempting to open the purchase UI.
        get() = !entitled && !busy

    val canRefreshPurchases: Boolean
        get() = !busy

    val confirmationCanBeDismissed: Boolean
        get() = confirmationDialog == PremiumPurchaseConfirmationDialog.DELAYED ||
            confirmationDialog == PremiumPurchaseConfirmationDialog.FAILED
}
