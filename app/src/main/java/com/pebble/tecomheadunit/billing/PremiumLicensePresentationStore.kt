/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.pebble.tecomheadunit.billing

import android.content.Context
import java.util.Locale

/**
 * Remembers which Play entitlement has already received the one-time confirmation presentation.
 *
 * Only the existing one-way purchase-evidence digest is stored. Raw purchase tokens and receipts
 * never cross this boundary.
 */
internal class PremiumLicensePresentationStore internal constructor(
    private val readPresentedDigest: () -> String?,
    private val persistPresentedDigest: (String) -> Boolean,
) {
    constructor(context: Context) : this(
        readPresentedDigest = {
            context.applicationContext.getSharedPreferences(
                PREFERENCES_NAME,
                Context.MODE_PRIVATE,
            ).getString(KEY_PRESENTED_DIGEST, null)
        },
        persistPresentedDigest = { digest ->
            context.applicationContext.getSharedPreferences(
                PREFERENCES_NAME,
                Context.MODE_PRIVATE,
            ).edit()
                .putString(KEY_PRESENTED_DIGEST, digest)
                .commit()
        },
    )

    /** True only for valid evidence that has already completed its UI presentation. */
    @Synchronized
    fun wasPresented(evidenceDigest: String?): Boolean =
        normalizeDigest(evidenceDigest)?.let { evidence ->
            normalizeDigest(readPresentedDigest()) == evidence
        } ?: false

    /** Does not write until Compose reports that the confirmation presentation completed. */
    @Synchronized
    fun shouldPresent(evidenceDigest: String?): Boolean =
        normalizeDigest(evidenceDigest)?.let { evidence ->
            normalizeDigest(readPresentedDigest()) != evidence
        } ?: false

    /** Records a completed presentation; returns false if validation or persistence fails. */
    @Synchronized
    fun recordPresented(evidenceDigest: String?): Boolean {
        val normalized = normalizeDigest(evidenceDigest) ?: return false
        if (normalizeDigest(readPresentedDigest()) == normalized) return true
        return persistPresentedDigest(normalized)
    }

    private fun normalizeDigest(value: String?): String? = value
        ?.trim()
        ?.uppercase(Locale.ROOT)
        ?.takeIf(EVIDENCE_DIGEST_PATTERN::matches)

    private companion object {
        const val PREFERENCES_NAME = "premium_license_presentation"
        const val KEY_PRESENTED_DIGEST = "presented_purchase_evidence_sha256"
        val EVIDENCE_DIGEST_PATTERN = Regex("[0-9A-F]{64}")
    }
}
