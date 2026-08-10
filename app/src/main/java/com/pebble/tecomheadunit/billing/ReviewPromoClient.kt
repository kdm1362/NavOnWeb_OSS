/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.pebble.tecomheadunit.billing

import android.content.Context
import com.pebble.tecomheadunit.BuildConfig
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.time.Instant
import java.time.format.DateTimeParseException
import java.util.Base64
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONObject

internal sealed interface ReviewPromoSubmissionResult {
    data class Activated(val grant: VerifiedReviewPromoGrant) : ReviewPromoSubmissionResult
    data object InvalidCode : ReviewPromoSubmissionResult
    data object RateLimited : ReviewPromoSubmissionResult
    data object RequestExpired : ReviewPromoSubmissionResult
    data object DeviceBindingUnavailable : ReviewPromoSubmissionResult
    data object NetworkUnavailable : ReviewPromoSubmissionResult
    data object ServerUnavailable : ReviewPromoSubmissionResult
    data object InvalidServerResponse : ReviewPromoSubmissionResult
    data object NotConfigured : ReviewPromoSubmissionResult
}

internal sealed interface ReviewPromoRefreshResult {
    data class Refreshed(val grant: VerifiedReviewPromoGrant) : ReviewPromoRefreshResult
    data object Revoked : ReviewPromoRefreshResult
    data object RateLimited : ReviewPromoRefreshResult
    data object RequestExpired : ReviewPromoRefreshResult
    data object DeviceBindingUnavailable : ReviewPromoRefreshResult
    data object NetworkUnavailable : ReviewPromoRefreshResult
    data object ServerUnavailable : ReviewPromoRefreshResult
    data object InvalidServerResponse : ReviewPromoRefreshResult
    data object NotConfigured : ReviewPromoRefreshResult
}

internal interface ReviewPromoClient {
    suspend fun redeem(code: String): ReviewPromoSubmissionResult
    suspend fun refresh(grant: VerifiedReviewPromoGrant): ReviewPromoRefreshResult
}

internal object ReviewPromoClientFactory {
    fun create(context: Context): ReviewPromoClient {
        val config = ReviewPromoClientConfig.fromBuildConfig()
            ?: return DisabledReviewPromoClient
        return CloudflareReviewPromoClient(
            config = config,
            installBinding = AndroidReviewPromoInstallBinding(context.applicationContext),
        )
    }
}

private object DisabledReviewPromoClient : ReviewPromoClient {
    override suspend fun redeem(code: String): ReviewPromoSubmissionResult =
        ReviewPromoSubmissionResult.NotConfigured

    override suspend fun refresh(grant: VerifiedReviewPromoGrant): ReviewPromoRefreshResult =
        ReviewPromoRefreshResult.NotConfigured
}

internal data class ReviewPromoClientConfig(
    val apiUrl: String,
    val es256KeyId: String,
    val es256PublicKeyDerBase64: String,
) {
    companion object {
        fun fromBuildConfig(): ReviewPromoClientConfig? {
            if (
                BuildConfig.REVIEW_PROMO_API_URL.isBlank() ||
                BuildConfig.REVIEW_PROMO_ES256_KEY_ID.isBlank() ||
                BuildConfig.REVIEW_PROMO_ES256_PUBLIC_KEY_DER_BASE64.isBlank()
            ) {
                return null
            }
            return ReviewPromoClientConfig(
                apiUrl = BuildConfig.REVIEW_PROMO_API_URL,
                es256KeyId = BuildConfig.REVIEW_PROMO_ES256_KEY_ID,
                es256PublicKeyDerBase64 = BuildConfig.REVIEW_PROMO_ES256_PUBLIC_KEY_DER_BASE64,
            )
        }
    }
}

