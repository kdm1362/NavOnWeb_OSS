/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.pebble.tecomheadunit.billing

import java.io.IOException
import java.nio.charset.StandardCharsets
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.time.Instant
import java.util.ArrayDeque
import java.util.Base64
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReviewPromoClientTest {
    @Test
    fun `code is exact and whitespace is never normalized`() = runBlocking {
        val transport = RecordingTransport()
        val client = client(transport)

        assertEquals(
            ReviewPromoSubmissionResult.InvalidCode,
            client.redeem(" NW_ABCDEFGHJKLMNPQRSTUV"),
        )
        assertEquals(
            ReviewPromoSubmissionResult.InvalidCode,
            client.redeem("NW_ABCDEFGHJKLMNPQRSTUV\n"),
        )
        assertTrue(transport.requests.isEmpty())
    }

    @Test
    fun `redeem obtains a bound challenge before sending the exact code`() = runBlocking {
        val transport = RecordingTransport(
            responses = ArrayDeque(
                listOf(
                    response(
                        200,
                        JSONObject()
                            .put("outcome", "challenge")
                            .put("challenge", CHALLENGE)
                            .put("nonce", NONCE)
                            .put("expiresAt", Instant.ofEpochSecond(NOW + 120).toString())
                            .toString(),
                    ),
                    response(403, "{\"outcome\":\"error\",\"reason\":\"invalid_code\"}"),
                ),
            ),
        )

        val result = client(transport).redeem("NW_ABCDEFGHJKLMNPQRSTUV")

        assertEquals(ReviewPromoSubmissionResult.InvalidCode, result)
        assertEquals(
            listOf("/api/review-promo/challenge", "/api/review-promo/verify"),
            transport.requests.map { it.path },
        )
        assertEquals(
            setOf("installIdHash", "packageName", "versionCode"),
            transport.requests[0].body.keys().asSequence().toSet(),
        )
        assertEquals(
            setOf("code", "challenge", "installIdHash", "packageName", "versionCode"),
            transport.requests[1].body.keys().asSequence().toSet(),
        )
        assertEquals("NW_ABCDEFGHJKLMNPQRSTUV", transport.requests[1].body.getString("code"))
        assertEquals(CHALLENGE, transport.requests[1].body.getString("challenge"))
    }

    @Test
    fun `refresh uses a new challenge and clears definitive revocation`() = runBlocking {
        val transport = RecordingTransport(
            responses = ArrayDeque(
                listOf(
                    response(
                        200,
                        JSONObject()
                            .put("outcome", "challenge")
                            .put("challenge", CHALLENGE)
                            .put("nonce", NONCE)
                            .put("expiresAt", Instant.ofEpochSecond(NOW + 120).toString())
                            .toString(),
                    ),
                    response(403, "{\"outcome\":\"error\",\"reason\":\"revoked\"}"),
                ),
            ),
        )

        val result = client(transport).refresh(grant())

        assertEquals(ReviewPromoRefreshResult.Revoked, result)
        assertEquals("/api/review-promo/refresh", transport.requests[1].path)
        assertEquals(
            setOf(
                "challenge",
                "entitlement",
                "signature",
                "kid",
                "installIdHash",
                "packageName",
                "versionCode",
            ),
            transport.requests[1].body.keys().asSequence().toSet(),
        )
        assertFalse(transport.requests[1].body.has("code"))
    }

    @Test
    fun `signed redeem and refresh grants are bound to each server challenge`() = runBlocking {
        val keyPair = p256KeyPair()
        val publicKey = Base64.getEncoder().encodeToString(keyPair.public.encoded)
        val transport = RecordingTransport(
            responses = ArrayDeque(
                listOf(
                    challengeResponse(CHALLENGE, NONCE),
                    signedGrantResponse(
                        keyPair = keyPair,
                        nonce = NONCE,
                        grantId = "22222222-2222-4222-8222-222222222222",
                    ),
                    challengeResponse(CHALLENGE_2, NONCE_2),
                    signedGrantResponse(
                        keyPair = keyPair,
                        nonce = NONCE_2,
                        grantId = "33333333-3333-4333-8333-333333333333",
                    ),
                ),
            ),
        )
        val client = client(transport, publicKey)

        val redeemed = client.redeem("NW_ABCDEFGHJKLMNPQRSTUV")
        assertTrue(redeemed is ReviewPromoSubmissionResult.Activated)
        val initialGrant = (redeemed as ReviewPromoSubmissionResult.Activated).grant
        val refreshed = client.refresh(initialGrant)

        assertTrue(refreshed is ReviewPromoRefreshResult.Refreshed)
        val refreshedGrant = (refreshed as ReviewPromoRefreshResult.Refreshed).grant
        assertEquals(
            UUID.fromString("33333333-3333-4333-8333-333333333333"),
            refreshedGrant.grantId,
        )
        assertEquals(
            listOf(
                "/api/review-promo/challenge",
                "/api/review-promo/verify",
                "/api/review-promo/challenge",
                "/api/review-promo/refresh",
            ),
            transport.requests.map { it.path },
        )
        assertEquals(CHALLENGE_2, transport.requests.last().body.getString("challenge"))
        assertEquals(initialGrant.entitlement, transport.requests.last().body.getString("entitlement"))
    }

    @Test
    fun `challenge replay and transient service failures fail closed`() {
        assertEquals(
            ReviewPromoSubmissionResult.RequestExpired,
            classifyReviewPromoRejection(409, "challenge_replayed"),
        )
        assertEquals(
            ReviewPromoSubmissionResult.ServerUnavailable,
            classifyReviewPromoRejection(503, "server_unavailable"),
        )
        assertEquals(
            ReviewPromoSubmissionResult.InvalidCode,
            classifyReviewPromoRejection(403, "revoked"),
        )
        assertEquals(
            ReviewPromoSubmissionResult.RateLimited,
            classifyReviewPromoRejection(429, "rate_limited"),
        )
    }

    @Test
    fun `network failure never creates a grant`() = runBlocking {
        val transport = RecordingTransport(failure = IOException("offline"))

        assertEquals(
            ReviewPromoSubmissionResult.NetworkUnavailable,
            client(transport).redeem("NW_ABCDEFGHJKLMNPQRSTUV"),
        )
    }

    @Test
    fun `coroutine cancellation is never converted into a server response`() = runBlocking {
        val transport = RecordingTransport(failure = CancellationException("cancelled"))
        var propagated = false

        try {
            client(transport).redeem("NW_ABCDEFGHJKLMNPQRSTUV")
        } catch (_: CancellationException) {
            propagated = true
        }

        assertTrue(propagated)
    }

    @Test
    fun `verify endpoint must use the fixed Cloudflare route`() {
        assertEquals(
            ReviewPromoEndpointSet(
                challengeUrl = "https://promo.navonweb.com/api/review-promo/challenge",
                verifyUrl = "https://promo.navonweb.com/api/review-promo/verify",
                refreshUrl = "https://promo.navonweb.com/api/review-promo/refresh",
            ),
            ReviewPromoEndpointSet.fromVerifyUrl(
                "https://promo.navonweb.com/api/review-promo/verify",
            ),
        )
        assertEquals(null, ReviewPromoEndpointSet.fromVerifyUrl("https://promo.navonweb.com/verify"))
        assertEquals(
            null,
            ReviewPromoEndpointSet.fromVerifyUrl(
                "https://promo.navonweb.com/api/review-promo/verify?redirect=1",
            ),
        )
    }

    private fun client(
        transport: Interceptor,
        publicKey: String = "unused-before-success-response",
    ) = CloudflareReviewPromoClient(
        config = ReviewPromoClientConfig(
            apiUrl = "https://promo.navonweb.com/api/review-promo/verify",
            es256KeyId = "test-key",
            es256PublicKeyDerBase64 = publicKey,
        ),
        installBinding = ReviewPromoInstallBinding { INSTALL_HASH },
        packageName = "com.eigenkodex.navonweb",
        versionCode = 23L,
        clockSeconds = { NOW },
        httpClient = OkHttpClient.Builder().addInterceptor(transport).build(),
    )

    private fun grant() = VerifiedReviewPromoGrant(
        entitlement = "old-entitlement",
        signature = "old-signature",
        keyId = "test-key",
        subject = UUID.fromString("11111111-1111-4111-8111-111111111111"),
        grantId = UUID.fromString("22222222-2222-4222-8222-222222222222"),
        issuedAtEpochSeconds = NOW - 60,
        expiresAtEpochSeconds = NOW + 600,
    )

    private class RecordingTransport(
        private val responses: ArrayDeque<PreparedResponse> = ArrayDeque(),
        private val failure: Exception? = null,
    ) : Interceptor {
        val requests = mutableListOf<RecordedRequest>()

        override fun intercept(chain: Interceptor.Chain): Response {
            failure?.let { throw it }
            val request = chain.request()
            val requestBuffer = Buffer()
            checkNotNull(request.body).writeTo(requestBuffer)
            requests += RecordedRequest(
                path = request.url.encodedPath,
                body = JSONObject(requestBuffer.readUtf8()),
            )
            val prepared = responses.removeFirst()
            return Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(prepared.code)
                .message("test")
                .body(prepared.body.toResponseBody(JSON_MEDIA_TYPE))
                .build()
        }
    }

    private data class RecordedRequest(val path: String, val body: JSONObject)
    private data class PreparedResponse(val code: Int, val body: String)

    private fun response(code: Int, body: String) = PreparedResponse(code, body)

    private fun challengeResponse(challenge: String, nonce: String) = response(
        200,
        JSONObject()
            .put("outcome", "challenge")
            .put("challenge", challenge)
            .put("nonce", nonce)
            .put("expiresAt", Instant.ofEpochSecond(NOW + 120).toString())
            .toString(),
    )

    private fun signedGrantResponse(
        keyPair: KeyPair,
        nonce: String,
        grantId: String,
    ): PreparedResponse {
        val expiresAt = NOW + 7_200L
        val payload = "{" +
            "\"v\":1," +
            "\"iss\":\"https://navonweb.com\"," +
            "\"aud\":\"com.eigenkodex.navonweb\"," +
            "\"sub\":\"11111111-1111-4111-8111-111111111111\"," +
            "\"scope\":\"navonweb.premium.review\"," +
            "\"iat\":$NOW," +
            "\"exp\":$expiresAt," +
            "\"nonce\":\"$nonce\"," +
            "\"install\":\"$INSTALL_HASH\"," +
            "\"pkg\":\"com.eigenkodex.navonweb\"," +
            "\"ver\":23," +
            "\"jti\":\"$grantId\"}"
        val entitlement = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(payload.toByteArray(StandardCharsets.UTF_8))
        val signer = Signature.getInstance("SHA256withECDSA")
        signer.initSign(keyPair.private)
        signer.update(
            ("navonweb-review-entitlement-v1." + entitlement)
                .toByteArray(StandardCharsets.US_ASCII),
        )
        val signature = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(derToP1363(signer.sign()))
        return response(
            200,
            JSONObject()
                .put("outcome", "granted")
                .put("entitlement", entitlement)
                .put("signature", signature)
                .put("alg", "ES256")
                .put("kid", "test-key")
                .put("expiresAt", Instant.ofEpochSecond(expiresAt).toString())
                .toString(),
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

    private companion object {
        const val NOW = 2_000_000_000L
        const val NONCE = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
        const val NONCE_2 = "EEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEE"
        const val INSTALL_HASH = "BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB"
        const val CHALLENGE =
            "CCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCC." +
                "DDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDD"
        const val CHALLENGE_2 =
            "FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFF." +
                "GGGGGGGGGGGGGGGGGGGGGGGGGGGGGGGGGGGGGGGGGGG"
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}
