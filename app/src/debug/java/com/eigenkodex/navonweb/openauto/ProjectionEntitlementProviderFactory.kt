/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.eigenkodex.navonweb.openauto

import android.content.Context
import com.eigenkodex.navonweb.BuildConfig

/** Debug bench override is explicit; otherwise debug uses the release entitlement sources. */
internal object ProjectionEntitlementProviderFactory {
    fun create(context: Context): ServerProjectionEntitlementProvider =
        if (BuildConfig.DEBUG && BuildConfig.ENABLE_PREMIUM_PROJECTION_BENCH) {
            ServerProjectionEntitlementProvider {
                ServerProjectionEntitlementGrant.serverVerifiedPremium(
                    "debug-build-enablePremiumProjectionBench",
                )
            }
        } else {
            PlayBillingProjectionEntitlementProvider(context)
        }
}
