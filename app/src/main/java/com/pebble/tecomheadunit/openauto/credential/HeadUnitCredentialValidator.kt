/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.pebble.tecomheadunit.openauto.credential

import java.security.MessageDigest
import java.security.PrivateKey
import java.security.PublicKey
import java.security.Signature
import java.security.cert.CertificateExpiredException
import java.security.cert.CertificateNotYetValidException
import java.security.cert.X509Certificate
import java.security.interfaces.ECPublicKey
import java.security.interfaces.RSAPublicKey
import java.time.Clock
import java.time.Instant
import java.util.Date
import java.util.Locale

internal data class LeafCredentialFacts(
    val notBefore: Instant,
    val notAfter: Instant,
    val publicKey: PublicKey,
    val encoded: ByteArray,
    val extendedKeyUsage: List<String>?,
    val keyUsage: BooleanArray?,
    val signatureAlgorithm: String,
)

internal sealed interface LeafCredentialValidation {
    data class Valid(val fingerprintSha256: String) : LeafCredentialValidation
    data class Invalid(val code: HeadUnitCredentialCode) : LeafCredentialValidation
}

internal class HeadUnitCredentialValidator(
    private val clock: Clock = Clock.systemUTC(),
) {
    fun validate(
        certificateChain: List<X509Certificate>,
        privateKey: PrivateKey,
        source: HeadUnitCredentialSource,
    ): HeadUnitCredentialResult {
        if (certificateChain.isEmpty()) {
            return unavailable(HeadUnitCredentialCode.INVALID_CERTIFICATE, source)
        }
        if (certificateChain.size > MAX_CHAIN_LENGTH) {
            return unavailable(HeadUnitCredentialCode.INVALID_CERTIFICATE_CHAIN, source)
        }

        val validationDate = Date.from(clock.instant())
        certificateChain.forEach { certificate ->
            try {
                certificate.checkValidity(validationDate)
            } catch (_: CertificateExpiredException) {
                return unavailable(HeadUnitCredentialCode.CERTIFICATE_EXPIRED, source)
            } catch (_: CertificateNotYetValidException) {
                return unavailable(HeadUnitCredentialCode.CERTIFICATE_NOT_YET_VALID, source)
            } catch (_: Exception) {
                return unavailable(HeadUnitCredentialCode.INVALID_CERTIFICATE, source)
            }
            if (isWeakSignatureAlgorithm(certificate.sigAlgName)) {
                return unavailable(HeadUnitCredentialCode.INVALID_CERTIFICATE, source)
            }
        }

        val leaf = certificateChain.first()
        val facts = try {
            LeafCredentialFacts(
                notBefore = leaf.notBefore.toInstant(),
                notAfter = leaf.notAfter.toInstant(),
                publicKey = leaf.publicKey,
                encoded = leaf.encoded.copyOf(),
                extendedKeyUsage = leaf.extendedKeyUsage?.toList(),
                keyUsage = leaf.keyUsage?.copyOf(),
                signatureAlgorithm = leaf.sigAlgName,
            )
        } catch (_: Exception) {
            return unavailable(HeadUnitCredentialCode.INVALID_CERTIFICATE, source)
        }

        val leafValidation = validateLeaf(facts, privateKey)
        if (leafValidation is LeafCredentialValidation.Invalid) {
            return unavailable(leafValidation.code, source)
        }

        for (index in 0 until certificateChain.lastIndex) {
            val certificate = certificateChain[index]
            val issuer = certificateChain[index + 1]
            if (certificate.issuerX500Principal != issuer.subjectX500Principal) {
                return unavailable(HeadUnitCredentialCode.INVALID_CERTIFICATE_CHAIN, source)
            }
            try {
                certificate.verify(issuer.publicKey)
            } catch (_: Exception) {
                return unavailable(HeadUnitCredentialCode.INVALID_CERTIFICATE_CHAIN, source)
            }
        }

        val fingerprint = (leafValidation as LeafCredentialValidation.Valid).fingerprintSha256
        return HeadUnitCredentialResult.Ready(
            HeadUnitCredential(
                certificateChain = certificateChain,
                privateKey = privateKey,
                source = source,
                leafFingerprintSha256 = fingerprint,
            ),
        )
    }

    internal fun validateLeaf(
        facts: LeafCredentialFacts,
        privateKey: PrivateKey,
    ): LeafCredentialValidation {
        val now = clock.instant()
        if (now.isBefore(facts.notBefore)) {
            return LeafCredentialValidation.Invalid(HeadUnitCredentialCode.CERTIFICATE_NOT_YET_VALID)
        }
        if (now.isAfter(facts.notAfter)) {
            return LeafCredentialValidation.Invalid(HeadUnitCredentialCode.CERTIFICATE_EXPIRED)
        }
        if (isWeakSignatureAlgorithm(facts.signatureAlgorithm)) {
            return LeafCredentialValidation.Invalid(HeadUnitCredentialCode.INVALID_CERTIFICATE)
        }

        val extendedKeyUsage = facts.extendedKeyUsage
        if (
            extendedKeyUsage != null &&
            CLIENT_AUTH_OID !in extendedKeyUsage &&
            ANY_EXTENDED_KEY_USAGE_OID !in extendedKeyUsage
        ) {
            return LeafCredentialValidation.Invalid(HeadUnitCredentialCode.INCOMPATIBLE_KEY_USAGE)
        }
        val keyUsage = facts.keyUsage
        if (keyUsage != null && (keyUsage.isEmpty() || !keyUsage[DIGITAL_SIGNATURE_INDEX])) {
            return LeafCredentialValidation.Invalid(HeadUnitCredentialCode.INCOMPATIBLE_KEY_USAGE)
        }

        val signatureAlgorithm = signatureAlgorithm(facts.publicKey, privateKey)
            ?: return LeafCredentialValidation.Invalid(HeadUnitCredentialCode.UNSUPPORTED_KEY_ALGORITHM)
        if (!hasAdequateKeyStrength(facts.publicKey)) {
            return LeafCredentialValidation.Invalid(HeadUnitCredentialCode.UNSUPPORTED_KEY_ALGORITHM)
        }

        val challengeSignature = try {
            Signature.getInstance(signatureAlgorithm).run {
                initSign(privateKey)
                update(KEY_MATCH_CHALLENGE)
                sign()
            }
        } catch (_: Exception) {
            return LeafCredentialValidation.Invalid(HeadUnitCredentialCode.KEY_ACCESS_ERROR)
        }
        val matches = try {
            Signature.getInstance(signatureAlgorithm).run {
                initVerify(facts.publicKey)
                update(KEY_MATCH_CHALLENGE)
                verify(challengeSignature)
            }
        } catch (_: Exception) {
            false
        }
        if (!matches) {
            return LeafCredentialValidation.Invalid(HeadUnitCredentialCode.KEY_MISMATCH)
        }

        return LeafCredentialValidation.Valid(sha256Hex(facts.encoded))
    }

    private fun signatureAlgorithm(publicKey: PublicKey, privateKey: PrivateKey): String? {
        val publicAlgorithm = normalizeKeyAlgorithm(publicKey.algorithm)
        val privateAlgorithm = normalizeKeyAlgorithm(privateKey.algorithm)
        if (publicAlgorithm != privateAlgorithm) return null
        return when (privateAlgorithm) {
            "RSA" -> "SHA256withRSA"
            "EC" -> "SHA256withECDSA"
            else -> null
        }
    }

    private fun hasAdequateKeyStrength(publicKey: PublicKey): Boolean = when (publicKey) {
        is RSAPublicKey -> publicKey.modulus.bitLength() >= MIN_RSA_BITS
        is ECPublicKey -> publicKey.params.order.bitLength() >= MIN_EC_BITS
        else -> false
    }

    private fun normalizeKeyAlgorithm(value: String): String = when (value.uppercase(Locale.ROOT)) {
        "ECDSA" -> "EC"
        else -> value.uppercase(Locale.ROOT)
    }

    private fun isWeakSignatureAlgorithm(value: String): Boolean {
        val normalized = value.uppercase(Locale.ROOT).replace("-", "")
        return normalized.contains("MD5") || normalized.contains("SHA1")
    }

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { byte -> "%02X".format(Locale.ROOT, byte.toInt() and 0xff) }

    private companion object {
        const val CLIENT_AUTH_OID = "1.3.6.1.5.5.7.3.2"
        const val ANY_EXTENDED_KEY_USAGE_OID = "2.5.29.37.0"
        const val DIGITAL_SIGNATURE_INDEX = 0
        const val MAX_CHAIN_LENGTH = 6
        const val MIN_RSA_BITS = 2048
        const val MIN_EC_BITS = 256
        val KEY_MATCH_CHALLENGE = "tecom-android-auto-key-match-v1".toByteArray(Charsets.UTF_8)
    }
}
