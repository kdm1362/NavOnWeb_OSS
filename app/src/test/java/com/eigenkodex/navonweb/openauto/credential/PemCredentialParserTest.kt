package com.eigenkodex.navonweb.openauto.credential

import java.security.KeyPairGenerator
import java.util.Base64
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class PemCredentialParserTest {
    @Test
    fun parsesUnencryptedPkcs8PrivateKey() {
        val keyPair = KeyPairGenerator.getInstance("RSA").run {
            initialize(2048)
            generateKeyPair()
        }
        val pem = pemBlock("PRIVATE KEY", keyPair.private.encoded)

        val parsed = PemCredentialParser.parsePrivateKey(pem)

        assertEquals("RSA", parsed.algorithm)
        assertArrayEquals(keyPair.private.encoded, parsed.encoded)
    }

    @Test
    fun rejectsPkcs1PrivateKeyLabel() {
        val malformed = pemBlock("RSA PRIVATE KEY", byteArrayOf(1, 2, 3))

        assertParserFailure(HeadUnitCredentialCode.INVALID_PRIVATE_KEY) {
            PemCredentialParser.parsePrivateKey(malformed)
        }
    }

    @Test
    fun rejectsTrailingPemMaterial() {
        val keyPair = KeyPairGenerator.getInstance("RSA").run {
            initialize(2048)
            generateKeyPair()
        }
        val pem = pemBlock("PRIVATE KEY", keyPair.private.encoded) + "unexpected".toByteArray()

        assertParserFailure(HeadUnitCredentialCode.INVALID_PRIVATE_KEY) {
            PemCredentialParser.parsePrivateKey(pem)
        }
    }

    @Test
    fun rejectsMalformedCertificateBlock() {
        val malformed = "-----BEGIN CERTIFICATE-----\nnot-base64!\n-----END CERTIFICATE-----".toByteArray()

        assertParserFailure(HeadUnitCredentialCode.INVALID_CERTIFICATE) {
            PemCredentialParser.parseCertificates(malformed)
        }
    }

    private fun pemBlock(label: String, der: ByteArray): ByteArray {
        val body = Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(der)
        return "-----BEGIN $label-----\n$body\n-----END $label-----\n".toByteArray()
    }

    private fun assertParserFailure(
        expected: HeadUnitCredentialCode,
        block: () -> Unit,
    ) {
        try {
            block()
            fail("Expected PemCredentialException")
        } catch (error: PemCredentialException) {
            assertEquals(expected, error.code)
        }
    }
}
