/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.pebble.tecomheadunit.billing

enum class PremiumEntitlementStatus {
    FREE,
    PENDING,
    PREMIUM,
}

enum class PremiumBillingConnectionState {
    NOT_STARTED,
    CONNECTING,
    READY,
    UNAVAILABLE,
    CLOSED,
}

enum class PremiumEntitlementSource {
    NONE,
    CACHED_PLAY_PURCHASE,
    LIVE_PLAY_QUERY,
}

/** UI-safe billing state. Purchase tokens, signatures and Play debug messages are never exposed. */
data class PremiumBillingState(
    val productId: String,
    val connection: PremiumBillingConnectionState,
    val entitlement: PremiumEntitlementStatus,
    val entitlementSource: PremiumEntitlementSource,
    val formattedPrice: String? = null,
    val productName: String? = null,
    val lastResponseCode: Int? = null,
) {
    val isPremium: Boolean
        get() = entitlement == PremiumEntitlementStatus.PREMIUM
}

sealed interface PremiumBillingActionResult {
    data object PurchaseFlowStarted : PremiumBillingActionResult
    data object PremiumOwned : PremiumBillingActionResult
    data object PurchasePending : PremiumBillingActionResult
    data object UserCancelled : PremiumBillingActionResult
    data object ProductUnavailable : PremiumBillingActionResult
    data class Failed(val responseCode: Int) : PremiumBillingActionResult
}

sealed interface PremiumPurchaseCheckResult {
    data object PremiumOwned : PremiumPurchaseCheckResult
    data object Free : PremiumPurchaseCheckResult
    data object PurchasePending : PremiumPurchaseCheckResult
    data class Failed(val responseCode: Int) : PremiumPurchaseCheckResult
}
