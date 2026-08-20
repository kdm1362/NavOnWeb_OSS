/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.eigenkodex.navonweb.billing

import android.content.Context
import com.eigenkodex.navonweb.BuildConfig

/**
 * Synchronous gate for code paths that cannot wait for BillingClient, such as system event
 * receivers. The debug override exists only in debug builds. Release accepts either an ownership
 * result returned by Google Play or the explicitly activated, process-only review promo session.
 */
internal object PremiumAccessGate {
    fun isPremium(context: Context): Boolean = resolvePremiumAccess(
        debugBenchOverride = BuildConfig.DEBUG && BuildConfig.ENABLE_PREMIUM_PROJECTION_BENCH,
        cachedPlayOwnership = PremiumEntitlementStore(context.applicationContext)
            .isEntitled(BuildConfig.PREMIUM_PRODUCT_ID),
        livePlayOwnership = PremiumBillingProvider.isPremiumInMemory(),
        reviewPromoAccess = ReviewPromoSession.isActive,
    )
}

internal fun resolvePremiumAccess(
    debugBenchOverride: Boolean,
    cachedPlayOwnership: Boolean,
    livePlayOwnership: Boolean,
    reviewPromoAccess: Boolean,
): Boolean =
    debugBenchOverride || cachedPlayOwnership || livePlayOwnership || reviewPromoAccess
