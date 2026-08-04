/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.pebble.tecomheadunit.session

/**
 * Separates a locally generated one-time code from a code intentionally published for pairing.
 *
 * The phone always keeps a [PairingCodeGate] internally, but a previously trusted installation
 * must not continuously advertise fresh eight-digit codes. This state contains no browser
 * credential and no Cloudflare route cookie.
 */
internal class PairingCodePublicationState(
    initialCode: String,
    hasStoredBrowserCredential: Boolean,
    private val nowMillis: () -> Long = { System.nanoTime() / 1_000_000L },
    private val refreshCooldownMillis: Long = DEFAULT_REFRESH_COOLDOWN_MILLIS,
) {
    private var publishedCode: String? = initialCode.takeUnless { hasStoredBrowserCredential }
    private var lastReplacementAtMillis: Long? = null

    init {
        require(refreshCooldownMillis > 0L)
    }

    @Synchronized
    fun currentCode(): String? = publishedCode

    /**
     * Opens a closed window or explicitly replaces the displayed code.
     *
     * The initial automatically displayed code has no cooldown, so the first recovery tap always
     * replaces a potentially consumed Worker route. Further taps inside the cooldown return the
     * same code without another publication.
     */
    @Synchronized
    fun request(codeFactory: () -> String): PairingCodePublicationRequest {
        val now = nowMillis()
        val current = publishedCode
        val lastReplacement = lastReplacementAtMillis
        if (
            current != null &&
            lastReplacement != null &&
            (now < lastReplacement || now - lastReplacement < refreshCooldownMillis)
        ) {
            return PairingCodePublicationRequest(current, shouldPublish = false)
        }

        val replacement = codeFactory()
        publishedCode = replacement
        lastReplacementAtMillis = now
        return PairingCodePublicationRequest(replacement, shouldPublish = true)
    }

    /** Replaces an active code, or closes the window when [keepOpen] is false. */
    @Synchronized
    fun afterRotation(code: String, keepOpen: Boolean): String? {
        if (publishedCode == null) return null
        publishedCode = code.takeIf { keepOpen }
        lastReplacementAtMillis = nowMillis().takeIf { keepOpen }
        return publishedCode
    }

    /** Returns true only when an observable published code was actually withdrawn. */
    @Synchronized
    fun close(): Boolean {
        if (publishedCode == null) return false
        publishedCode = null
        lastReplacementAtMillis = null
        return true
    }

    private companion object {
        const val DEFAULT_REFRESH_COOLDOWN_MILLIS = 10_000L
    }
}

internal data class PairingCodePublicationRequest(
    val code: String,
    val shouldPublish: Boolean,
)
