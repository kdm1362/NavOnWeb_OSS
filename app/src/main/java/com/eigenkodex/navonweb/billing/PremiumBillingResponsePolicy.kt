/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.eigenkodex.navonweb.billing

import com.android.billingclient.api.BillingClient

/** Separates terminal purchase failures from conditions that can recover on a later Play query. */
internal object PremiumBillingResponsePolicy {
    fun isConfirmedPurchaseFailure(responseCode: Int): Boolean = when (responseCode) {
        BillingClient.BillingResponseCode.FEATURE_NOT_SUPPORTED,
        BillingClient.BillingResponseCode.ITEM_UNAVAILABLE,
        BillingClient.BillingResponseCode.DEVELOPER_ERROR,
        BillingClient.BillingResponseCode.ITEM_NOT_OWNED,
        -> true

        // Connectivity, Play service and unknown failures are not evidence that payment failed.
        // Keep checking until Play returns ownership or the user closes the delayed dialog.
        else -> false
    }
}
