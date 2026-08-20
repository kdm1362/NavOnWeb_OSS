package com.eigenkodex.navonweb.openauto.credential

import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HeadUnitCredentialValidatorTest {
    private val now = Instant.parse("2026-08-01T00:00:00Z")
    private val validator = HeadUnitCredentialValidator(Clock.fixed(now, ZoneOffset.UTC))
    private val matching = rsaKeyPair()

    @Test
    fun acceptsMatchingCredentialWithoutExplicitExtendedKeyUsage() {
        val encoded = "deterministic-test-leaf".toByteArray()
        val result = validator.validateLeaf(validFacts(matching, encoded = encoded), matching.private)

        assertTrue(result is LeafCredentialValidation.Valid)
        assertEquals(
            MessageDigest.getInstance("SHA-256").digest(encoded).toHex(),
            (result as LeafCredentialValidation.Valid).fingerprintSha256,
        )
    }

    @Test
    fun rejectsMismatchedPrivateKey() {
        val result = validator.validateLeaf(validFacts(matching), rsaKeyPair().private)

        assertInvalid(HeadUnitCredentialCode.KEY_MISMATCH, result)
    }

    @Test
    fun rejectsExpiredCertificate() {
        val result = validator.validateLeaf(
            validFacts(
                matching,
                notBefore = now.minusSeconds(120),
                notAfter = now.minusSeconds(1),
            ),
            matching.private,
        )

        assertInvalid(HeadUnitCredentialCode.CERTIFICATE_EXPIRED, result)
    }

    @Test
    fun rejectsCertificateWhoseValidityHasNotStarted() {
        val result = validator.validateLeaf(
            validFacts(
                matching,
                notBefore = now.plusSeconds(1),
                notAfter = now.plusSeconds(120),
            ),
            matching.private,
        )

        assertInvalid(HeadUnitCredentialCode.CERTIFICATE_NOT_YET_VALID, result)
    }

    @Test
    fun rejectsServerOnlyExtendedKeyUsage() {
        val result = validator.validateLeaf(
            validFacts(matching, extendedKeyUsage = listOf(SERVER_AUTH_OID)),
            matching.private,
        )

        assertInvalid(HeadUnitCredentialCode.INCOMPATIBLE_KEY_USAGE, result)
    }

    @Test
    fun acceptsExplicitClientAuthenticationExtendedKeyUsage() {
        val result = validator.validateLeaf(
            validFacts(matching, extendedKeyUsage = listOf(CLIENT_AUTH_OID)),
            matching.private,
        )

        assertTrue(result is LeafCredentialValidation.Valid)
    }

    @Test
    fun rejectsCertificateThatExplicitlyForbidsDigitalSignatures() {
        val result = validator.validateLeaf(
            validFacts(matching, keyUsage = BooleanArray(9)),
            matching.private,
        )

        assertInvalid(HeadUnitCredentialCode.INCOMPATIBLE_KEY_USAGE, result)
    }

    private fun validFacts(
        pair: KeyPair,
        notBefore: Instant = now.minusSeconds(60),
        notAfter: Instant = now.plusSeconds(60),
        encoded: ByteArray = "leaf".toByteArray(),
        extendedKeyUsage: List<String>? = null,
        keyUsage: BooleanArray? = null,
    ) = LeafCredentialFacts(
        notBefore = notBefore,
        notAfter = notAfter,
        publicKey = pair.public,
        encoded = encoded,
        extendedKeyUsage = extendedKeyUsage,
        keyUsage = keyUsage,
        signatureAlgorithm = "SHA256withRSA",
    )

    private fun assertInvalid(
        expected: HeadUnitCredentialCode,
        actual: LeafCredentialValidation,
    ) {
        assertTrue(actual is LeafCredentialValidation.Invalid)
        assertEquals(expected, (actual as LeafCredentialValidation.Invalid).code)
    }

    private fun rsaKeyPair(): KeyPair = KeyPairGenerator.getInstance("RSA").run {
        initialize(2048)
        generateKeyPair()
    }

    private fun ByteArray.toHex(): String =
        joinToString("") { byte -> "%02X".format(byte.toInt() and 0xff) }

    private companion object {
        const val CLIENT_AUTH_OID = "1.3.6.1.5.5.7.3.2"
        const val SERVER_AUTH_OID = "1.3.6.1.5.5.7.3.1"
    }
}
