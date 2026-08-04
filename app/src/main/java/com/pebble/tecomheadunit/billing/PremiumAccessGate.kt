/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.pebble.tecomheadunit.billing

import android.content.Context
import com.pebble.tecomheadunit.BuildConfig

/**
 * Synchronous gate for code paths that cannot wait for BillingClient, such as system event
 * receivers. The debug override exists only in debug builds; release accepts only an ownership
 * result previously returned by Google Play and stored in app-private preferences.
 */
internal object PremiumAccessGate {
    fun isPremium(context: Context): Boolean = resolvePremiumAccess(
        debugBenchOverride = BuildConfig.DEBUG && BuildConfig.ENABLE_PREMIUM_PROJECTION_BENCH,
        cachedPlayOwnership = PremiumEntitlementStore(context.applicationContext)
            .isEntitled(BuildConfig.PREMIUM_PRODUCT_ID),
        livePlayOwnership = PremiumBillingProvider.isPremiumInMemory(),
    )
}

internal fun resolvePremiumAccess(
    debugBenchOverride: Boolean,
    cachedPlayOwnership: Boolean,
    livePlayOwnership: Boolean,
): Boolean = debugBenchOverride || cachedPlayOwnership || livePlayOwnership
