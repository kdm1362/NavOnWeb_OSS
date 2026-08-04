/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.pebble.tecomheadunit.openauto.credential

import java.security.MessageDigest
import java.security.cert.X509Certificate
import java.util.Locale

/** A bounded, immutable set of exact SHA-256 digests. */
internal class HeadUnitCredentialPinSet private constructor(
    pins: List<ByteArray>,
) {
    private val pins = pins.map { it.copyOf() }

    fun matches(certificate: X509Certificate): Boolean {
        val encoded = try {
            certificate.encoded
        } catch (_: Exception) {
            return false
        }
        val actual = MessageDigest.getInstance(SHA256).digest(encoded)
        return try {
            pins.any { expected -> MessageDigest.isEqual(expected, actual) }
        } finally {
            actual.fill(0)
        }
    }

    companion object {
        private const val SHA256 = "SHA-256"

        internal fun fromDigests(digests: List<ByteArray>): HeadUnitCredentialPinSet =
            HeadUnitCredentialPinSet(digests)
    }
}

/** Parses a comma-separated list of exact 64-character hexadecimal SHA-256 pins. */
internal object HeadUnitCredentialPinParser {
    private const val SHA256_HEX_LENGTH = 64
    private const val SHA256_BYTE_LENGTH = 32
    private const val MAX_PIN_COUNT = 16
    private const val MAX_INPUT_CHARACTERS = 2_048

    fun parseSha256Pins(value: String?): HeadUnitCredentialPinSet? {
        val input = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (input.length > MAX_INPUT_CHARACTERS) return null

        val tokens = input.split(',')
        if (tokens.size !in 1..MAX_PIN_COUNT) return null

        val normalized = LinkedHashSet<String>(tokens.size)
        tokens.forEach { rawToken ->
            val token = rawToken.trim()
            if (token.length != SHA256_HEX_LENGTH || token.any { !it.isAsciiHexDigit() }) {
                return null
            }
            normalized += token.uppercase(Locale.US)
        }
        if (normalized.isEmpty()) return null

        return HeadUnitCredentialPinSet.fromDigests(normalized.map(::decodeHex))
    }

    private fun decodeHex(value: String): ByteArray = ByteArray(SHA256_BYTE_LENGTH) { index ->
        val high = value[index * 2].asciiHexValue()
        val low = value[index * 2 + 1].asciiHexValue()
        ((high shl 4) or low).toByte()
    }

    private fun Char.isAsciiHexDigit(): Boolean =
        this in '0'..'9' || this in 'A'..'F' || this in 'a'..'f'

    private fun Char.asciiHexValue(): Int = when (this) {
        in '0'..'9' -> code - '0'.code
        in 'A'..'F' -> code - 'A'.code + 10
        in 'a'..'f' -> code - 'a'.code + 10
        else -> error("validated hexadecimal input contained a non-hexadecimal character")
    }
}

/**
 * Trusts only a chain whose first leaf and last anchor DER encodings match the
 * independently configured exact SHA-256 pin sets.
 */
internal class PinnedHeadUnitCredentialTrustPolicy private constructor(
    private val leafPins: HeadUnitCredentialPinSet,
    private val anchorPins: HeadUnitCredentialPinSet,
) : HeadUnitCredentialTrustPolicy {
    override fun evaluate(certificateChain: List<X509Certificate>): HeadUnitCredentialTrustDecision {
        if (certificateChain.isEmpty()) return HeadUnitCredentialTrustDecision.UNTRUSTED
        if (!leafPins.matches(certificateChain.first())) {
            return HeadUnitCredentialTrustDecision.UNTRUSTED
        }
        if (!anchorPins.matches(certificateChain.last())) {
            return HeadUnitCredentialTrustDecision.UNTRUSTED
        }
        return HeadUnitCredentialTrustDecision.TRUSTED
    }

    companion object {
        /** Invalid, partial, or empty configuration remains fail-closed. */
        fun fromSha256PinLists(
            leafPins: String?,
            anchorPins: String?,
        ): HeadUnitCredentialTrustPolicy {
            val parsedLeafPins = HeadUnitCredentialPinParser.parseSha256Pins(leafPins)
                ?: return UnconfiguredHeadUnitCredentialTrustPolicy
            val parsedAnchorPins = HeadUnitCredentialPinParser.parseSha256Pins(anchorPins)
                ?: return UnconfiguredHeadUnitCredentialTrustPolicy
            return PinnedHeadUnitCredentialTrustPolicy(parsedLeafPins, parsedAnchorPins)
        }
    }
}