internal data class ReviewPromoEndpointSet(
    val challengeUrl: String,
    val verifyUrl: String,
    val refreshUrl: String,
) {
    companion object {
        fun fromVerifyUrl(value: String): ReviewPromoEndpointSet? = runCatching {
            val verify = value.toHttpUrlOrNull() ?: error("invalid URL")
            require(verify.isHttps)
            require(verify.username.isEmpty() && verify.password.isEmpty())
            require(verify.query == null && verify.fragment == null)
            require(verify.encodedPath == VERIFY_PATH)
            fun endpoint(path: String): String = verify.newBuilder()
                .encodedPath(path)
                .build()
                .toString()
            ReviewPromoEndpointSet(
                challengeUrl = endpoint(CHALLENGE_PATH),
                verifyUrl = verify.toString(),
                refreshUrl = endpoint(REFRESH_PATH),
            )
        }.getOrNull()
    }
}

internal class CloudflareReviewPromoClient(
    private val config: ReviewPromoClientConfig,
    private val installBinding: ReviewPromoInstallBinding,
    private val packageName: String = BuildConfig.APPLICATION_ID,
    private val versionCode: Long = BuildConfig.VERSION_CODE.toLong(),
    private val clockSeconds: () -> Long = { System.currentTimeMillis() / 1_000L },
    private val httpClient: OkHttpClient = defaultReviewPromoHttpClient(),
) : ReviewPromoClient {
    private val endpoints = ReviewPromoEndpointSet.fromVerifyUrl(config.apiUrl)

    override suspend fun redeem(code: String): ReviewPromoSubmissionResult {
        if (!code.matches(REVIEW_PROMO_CODE_PATTERN)) {
            return ReviewPromoSubmissionResult.InvalidCode
        }
        val endpointSet = endpoints ?: return ReviewPromoSubmissionResult.NotConfigured
        val installIdHash = runCatching { installBinding.installIdHash(packageName) }
            .getOrElse { return ReviewPromoSubmissionResult.DeviceBindingUnavailable }
        val issuedChallenge = when (
            val result = requestChallenge(endpointSet.challengeUrl, installIdHash)
        ) {
            is ChallengeRequestResult.Issued -> result.challenge
            is ChallengeRequestResult.Failed -> return result.failure.toSubmissionResult()
        }
        if (issuedChallenge.expiresAtEpochSeconds <= clockSeconds()) {
            return ReviewPromoSubmissionResult.RequestExpired
        }

        val response = postJson(
            endpointSet.verifyUrl,
            JSONObject()
                .put("code", code)
                .put("challenge", issuedChallenge.value)
                .put("installIdHash", installIdHash)
                .put("packageName", packageName)
                .put("versionCode", versionCode),
        )
        val payload = when (response) {
            is JsonPostResult.Response -> response
            JsonPostResult.NetworkUnavailable ->
                return ReviewPromoSubmissionResult.NetworkUnavailable
            JsonPostResult.InvalidResponse ->
                return ReviewPromoSubmissionResult.InvalidServerResponse
        }
        val outcome = payload.json.optString("outcome")
        val reason = payload.json.optString("reason")
        if (!payload.successful || outcome != "granted") {
            return classifyReviewPromoRejection(payload.statusCode, reason)
        }
        return verifyGrant(payload.json, issuedChallenge, installIdHash)
            ?.let(ReviewPromoSubmissionResult::Activated)
            ?: ReviewPromoSubmissionResult.InvalidServerResponse
    }

    override suspend fun refresh(grant: VerifiedReviewPromoGrant): ReviewPromoRefreshResult {
        if (grant.expiresAtEpochSeconds <= clockSeconds()) return ReviewPromoRefreshResult.Revoked
        val endpointSet = endpoints ?: return ReviewPromoRefreshResult.NotConfigured
        val installIdHash = runCatching { installBinding.installIdHash(packageName) }
            .getOrElse { return ReviewPromoRefreshResult.DeviceBindingUnavailable }
        val issuedChallenge = when (
            val result = requestChallenge(endpointSet.challengeUrl, installIdHash)
        ) {
            is ChallengeRequestResult.Issued -> result.challenge
            is ChallengeRequestResult.Failed -> return result.failure.toRefreshResult()
        }
        if (issuedChallenge.expiresAtEpochSeconds <= clockSeconds()) {
            return ReviewPromoRefreshResult.RequestExpired
        }

        val response = postJson(
            endpointSet.refreshUrl,
            JSONObject()
                .put("challenge", issuedChallenge.value)
                .put("entitlement", grant.entitlement)
                .put("signature", grant.signature)
                .put("kid", grant.keyId)
                .put("installIdHash", installIdHash)
                .put("packageName", packageName)
                .put("versionCode", versionCode),
        )
        val payload = when (response) {
            is JsonPostResult.Response -> response
            JsonPostResult.NetworkUnavailable -> return ReviewPromoRefreshResult.NetworkUnavailable
            JsonPostResult.InvalidResponse -> return ReviewPromoRefreshResult.InvalidServerResponse
        }
        val outcome = payload.json.optString("outcome")
        val reason = payload.json.optString("reason")
        if (!payload.successful || outcome != "granted") {
            if (reason in DEFINITIVE_REVOCATION_REASONS) return ReviewPromoRefreshResult.Revoked
            return classifyRefreshFailure(payload.statusCode, reason)
        }
        return verifyGrant(payload.json, issuedChallenge, installIdHash)
            ?.let(ReviewPromoRefreshResult::Refreshed)
            ?: ReviewPromoRefreshResult.InvalidServerResponse
    }

    private suspend fun requestChallenge(
        challengeUrl: String,
        installIdHash: String,
    ): ChallengeRequestResult {
        val response = postJson(
            challengeUrl,
            JSONObject()
                .put("installIdHash", installIdHash)
                .put("packageName", packageName)
                .put("versionCode", versionCode),
        )
        val payload = when (response) {
            is JsonPostResult.Response -> response
            JsonPostResult.NetworkUnavailable ->
                return ChallengeRequestResult.Failed(RemoteFailure.NetworkUnavailable)
            JsonPostResult.InvalidResponse ->
                return ChallengeRequestResult.Failed(RemoteFailure.InvalidResponse)
        }
        val outcome = payload.json.optString("outcome")
        val reason = payload.json.optString("reason")
        if (!payload.successful || outcome != "challenge") {
            return ChallengeRequestResult.Failed(classifyRemoteFailure(payload.statusCode, reason))
        }
        val challenge = payload.json.optString("challenge")
        val nonce = payload.json.optString("nonce")
        val expiresAt = payload.json.optString("expiresAt")
        val expiresAtEpochSeconds = try {
            Instant.parse(expiresAt).epochSecond
        } catch (_: DateTimeParseException) {
            return ChallengeRequestResult.Failed(RemoteFailure.InvalidResponse)
        }
        val now = clockSeconds()
        if (
            !challenge.matches(CHALLENGE_PATTERN) ||
            !nonce.matches(BASE64_URL_DIGEST_PATTERN) ||
            expiresAtEpochSeconds <= now ||
            expiresAtEpochSeconds > now + MAX_CHALLENGE_TTL_SECONDS + CLOCK_SKEW_SECONDS
        ) {
            return ChallengeRequestResult.Failed(RemoteFailure.InvalidResponse)
        }
        return ChallengeRequestResult.Issued(
            ServerChallenge(
                value = challenge,
                nonce = nonce,
                expiresAtEpochSeconds = expiresAtEpochSeconds,
            ),
        )
    }

    private fun verifyGrant(
        response: JSONObject,
        challenge: ServerChallenge,
        installIdHash: String,
    ): VerifiedReviewPromoGrant? {
        val entitlement = response.optString("entitlement")
        val signature = response.optString("signature")
        val algorithm = response.optString("alg")
        val keyId = response.optString("kid")
        val expiresAt = response.optString("expiresAt")
        return when (
            val verified = ReviewPromoEntitlementVerifier.verify(
                entitlement = entitlement,
                signatureBase64Url = signature,
                algorithm = algorithm,
                keyId = keyId,
                expiresAt = expiresAt,
                pinnedKeyId = config.es256KeyId,
                pinnedPublicKeyDerBase64 = config.es256PublicKeyDerBase64,
                expected = ReviewPromoRequestBinding(
                    nonce = challenge.nonce,
                    installIdHash = installIdHash,
                    packageName = packageName,
                    versionCode = versionCode,
                ),
                nowEpochSeconds = clockSeconds(),
            )
        ) {
            is ReviewPromoEntitlementVerification.Verified -> verified.grant
            ReviewPromoEntitlementVerification.Rejected -> null
        }
    }

    private suspend fun postJson(url: String, json: JSONObject): JsonPostResult {
        val request = Request.Builder()
            .url(url)
            .post(json.toString().toRequestBody(JSON_MEDIA_TYPE))
            .header("Accept", "application/json")
            .header("Cache-Control", "no-store")
            .build()
        val response = try {
            withContext(Dispatchers.IO) { httpClient.newCall(request).execute() }
        } catch (_: IOException) {
            return JsonPostResult.NetworkUnavailable
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            return JsonPostResult.InvalidResponse
        }
        response.use { value ->
            val declaredLength = value.body.contentLength()
            if (declaredLength > MAX_RESPONSE_BYTES) return JsonPostResult.InvalidResponse
            val responseBytes = runCatching { value.body.bytes() }
                .getOrElse { return JsonPostResult.NetworkUnavailable }
            if (responseBytes.size > MAX_RESPONSE_BYTES) return JsonPostResult.InvalidResponse
            val responseJson = runCatching {
                JSONObject(responseBytes.toString(StandardCharsets.UTF_8))
            }.getOrNull() ?: return JsonPostResult.InvalidResponse
            return JsonPostResult.Response(
                statusCode = value.code,
                successful = value.isSuccessful,
                json = responseJson,
            )
        }
    }
}

