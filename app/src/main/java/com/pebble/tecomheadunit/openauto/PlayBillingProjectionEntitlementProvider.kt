/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.pebble.tecomheadunit.openauto

import android.content.Context
import com.pebble.tecomheadunit.BuildConfig
import com.pebble.tecomheadunit.billing.PremiumBillingProvider
import com.pebble.tecomheadunit.billing.PremiumEntitlementStore

/**
 * Synchronous projection-start gate backed by a prior successful Play ownership query. Browser
 * input and profile preferences cannot mutate this source.
 */
internal class PlayBillingProjectionEntitlementProvider(context: Context) :
    ServerProjectionEntitlementProvider {
    private val store = PremiumEntitlementStore(context.applicationContext)

    override fun currentGrant(): ServerProjectionEntitlementGrant {
        val digest = store.entitlementEvidenceDigest(BuildConfig.PREMIUM_PRODUCT_ID)
            ?: PremiumBillingProvider.currentPurchaseEvidenceDigest()
            ?: return ServerProjectionEntitlementGrant.Free
        return ServerProjectionEntitlementGrant.clientPlayPurchase(digest)
    }
}
