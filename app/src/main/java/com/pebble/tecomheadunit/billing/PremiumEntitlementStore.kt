/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.pebble.tecomheadunit.billing

import android.content.Context
import java.security.MessageDigest
import java.util.Locale

internal data class CachedPremiumEntitlement(
    val productId: String,
    /** One-way identifier for correlating the same owned item without retaining a purchase token. */
    val purchaseTokenSha256: String,
    val observedAtEpochMillis: Long,
)

/**
 * Stores the last successful Play ownership result for offline use.
 *
 * The raw purchase token, signed JSON and signature are intentionally not persisted. A successful
 * future query that no longer returns the item clears this cache immediately. This is a UX cache,
 * not a substitute for server-side Google Play Developer API verification.
 */
internal class PremiumEntitlementStore internal constructor(
    private val read: () -> CachedPremiumEntitlement?,
    private val persist: (CachedPremiumEntitlement?) -> Boolean,
) {
    constructor(context: Context) : this(
        read = {
            val preferences = context.applicationContext.getSharedPreferences(
                PREFERENCES_NAME,
                Context.MODE_PRIVATE,
            )
            val version = preferences.getInt(KEY_VERSION, 0)
            val productId = preferences.getString(KEY_PRODUCT_ID, null).orEmpty()
            val tokenDigest = preferences.getString(KEY_TOKEN_DIGEST, null).orEmpty()
                .uppercase(Locale.ROOT)
            val observedAt = preferences.getLong(KEY_OBSERVED_AT, 0L)
            if (
                version == CACHE_VERSION &&
                isValidProductId(productId) &&
                TOKEN_DIGEST_PATTERN.matches(tokenDigest) &&
                observedAt > 0L
            ) {
                CachedPremiumEntitlement(productId, tokenDigest, observedAt)
            } else {
                null
            }
        },
        persist = { entitlement ->
            val preferences = context.applicationContext.getSharedPreferences(
                PREFERENCES_NAME,
                Context.MODE_PRIVATE,
            )
            val editor = preferences.edit()
            if (entitlement == null) {
                editor.clear()
            } else {
                editor
                    .putInt(KEY_VERSION, CACHE_VERSION)
                    .putString(KEY_PRODUCT_ID, entitlement.productId)
                    .putString(KEY_TOKEN_DIGEST, entitlement.purchaseTokenSha256)
                    .putLong(KEY_OBSERVED_AT, entitlement.observedAtEpochMillis)
            }
            editor.commit()
        },
    )

    @Synchronized
    fun load(configuredProductId: String): CachedPremiumEntitlement? =
        read()?.takeIf { cached ->
            cached.productId == configuredProductId &&
                isValidProductId(cached.productId) &&
                TOKEN_DIGEST_PATTERN.matches(cached.purchaseTokenSha256) &&
                cached.observedAtEpochMillis > 0L
        }

    @Synchronized
    fun isEntitled(configuredProductId: String): Boolean =
        load(configuredProductId) != null

    @Synchronized
    fun entitlementEvidenceDigest(configuredProductId: String): String? =
        load(configuredProductId)?.purchaseTokenSha256

    @Synchronized
    fun recordOwned(
        productId: String,
        purchaseToken: String,
        observedAtEpochMillis: Long,
    ): CachedPremiumEntitlement? {
        if (
            !isValidProductId(productId) ||
            purchaseToken.isBlank() ||
            observedAtEpochMillis <= 0L
        ) {
            return null
        }
        val cached = CachedPremiumEntitlement(
            productId = productId,
            purchaseTokenSha256 = purchaseTokenSha256(purchaseToken),
            observedAtEpochMillis = observedAtEpochMillis,
        )
        return cached.takeIf { persist(it) }
    }

    @Synchronized
    fun clear(): Boolean = persist(null)

    private companion object {
        const val PREFERENCES_NAME = "play_billing_entitlement"
        const val CACHE_VERSION = 1
        const val KEY_VERSION = "cache_version"
        const val KEY_PRODUCT_ID = "product_id"
        const val KEY_TOKEN_DIGEST = "purchase_token_sha256"
        const val KEY_OBSERVED_AT = "observed_at_epoch_millis"
        val TOKEN_DIGEST_PATTERN = Regex("[0-9A-F]{64}")
    }
}

internal enum class PremiumPurchaseState {
    PURCHASED,
    PENDING,
    OTHER,
}

internal data class PremiumPurchaseRecord(
    val productIds: Set<String>,
    val state: PremiumPurchaseState,
    val purchaseToken: String,
    val acknowledged: Boolean,
    val purchaseTimeEpochMillis: Long,
)

internal sealed interface PremiumPurchaseEvaluation {
    data class Purchased(val purchase: PremiumPurchaseRecord) : PremiumPurchaseEvaluation
    data object Pending : PremiumPurchaseEvaluation
    data object Free : PremiumPurchaseEvaluation
}

internal object PremiumPurchasePolicy {
    fun evaluate(
        productId: String,
        purchases: List<PremiumPurchaseRecord>,
    ): PremiumPurchaseEvaluation {
        if (!isValidProductId(productId)) return PremiumPurchaseEvaluation.Free
        val matching = purchases.filter { purchase ->
            productId in purchase.productIds && purchase.purchaseToken.isNotBlank()
        }
        val purchased = matching
            .filter { it.state == PremiumPurchaseState.PURCHASED }
            .maxByOrNull(PremiumPurchaseRecord::purchaseTimeEpochMillis)
        return when {
            purchased != null -> PremiumPurchaseEvaluation.Purchased(purchased)
            matching.any { it.state == PremiumPurchaseState.PENDING } ->
                PremiumPurchaseEvaluation.Pending
            else -> PremiumPurchaseEvaluation.Free
        }
    }
}

internal fun isValidProductId(productId: String): Boolean =
    productId.matches(Regex("[a-z][a-z0-9._-]{0,149}"))

internal fun purchaseTokenSha256(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(Charsets.UTF_8))
    .joinToString(separator = "") { byte -> "%02X".format(Locale.ROOT, byte) }
