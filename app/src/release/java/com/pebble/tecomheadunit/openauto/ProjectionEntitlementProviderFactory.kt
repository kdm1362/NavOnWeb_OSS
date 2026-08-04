/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.pebble.tecomheadunit.openauto

import android.content.Context

/** Release has no bench override and reads only the last successful Google Play ownership query. */
internal object ProjectionEntitlementProviderFactory {
    fun create(context: Context): ServerProjectionEntitlementProvider =
        PlayBillingProjectionEntitlementProvider(context)
}
