/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.eigenkodex.navonweb.billing

import java.math.BigInteger
import java.nio.charset.StandardCharsets
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.X509EncodedKeySpec
import java.time.Instant
import java.time.format.DateTimeParseException
import java.util.Base64
import java.util.UUID
import org.json.JSONObject

internal const val REVIEW_PROMO_ISSUER = "https://navonweb.com"
internal const val REVIEW_PROMO_SCOPE = "navonweb.premium.review"

internal data class ReviewPromoRequestBinding(
    val nonce: String,
    val installIdHash: String,
    val packageName: String,
    val versionCode: Long,
)

internal data class VerifiedReviewPromoGrant(
    val entitlement: String,
    val signature: String,
    val keyId: String,
    val subject: UUID,
    val grantId: UUID,
    val issuedAtEpochSeconds: Long,
    val expiresAtEpochSeconds: Long,
)

internal sealed interface ReviewPromoEntitlementVerification {
    data class Verified(val grant: VerifiedReviewPromoGrant) : ReviewPromoEntitlementVerification
    data object Rejected : ReviewPromoEntitlementVerification
}

internal object ReviewPromoEntitlementVerifier {
    fun verify(
        entitlement: String,
        signatureBase64Url: String,
        algorithm: String,
        keyId: String,
        expiresAt: String,
        pinnedKeyId: String,
        pinnedPublicKeyDerBase64: String,
        expected: ReviewPromoRequestBinding,
        nowEpochSeconds: Long = ReviewPromoClock.epochSeconds(),
    ): ReviewPromoEntitlementVerification = runCatching {
        require(expected.nonce.isBase64UrlDigest())
        require(expected.installIdHash.isBase64UrlDigest())
        require(expected.packageName.matches(Regex("[A-Za-z][A-Za-z0-9_.]{1,199}")))
        require(expected.versionCode > 0L)
        require(algorithm == "ES256")
        require(keyId == pinnedKeyId && keyId.matches(KEY_ID_PATTERN))
        require(entitlement.length in 1..MAX_ENTITLEMENT_CHARACTERS)
        val payload = entitlement.decodeCanonicalBase64Url(MAX_ENTITLEMENT_BYTES)
        val rawSignature = signatureBase64Url.decodeCanonicalBase64Url(P1363_SIGNATURE_BYTES)
        require(rawSignature.size == P1363_SIGNATURE_BYTES)

        val publicKey = decodePinnedP256PublicKey(pinnedPublicKeyDerBase64)
        val signedInput = (REVIEW_PROMO_SIGNATURE_PREFIX + entitlement)
            .toByteArray(StandardCharsets.US_ASCII)
        val verifier = Signature.getInstance("SHA256withECDSA")
        verifier.initVerify(publicKey)
        verifier.update(signedInput)
        require(verifier.verify(p1363ToDer(rawSignature)))

        val claims = JSONObject(payload.toString(StandardCharsets.UTF_8))
        require(claims.keys().asSequence().toSet() == REQUIRED_CLAIMS)
        require(claims.requireIntegral("v") == 1L)
        require(claims.getString("iss") == REVIEW_PROMO_ISSUER)
        require(claims.getString("aud") == expected.packageName)
        require(claims.getString("scope") == REVIEW_PROMO_SCOPE)
        require(claims.getString("nonce") == expected.nonce)
        require(claims.getString("install") == expected.installIdHash)
        require(claims.getString("pkg") == expected.packageName)
        require(claims.requireIntegral("ver") == expected.versionCode)

        val issuedAt = claims.requireIntegral("iat")
        val expiresAtEpochSeconds = claims.requireIntegral("exp")
        require(issuedAt <= nowEpochSeconds + MAX_FUTURE_IAT_SKEW_SECONDS)
        require(issuedAt >= nowEpochSeconds - MAX_ISSUED_AGE_SECONDS)
        require(expiresAtEpochSeconds > nowEpochSeconds)
        require(
            expiresAtEpochSeconds - issuedAt in
                MIN_GRANT_TTL_SECONDS..MAX_GRANT_TTL_SECONDS,
        )
        val responseExpiry = try {
            Instant.parse(expiresAt).epochSecond
        } catch (_: DateTimeParseException) {
            error("invalid response expiry")
        }
        require(responseExpiry == expiresAtEpochSeconds)

        val subject = UUID.fromString(claims.getString("sub"))
        val grantId = UUID.fromString(claims.getString("jti"))
        require(subject.toString() == claims.getString("sub"))
        require(grantId.toString() == claims.getString("jti"))

        VerifiedReviewPromoGrant(
            entitlement = entitlement,
            signature = signatureBase64Url,
            keyId = keyId,
            subject = subject,
            grantId = grantId,
            issuedAtEpochSeconds = issuedAt,
            expiresAtEpochSeconds = expiresAtEpochSeconds,
        )
    }.fold(
        onSuccess = ReviewPromoEntitlementVerification::Verified,
        onFailure = { ReviewPromoEntitlementVerification.Rejected },
    )
}

