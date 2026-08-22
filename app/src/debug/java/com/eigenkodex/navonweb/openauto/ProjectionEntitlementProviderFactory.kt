/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.eigenkodex.navonweb.openauto

import android.content.Context

/** Debug resolves entitlement exactly like release: verified Play ownership or a review session. */
internal object ProjectionEntitlementProviderFactory {
    fun create(context: Context): ServerProjectionEntitlementProvider =
        PlayBillingProjectionEntitlementProvider(context)
}
