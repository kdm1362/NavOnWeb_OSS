/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.eigenkodex.navonweb.billing

import java.nio.charset.StandardCharsets
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.time.Instant
import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReviewPromoProtocolTest {
    @Test
    fun `valid P256 P1363 signed entitlement is accepted`() {
        val keyPair = p256KeyPair()
        val now = 2_000_000_000L
        val signed = signedEntitlement(keyPair, now, now + 7_200L)

        val result = ReviewPromoEntitlementVerifier.verify(
            entitlement = signed.entitlement,
            signatureBase64Url = signed.signature,
            algorithm = "ES256",
            keyId = "promo-key-1",
            expiresAt = Instant.ofEpochSecond(now + 7_200L).toString(),
            pinnedKeyId = "promo-key-1",
            pinnedPublicKeyDerBase64 = Base64.getEncoder().encodeToString(keyPair.public.encoded),
            expected = expectedBinding(),
            nowEpochSeconds = now,
        )

        assertTrue(result is ReviewPromoEntitlementVerification.Verified)
        val grant = (result as ReviewPromoEntitlementVerification.Verified).grant
        assertEquals(now + 7_200L, grant.expiresAtEpochSeconds)
    }

    @Test
    fun `tampered binding wrong key and oversized TTL fail closed`() {
        val keyPair = p256KeyPair()
        val otherKey = p256KeyPair()
        val now = 2_000_000_000L
        val normal = signedEntitlement(keyPair, now, now + 7_200L)
        val undersized = signedEntitlement(keyPair, now, now + 599L)
        val oversized = signedEntitlement(keyPair, now, now + 14_401L)
        val publicKey = Base64.getEncoder().encodeToString(keyPair.public.encoded)

        val wrongBinding = verify(normal, publicKey, now, expectedBinding().copy(nonce = DIGEST_C))
        val wrongKey = verify(
            normal,
            Base64.getEncoder().encodeToString(otherKey.public.encoded),
            now,
            expectedBinding(),
        )
        val oversizedTtl = verify(oversized, publicKey, now, expectedBinding())
        val undersizedTtl = verify(undersized, publicKey, now, expectedBinding())

        assertTrue(wrongBinding is ReviewPromoEntitlementVerification.Rejected)
        assertTrue(wrongKey is ReviewPromoEntitlementVerification.Rejected)
        assertTrue(undersizedTtl is ReviewPromoEntitlementVerification.Rejected)
        assertTrue(oversizedTtl is ReviewPromoEntitlementVerification.Rejected)
    }

    private fun verify(
        signed: SignedEntitlement,
        publicKey: String,
        now: Long,
        binding: ReviewPromoRequestBinding,
    ) = ReviewPromoEntitlementVerifier.verify(
        entitlement = signed.entitlement,
        signatureBase64Url = signed.signature,
        algorithm = "ES256",
        keyId = "promo-key-1",
        expiresAt = Instant.ofEpochSecond(signed.expiresAt).toString(),
        pinnedKeyId = "promo-key-1",
        pinnedPublicKeyDerBase64 = publicKey,
        expected = binding,
        nowEpochSeconds = now,
    )

    private fun signedEntitlement(
        keyPair: KeyPair,
        issuedAt: Long,
        expiresAt: Long,
    ): SignedEntitlement {
        val payload = "{" +
            "\"v\":1," +
            "\"iss\":\"https://navonweb.com\"," +
            "\"aud\":\"com.eigenkodex.navonweb\"," +
            "\"sub\":\"11111111-1111-4111-8111-111111111111\"," +
            "\"scope\":\"navonweb.premium.review\"," +
            "\"iat\":$issuedAt," +
            "\"exp\":$expiresAt," +
            "\"nonce\":\"$DIGEST_A\"," +
            "\"install\":\"$DIGEST_B\"," +
            "\"pkg\":\"com.eigenkodex.navonweb\"," +
            "\"ver\":23," +
            "\"jti\":\"22222222-2222-4222-8222-222222222222\"}"
        val entitlement = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(payload.toByteArray(StandardCharsets.UTF_8))
        val signer = Signature.getInstance("SHA256withECDSA")
        signer.initSign(keyPair.private)
        signer.update(
            ("navonweb-review-entitlement-v1." + entitlement)
                .toByteArray(StandardCharsets.US_ASCII),
        )
        val rawSignature = derToP1363(signer.sign())
        return SignedEntitlement(
            entitlement = entitlement,
            signature = Base64.getUrlEncoder().withoutPadding().encodeToString(rawSignature),
            expiresAt = expiresAt,
        )
    }

    private fun p256KeyPair(): KeyPair = KeyPairGenerator.getInstance("EC").run {
        initialize(ECGenParameterSpec("secp256r1"))
        generateKeyPair()
    }

    private fun derToP1363(der: ByteArray): ByteArray {
        require(der.size >= 8 && der[0] == 0x30.toByte())
        var offset = 2
        require(der[offset++] == 0x02.toByte())
        val rLength = der[offset++].toInt() and 0xff
        val r = der.copyOfRange(offset, offset + rLength)
        offset += rLength
        require(der[offset++] == 0x02.toByte())
        val sLength = der[offset++].toInt() and 0xff
        val s = der.copyOfRange(offset, offset + sLength)
        return unsigned32(r) + unsigned32(s)
    }

    private fun unsigned32(integer: ByteArray): ByteArray {
        val unsigned = integer.dropWhile { it == 0.toByte() }.toByteArray()
        require(unsigned.size <= 32)
        return ByteArray(32 - unsigned.size) + unsigned
    }

    private fun expectedBinding() = ReviewPromoRequestBinding(
        nonce = DIGEST_A,
        installIdHash = DIGEST_B,
        packageName = "com.eigenkodex.navonweb",
        versionCode = 23,
    )

    private data class SignedEntitlement(
        val entitlement: String,
        val signature: String,
        val expiresAt: Long,
    )

    private companion object {
        const val DIGEST_A = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
        const val DIGEST_B = "BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB"
        const val DIGEST_C = "CCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCC"
    }
}
