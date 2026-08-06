/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.pebble.tecomheadunit.openauto.credential

import android.content.Context

object HeadUnitCredentialProviderFactory {
    fun create(@Suppress("UNUSED_PARAMETER") context: Context): HeadUnitCredentialProvider =
        AndroidKeyStoreCredentialProvider(
            requireHardwareBacked = false,
            provisionIfMissing = true,
            trustPolicy = HeadUnitCredentialTrustPolicy {
                HeadUnitCredentialTrustDecision.TRUSTED
            },
        )
}
