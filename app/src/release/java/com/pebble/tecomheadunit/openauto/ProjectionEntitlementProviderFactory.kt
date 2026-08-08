/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.pebble.tecomheadunit.openauto

import android.content.Context

/** Release accepts Play ownership plus the explicitly activated process-only review session. */
internal object ProjectionEntitlementProviderFactory {
    fun create(context: Context): ServerProjectionEntitlementProvider =
        PlayBillingProjectionEntitlementProvider(context)
}
