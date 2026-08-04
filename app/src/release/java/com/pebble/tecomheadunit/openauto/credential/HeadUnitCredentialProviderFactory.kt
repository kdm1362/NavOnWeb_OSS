/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.pebble.tecomheadunit.openauto.credential

import android.content.Context
import com.pebble.tecomheadunit.BuildConfig

object HeadUnitCredentialProviderFactory {
    fun create(@Suppress("UNUSED_PARAMETER") context: Context): HeadUnitCredentialProvider =
        AndroidKeyStoreCredentialProvider(
            trustPolicy = PinnedHeadUnitCredentialTrustPolicy.fromSha256PinLists(
                leafPins = BuildConfig.AASDK_IDENTITY_LEAF_SHA256,
                anchorPins = BuildConfig.AASDK_IDENTITY_ANCHOR_SHA256,
            ),
        )
}
