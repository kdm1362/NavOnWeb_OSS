/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.pebble.tecomheadunit.openauto.credential

import android.content.Context

object HeadUnitCredentialProviderFactory {
    fun create(context: Context): HeadUnitCredentialProvider {
        val keyStoreProvider = AndroidKeyStoreCredentialProvider(
            requireHardwareBacked = false,
            trustPolicy = HeadUnitCredentialTrustPolicy {
                HeadUnitCredentialTrustDecision.TRUSTED
            },
        )
        val developmentProvider = DevelopmentPemCredentialProvider(context)
        return HeadUnitCredentialProvider {
            when (val keyStoreResult = keyStoreProvider.load()) {
                is HeadUnitCredentialResult.Ready -> keyStoreResult
                is HeadUnitCredentialResult.Unavailable -> {
                    if (keyStoreResult.status.code == HeadUnitCredentialCode.CERT_NOT_PROVISIONED) {
                        developmentProvider.load()
                    } else {
                        keyStoreResult
                    }
                }
            }
        }
    }
}