internal fun classifyReviewPromoRejection(
    statusCode: Int,
    reason: String,
): ReviewPromoSubmissionResult = when {
    statusCode == 429 || reason == "rate_limited" -> ReviewPromoSubmissionResult.RateLimited
    reason in CHALLENGE_FAILURE_REASONS -> ReviewPromoSubmissionResult.RequestExpired
    reason in INVALID_CODE_REASONS || statusCode in setOf(400, 401, 403, 404) ->
        ReviewPromoSubmissionResult.InvalidCode
    statusCode >= 500 || reason in SERVER_FAILURE_REASONS ->
        ReviewPromoSubmissionResult.ServerUnavailable
    else -> ReviewPromoSubmissionResult.InvalidServerResponse
}

private fun classifyRefreshFailure(statusCode: Int, reason: String): ReviewPromoRefreshResult = when {
    statusCode == 429 || reason == "rate_limited" -> ReviewPromoRefreshResult.RateLimited
    reason in CHALLENGE_FAILURE_REASONS -> ReviewPromoRefreshResult.RequestExpired
    statusCode >= 500 || reason in SERVER_FAILURE_REASONS ->
        ReviewPromoRefreshResult.ServerUnavailable
    else -> ReviewPromoRefreshResult.InvalidServerResponse
}