internal fun sha256Base64Url(bytes: ByteArray): String = Base64.getUrlEncoder()
    .withoutPadding()
    .encodeToString(MessageDigest.getInstance("SHA-256").digest(bytes))

private fun String.isBase64UrlDigest(): Boolean =
    length == SHA256_BASE64_URL_CHARACTERS && matches(BASE64_URL_PATTERN)

private fun String.decodeCanonicalBase64Url(maxBytes: Int): ByteArray {
    require(length <= ((maxBytes + 2) / 3) * 4)
    require(matches(BASE64_URL_PATTERN))
    val decoded = Base64.getUrlDecoder().decode(this)
    require(decoded.size <= maxBytes)
    require(Base64.getUrlEncoder().withoutPadding().encodeToString(decoded) == this)
    return decoded
}

private fun decodePinnedP256PublicKey(encoded: String): ECPublicKey {
    require(encoded.length in 80..512)
    val der = Base64.getDecoder().decode(encoded)
    val key = KeyFactory.getInstance("EC")
        .generatePublic(X509EncodedKeySpec(der)) as? ECPublicKey
        ?: error("not an EC public key")
    require(key.params.curve.field.fieldSize == 256)
    require(key.params.order == P256_ORDER)
    require(key.params.cofactor == 1)
    return key
}

/** Android's SHA256withECDSA expects ASN.1 DER while the wire contract uses IEEE-P1363 r||s. */
internal fun p1363ToDer(signature: ByteArray): ByteArray {
    require(signature.size == P1363_SIGNATURE_BYTES)
    val r = positiveDerInteger(signature.copyOfRange(0, 32))
    val s = positiveDerInteger(signature.copyOfRange(32, 64))
    val sequenceLength = 2 + r.size + 2 + s.size
    return byteArrayOf(0x30, sequenceLength.toByte(), 0x02, r.size.toByte()) +
        r + byteArrayOf(0x02, s.size.toByte()) + s
}

private fun positiveDerInteger(value: ByteArray): ByteArray {
    val firstNonZero = value.indexOfFirst { it.toInt() != 0 }.let { if (it < 0) value.lastIndex else it }
    val unsigned = value.copyOfRange(firstNonZero, value.size)
    return if (unsigned.first().toInt() and 0x80 != 0) byteArrayOf(0) + unsigned else unsigned
}

private fun JSONObject.requireIntegral(name: String): Long = when (val value = get(name)) {
    is Byte -> value.toLong()
    is Short -> value.toLong()
    is Int -> value.toLong()
    is Long -> value
    else -> error("$name is not an integer")
}

private const val REVIEW_PROMO_SIGNATURE_PREFIX = "navonweb-review-entitlement-v1."
private const val SHA256_BASE64_URL_CHARACTERS = 43
private const val P1363_SIGNATURE_BYTES = 64
private const val MAX_ENTITLEMENT_BYTES = 2_048
private const val MAX_ENTITLEMENT_CHARACTERS = 2_731
private const val MAX_FUTURE_IAT_SKEW_SECONDS = 60L
private const val MAX_ISSUED_AGE_SECONDS = 300L
private const val MIN_GRANT_TTL_SECONDS = 600L
private const val MAX_GRANT_TTL_SECONDS = 14_400L
private val KEY_ID_PATTERN = Regex("[A-Za-z0-9._-]{1,64}")
private val BASE64_URL_PATTERN = Regex("[A-Za-z0-9_-]+")
private val P256_ORDER = BigInteger(
    "FFFFFFFF00000000FFFFFFFFFFFFFFFFBCE6FAADA7179E84F3B9CAC2FC632551",
    16,
)
private val REQUIRED_CLAIMS = setOf(
    "v", "iss", "aud", "sub", "scope", "iat", "exp", "nonce", "install", "pkg", "ver", "jti",
)
