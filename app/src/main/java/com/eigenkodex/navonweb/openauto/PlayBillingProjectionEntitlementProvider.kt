/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.eigenkodex.navonweb.openauto

import android.content.Context
import com.eigenkodex.navonweb.BuildConfig
import com.eigenkodex.navonweb.billing.PremiumBillingProvider
import com.eigenkodex.navonweb.billing.PremiumEntitlementStore
import com.eigenkodex.navonweb.billing.ReviewPromoSession

/**
 * Synchronous projection-start gate backed by a prior successful Play ownership query or the
 * process-only review promo session. Browser input and profile preferences cannot mutate either
 * source.
 */
internal class PlayBillingProjectionEntitlementProvider(context: Context) :
    ServerProjectionEntitlementProvider {
    private val store = PremiumEntitlementStore(context.applicationContext)

    override fun currentGrant(): ServerProjectionEntitlementGrant {
        if (ReviewPromoSession.isActive) return ServerProjectionEntitlementGrant.ReviewPromo
        val digest = store.entitlementEvidenceDigest(BuildConfig.PREMIUM_PRODUCT_ID)
            ?: PremiumBillingProvider.currentPurchaseEvidenceDigest()
            ?: return ServerProjectionEntitlementGrant.Free
        return ServerProjectionEntitlementGrant.clientPlayPurchase(digest)
    }
}