private fun classifyRemoteFailure(statusCode: Int, reason: String): RemoteFailure = when {
    statusCode == 429 || reason == "rate_limited" -> RemoteFailure.RateLimited
    reason in CHALLENGE_FAILURE_REASONS -> RemoteFailure.RequestExpired
    statusCode >= 500 || reason in SERVER_FAILURE_REASONS -> RemoteFailure.ServerUnavailable
    else -> RemoteFailure.InvalidResponse
}

internal fun interface ReviewPromoInstallBinding {
    fun installIdHash(packageName: String): String
}

private class AndroidReviewPromoInstallBinding(context: Context) : ReviewPromoInstallBinding {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    override fun installIdHash(packageName: String): String {
        val installId = synchronized(INSTALL_ID_LOCK) {
            preferences.getString(KEY_INSTALL_ID, null)
                ?.takeIf { it.matches(INSTALL_ID_PATTERN) }
                ?: ByteArray(INSTALL_ID_BYTES).also(SecureRandom()::nextBytes).base64Url().also {
                    check(preferences.edit().putString(KEY_INSTALL_ID, it).commit()) {
                        "install binding unavailable"
                    }
                }
        }
        return sha256Base64Url(
            ("navonweb-review-install-v1\u0000$packageName\u0000$installId")
                .toByteArray(StandardCharsets.UTF_8),
        )
    }

