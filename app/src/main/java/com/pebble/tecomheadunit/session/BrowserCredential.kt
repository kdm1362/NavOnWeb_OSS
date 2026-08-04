/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.pebble.tecomheadunit.session

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

object BrowserCredential {
    private const val TOKEN_BYTES = 32
    private const val TOKEN_LENGTH = 43
    private val secureRandom = SecureRandom()
    private val encoder = Base64.getUrlEncoder().withoutPadding()
    private val decoder = Base64.getUrlDecoder()

    fun create(): String = ByteArray(TOKEN_BYTES)
        .also(secureRandom::nextBytes)
        .let(encoder::encodeToString)

    fun digest(token: String): String = encoder.encodeToString(
        MessageDigest.getInstance("SHA-256")
            .digest(token.toByteArray(StandardCharsets.UTF_8)),
    )

    fun isValid(candidate: String?, expectedDigest: String?): Boolean {
        if (candidate == null || expectedDigest == null) return false
        if (candidate.length != TOKEN_LENGTH || !candidate.all(::isTokenCharacter)) return false

        val expected = decodeDigest(expectedDigest) ?: return false
        val actual = MessageDigest.getInstance("SHA-256")
            .digest(candidate.toByteArray(StandardCharsets.UTF_8))
        return MessageDigest.isEqual(actual, expected)
    }

    /** Checks only the stored digest shape; no browser credential is reconstructed or exposed. */
    fun isStoredDigest(value: String?): Boolean = decodeDigest(value)?.size == TOKEN_BYTES

    private fun decodeDigest(value: String?): ByteArray? {
        if (value == null || value.length != TOKEN_LENGTH || !value.all(::isTokenCharacter)) {
            return null
        }
        return runCatching { decoder.decode(value) }
            .getOrNull()
            ?.takeIf { it.size == TOKEN_BYTES }
    }

    private fun isTokenCharacter(character: Char): Boolean =
        character in 'a'..'z' ||
            character in 'A'..'Z' ||
            character in '0'..'9' ||
            character == '-' ||
            character == '_'
}
