package com.eigenkodex.navonweb.openauto.credential

import java.math.BigInteger
import java.security.MessageDigest
import java.security.Principal
import java.security.PublicKey
import java.security.cert.CertificateEncodingException
import java.security.cert.X509Certificate
import java.util.Date
import javax.security.auth.x500.X500Principal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class PinnedHeadUnitCredentialTrustPolicyTest {
    @Test
    fun `parser accepts bounded comma-separated 64 hex pins`() {
        val first = byteArrayOf(1, 2, 3).sha256Hex()
        val second = byteArrayOf(4, 5, 6).sha256Hex()

        assertNotNull(
            HeadUnitCredentialPinParser.parseSha256Pins(
                "${first.lowercase()}, $second",
            ),
        )
    }

    @Test
    fun `parser rejects blank malformed partial and excessive pin lists`() {
        val valid = byteArrayOf(1).sha256Hex()

        assertNull(HeadUnitCredentialPinParser.parseSha256Pins(null))
        assertNull(HeadUnitCredentialPinParser.parseSha256Pins("  "))
        assertNull(HeadUnitCredentialPinParser.parseSha256Pins(valid.dropLast(1)))
        assertNull(HeadUnitCredentialPinParser.parseSha256Pins("${"0".repeat(63)}G"))
        assertNull(HeadUnitCredentialPinParser.parseSha256Pins("$valid,,${valid}"))
        assertNull(
            HeadUnitCredentialPinParser.parseSha256Pins(
                List(17) { valid }.joinToString(","),
            ),
        )
    }

    @Test
    fun `missing or invalid production pin configuration stays unconfigured`() {
        val valid = byteArrayOf(1).sha256Hex()

        assertEquals(
            HeadUnitCredentialTrustDecision.NOT_CONFIGURED,
            policy("", valid).evaluate(listOf(FakeCertificate(byteArrayOf(1)))),
        )
        assertEquals(
            HeadUnitCredentialTrustDecision.NOT_CONFIGURED,
            policy(valid, "not-a-pin").evaluate(listOf(FakeCertificate(byteArrayOf(1)))),
        )
    }

    @Test
    fun `policy requires exact first leaf and last anchor DER pins`() {
        val leaf = FakeCertificate("leaf".toByteArray())
        val middle = FakeCertificate("middle".toByteArray())
        val anchor = FakeCertificate("anchor".toByteArray())
        val chain = listOf(leaf, middle, anchor)

        assertEquals(
            HeadUnitCredentialTrustDecision.TRUSTED,
            policy(leaf.der.sha256Hex(), anchor.der.sha256Hex()).evaluate(chain),
        )
        assertEquals(
            HeadUnitCredentialTrustDecision.UNTRUSTED,
            policy(middle.der.sha256Hex(), anchor.der.sha256Hex()).evaluate(chain),
        )
        assertEquals(
            HeadUnitCredentialTrustDecision.UNTRUSTED,
            policy(leaf.der.sha256Hex(), middle.der.sha256Hex()).evaluate(chain),
        )
    }

    @Test
    fun `policy accepts any configured rotation pin in each independent set`() {
        val leaf = FakeCertificate("leaf-current".toByteArray())
        val anchor = FakeCertificate("anchor-current".toByteArray())
        val leafPins = "${"old-leaf".toByteArray().sha256Hex()},${leaf.der.sha256Hex()}"
        val anchorPins = "${"old-anchor".toByteArray().sha256Hex()},${anchor.der.sha256Hex()}"

        assertEquals(
            HeadUnitCredentialTrustDecision.TRUSTED,
            policy(leafPins, anchorPins).evaluate(listOf(leaf, anchor)),
        )
    }

    @Test
    fun `empty chain and certificate encoding errors are untrusted`() {
        val valid = byteArrayOf(1).sha256Hex()
        val configured = policy(valid, valid)

        assertEquals(
            HeadUnitCredentialTrustDecision.UNTRUSTED,
            configured.evaluate(emptyList()),
        )
        assertEquals(
            HeadUnitCredentialTrustDecision.UNTRUSTED,
            configured.evaluate(listOf(FakeCertificate(byteArrayOf(1), failEncoding = true))),
        )
    }

    private fun policy(leafPins: String?, anchorPins: String?): HeadUnitCredentialTrustPolicy =
        PinnedHeadUnitCredentialTrustPolicy.fromSha256PinLists(leafPins, anchorPins)

    private fun ByteArray.sha256Hex(): String =
        MessageDigest.getInstance("SHA-256")
            .digest(this)
            .joinToString("") { byte -> "%02X".format(byte.toInt() and 0xff) }

    @Suppress("DEPRECATION")
    private class FakeCertificate(
        val der: ByteArray,
        private val failEncoding: Boolean = false,
    ) : X509Certificate() {
        private val principal = X500Principal("CN=NAVONWEB pin test")
        private val publicKey = object : PublicKey {
            override fun getAlgorithm() = "RSA"
            override fun getFormat() = "X.509"
            override fun getEncoded() = byteArrayOf(1, 2, 3)
        }

        override fun getEncoded(): ByteArray {
            if (failEncoding) throw CertificateEncodingException("test failure")
            return der.copyOf()
        }

        override fun checkValidity() = Unit
        override fun checkValidity(date: Date?) = Unit
        override fun getVersion() = 3
        override fun getSerialNumber() = BigInteger.ONE
        override fun getIssuerDN(): Principal = principal
        override fun getSubjectDN(): Principal = principal
        override fun getIssuerX500Principal() = principal
        override fun getSubjectX500Principal() = principal
        override fun getNotBefore() = Date(0)
        override fun getNotAfter() = Date(Long.MAX_VALUE)
        override fun getTBSCertificate() = der.copyOf()
        override fun getSignature() = byteArrayOf(1)
        override fun getSigAlgName() = "SHA256withRSA"
        override fun getSigAlgOID() = "1.2.840.113549.1.1.11"
        override fun getSigAlgParams(): ByteArray? = null
        override fun getIssuerUniqueID(): BooleanArray? = null
        override fun getSubjectUniqueID(): BooleanArray? = null
        override fun getKeyUsage(): BooleanArray? = null
        override fun getBasicConstraints() = -1
        override fun verify(key: PublicKey?) = Unit
        override fun verify(key: PublicKey?, sigProvider: String?) = Unit
        override fun toString() = "FakeCertificate"
        override fun getPublicKey() = publicKey
        override fun getCriticalExtensionOIDs(): MutableSet<String>? = null
        override fun getNonCriticalExtensionOIDs(): MutableSet<String>? = null
        override fun getExtensionValue(oid: String?): ByteArray? = null
        override fun hasUnsupportedCriticalExtension() = false
    }
}
