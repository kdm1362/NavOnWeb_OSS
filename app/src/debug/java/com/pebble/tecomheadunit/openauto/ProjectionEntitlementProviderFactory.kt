/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.pebble.tecomheadunit.openauto

import android.content.Context
import com.pebble.tecomheadunit.BuildConfig

/** Debug bench override is explicit; otherwise debug uses the same Play-owned cache as release. */
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