    private companion object {
        const val PREFERENCES_NAME = "review_promo_install_binding_v1"
        const val KEY_INSTALL_ID = "install_id"
        const val INSTALL_ID_BYTES = 32
        val INSTALL_ID_PATTERN = Regex("[A-Za-z0-9_-]{43}")
        val INSTALL_ID_LOCK = Any()
    }
}

private data class ServerChallenge(
    val value: String,
    val nonce: String,
    val expiresAtEpochSeconds: Long,
)

private sealed interface ChallengeRequestResult {
    data class Issued(val challenge: ServerChallenge) : ChallengeRequestResult
    data class Failed(val failure: RemoteFailure) : ChallengeRequestResult
}

private sealed interface JsonPostResult {
    data class Response(
        val statusCode: Int,
        val successful: Boolean,
        val json: JSONObject,
    ) : JsonPostResult

    data object NetworkUnavailable : JsonPostResult
    data object InvalidResponse : JsonPostResult
}

private enum class RemoteFailure {
    RateLimited,
    RequestExpired,
    NetworkUnavailable,
    ServerUnavailable,
    InvalidResponse,
}

private fun RemoteFailure.toSubmissionResult(): ReviewPromoSubmissionResult = when (this) {
    RemoteFailure.RateLimited -> ReviewPromoSubmissionResult.RateLimited
    RemoteFailure.RequestExpired -> ReviewPromoSubmissionResult.RequestExpired
    RemoteFailure.NetworkUnavailable -> ReviewPromoSubmissionResult.NetworkUnavailable
    RemoteFailure.ServerUnavailable -> ReviewPromoSubmissionResult.ServerUnavailable
    RemoteFailure.InvalidResponse -> ReviewPromoSubmissionResult.InvalidServerResponse
}

private fun RemoteFailure.toRefreshResult(): ReviewPromoRefreshResult = when (this) {
    RemoteFailure.RateLimited -> ReviewPromoRefreshResult.RateLimited
    RemoteFailure.RequestExpired -> ReviewPromoRefreshResult.RequestExpired
    RemoteFailure.NetworkUnavailable -> ReviewPromoRefreshResult.NetworkUnavailable
    RemoteFailure.ServerUnavailable -> ReviewPromoRefreshResult.ServerUnavailable
    RemoteFailure.InvalidResponse -> ReviewPromoRefreshResult.InvalidServerResponse
}

private fun ByteArray.base64Url(): String =
    Base64.getUrlEncoder().withoutPadding().encodeToString(this)

private fun defaultReviewPromoHttpClient(): OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(10, TimeUnit.SECONDS)
    .readTimeout(20, TimeUnit.SECONDS)
    .writeTimeout(10, TimeUnit.SECONDS)
    .callTimeout(30, TimeUnit.SECONDS)
    .followRedirects(false)
    .followSslRedirects(false)
    .build()

private const val VERIFY_PATH = "/api/review-promo/verify"
private const val CHALLENGE_PATH = "/api/review-promo/challenge"
private const val REFRESH_PATH = "/api/review-promo/refresh"
private const val MAX_RESPONSE_BYTES = 16 * 1_024L
private const val MAX_CHALLENGE_TTL_SECONDS = 300L
private const val CLOCK_SKEW_SECONDS = 60L
private val REVIEW_PROMO_CODE_PATTERN = Regex("[A-Za-z0-9_-]{8,128}")
private val BASE64_URL_DIGEST_PATTERN = Regex("[A-Za-z0-9_-]{43}")
private val CHALLENGE_PATTERN = Regex("[A-Za-z0-9_-]{16,2048}\\.[A-Za-z0-9_-]{43}")
private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
private val INVALID_CODE_REASONS = setOf("invalid_code", "promo_expired")
private val CHALLENGE_FAILURE_REASONS = setOf(
    "challenge_invalid",
    "challenge_expired",
    "challenge_replayed",
)
private val DEFINITIVE_REVOCATION_REASONS = setOf(
    "grant_invalid",
    "revoked",
    "expired",
    "client_not_allowed",
)
private val SERVER_FAILURE_REASONS = setOf("server_unavailable", "configuration_error")
