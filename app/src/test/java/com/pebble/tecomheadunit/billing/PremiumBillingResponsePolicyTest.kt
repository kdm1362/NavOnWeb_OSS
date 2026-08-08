/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.pebble.tecomheadunit.billing

import com.android.billingclient.api.BillingClient
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PremiumBillingResponsePolicyTest {
    @Test
    @Suppress("DEPRECATION")
    fun `temporary Play infrastructure errors never confirm payment failure`() {
        listOf(
            BillingClient.BillingResponseCode.SERVICE_TIMEOUT,
            BillingClient.BillingResponseCode.SERVICE_DISCONNECTED,
            BillingClient.BillingResponseCode.SERVICE_UNAVAILABLE,
            BillingClient.BillingResponseCode.NETWORK_ERROR,
            BillingClient.BillingResponseCode.BILLING_UNAVAILABLE,
            BillingClient.BillingResponseCode.ERROR,
            Int.MIN_VALUE,
        ).forEach { responseCode ->
            assertFalse(
                "response $responseCode must remain retryable",
                PremiumBillingResponsePolicy.isConfirmedPurchaseFailure(responseCode),
            )
        }
    }

    @Test
    fun `only nonrecoverable Play contract failures are terminal`() {
        listOf(
            BillingClient.BillingResponseCode.FEATURE_NOT_SUPPORTED,
            BillingClient.BillingResponseCode.ITEM_UNAVAILABLE,
            BillingClient.BillingResponseCode.DEVELOPER_ERROR,
            BillingClient.BillingResponseCode.ITEM_NOT_OWNED,
        ).forEach { responseCode ->
            assertTrue(
                "response $responseCode must be terminal",
                PremiumBillingResponsePolicy.isConfirmedPurchaseFailure(responseCode),
            )
        }
        assertFalse(
            PremiumBillingResponsePolicy.isConfirmedPurchaseFailure(
                BillingClient.BillingResponseCode.USER_CANCELED,
            ),
        )
        assertFalse(
            PremiumBillingResponsePolicy.isConfirmedPurchaseFailure(
                BillingClient.BillingResponseCode.OK,
            ),
        )
    }
}
