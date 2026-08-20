/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.eigenkodex.navonweb.openauto.credential

import java.io.ByteArrayInputStream
import java.security.KeyFactory
import java.security.PrivateKey
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64

internal class PemCredentialException(val code: HeadUnitCredentialCode) : Exception()

internal object PemCredentialParser {
    fun parseCertificates(bytes: ByteArray): List<X509Certificate> {
        val blocks = decodeCertificateBlocks(bytes)
        return try {
            val factory = CertificateFactory.getInstance("X.509")
            blocks.map { der ->
                ByteArrayInputStream(der).use { input ->
                    val certificate = factory.generateCertificate(input) as X509Certificate
                    if (input.available() != 0) {
                        throw PemCredentialException(HeadUnitCredentialCode.INVALID_CERTIFICATE)
                    }
                    certificate
                }
            }
        } catch (_: Exception) {
            throw PemCredentialException(HeadUnitCredentialCode.INVALID_CERTIFICATE)
        } finally {
            blocks.forEach { it.fill(0) }
        }
    }

    fun parsePrivateKey(bytes: ByteArray): PrivateKey {
        val der = decodeSingleBlock(bytes, PRIVATE_KEY_BEGIN, PRIVATE_KEY_END)
        try {
            val spec = PKCS8EncodedKeySpec(der)
            for (algorithm in SUPPORTED_KEY_FACTORIES) {
                try {
                    return KeyFactory.getInstance(algorithm).generatePrivate(spec)
                } catch (_: Exception) {
                    // Try the next explicitly supported key algorithm.
                }
            }
            throw PemCredentialException(HeadUnitCredentialCode.INVALID_PRIVATE_KEY)
        } finally {
            der.fill(0)
        }
    }

    private fun decodeCertificateBlocks(bytes: ByteArray): List<ByteArray> {
        val text = bytes.toString(Charsets.US_ASCII)
        val blocks = mutableListOf<ByteArray>()
        var cursor = 0
        while (true) {
            cursor = skipWhitespace(text, cursor)
            if (cursor == text.length) break
            if (!text.startsWith(CERTIFICATE_BEGIN, cursor)) {
                blocks.forEach { it.fill(0) }
                throw PemCredentialException(HeadUnitCredentialCode.INVALID_CERTIFICATE)
            }
            val bodyStart = cursor + CERTIFICATE_BEGIN.length
            val bodyEnd = text.indexOf(CERTIFICATE_END, bodyStart)
            if (bodyEnd < 0) {
                blocks.forEach { it.fill(0) }
                throw PemCredentialException(HeadUnitCredentialCode.INVALID_CERTIFICATE)
            }
            blocks += decodeBody(
                text.substring(bodyStart, bodyEnd),
                HeadUnitCredentialCode.INVALID_CERTIFICATE,
            )
            if (blocks.size > MAX_CERTIFICATE_COUNT) {
                blocks.forEach { it.fill(0) }
                throw PemCredentialException(HeadUnitCredentialCode.INVALID_CERTIFICATE_CHAIN)
            }
            cursor = bodyEnd + CERTIFICATE_END.length
        }
        if (blocks.isEmpty()) {
            throw PemCredentialException(HeadUnitCredentialCode.INVALID_CERTIFICATE)
        }
        return blocks
    }

    private fun decodeSingleBlock(bytes: ByteArray, begin: String, end: String): ByteArray {
        val text = bytes.toString(Charsets.US_ASCII)
        val start = skipWhitespace(text, 0)
        if (!text.startsWith(begin, start)) {
            throw PemCredentialException(HeadUnitCredentialCode.INVALID_PRIVATE_KEY)
        }
        val bodyStart = start + begin.length
        val bodyEnd = text.indexOf(end, bodyStart)
        if (bodyEnd < 0) {
            throw PemCredentialException(HeadUnitCredentialCode.INVALID_PRIVATE_KEY)
        }
        val trailingStart = skipWhitespace(text, bodyEnd + end.length)
        if (trailingStart != text.length) {
            throw PemCredentialException(HeadUnitCredentialCode.INVALID_PRIVATE_KEY)
        }
        return decodeBody(
            text.substring(bodyStart, bodyEnd),
            HeadUnitCredentialCode.INVALID_PRIVATE_KEY,
        )
    }

    private fun decodeBody(body: String, errorCode: HeadUnitCredentialCode): ByteArray {
        val compact = buildString(body.length) {
            body.forEach { character ->
                if (!character.isWhitespace()) append(character)
            }
        }
        if (compact.isEmpty() || compact.any { it !in BASE64_CHARACTERS }) {
            throw PemCredentialException(errorCode)
        }
        return try {
            Base64.getDecoder().decode(compact)
        } catch (_: IllegalArgumentException) {
            throw PemCredentialException(errorCode)
        }
    }

    private fun skipWhitespace(value: String, from: Int): Int {
        var index = from
        while (index < value.length && value[index].isWhitespace()) index += 1
        return index
    }

    private const val CERTIFICATE_BEGIN = "-----BEGIN CERTIFICATE-----"
    private const val CERTIFICATE_END = "-----END CERTIFICATE-----"
    private const val PRIVATE_KEY_BEGIN = "-----BEGIN PRIVATE KEY-----"
    private const val PRIVATE_KEY_END = "-----END PRIVATE KEY-----"
    private const val MAX_CERTIFICATE_COUNT = 6
    private val SUPPORTED_KEY_FACTORIES = listOf("RSA", "EC")
    private val BASE64_CHARACTERS =
        "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/=".toSet()
}
