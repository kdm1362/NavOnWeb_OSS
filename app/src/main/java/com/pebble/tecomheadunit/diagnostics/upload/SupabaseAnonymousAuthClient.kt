/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.pebble.tecomheadunit.diagnostics.upload

import android.content.Context
import com.pebble.tecomheadunit.BuildConfig
import java.io.IOException
import java.net.URI
import java.nio.charset.StandardCharsets
import org.json.JSONObject

internal data class SupabaseDiagnosticConfig(
    val baseUrl: String,
    val publishableKey: String,
) {
    companion object {
        fun fromBuildConfig(): SupabaseDiagnosticConfig? {
            val baseUrl = BuildConfig.SUPABASE_URL.trim().trimEnd('/')
            val publishableKey = BuildConfig.SUPABASE_PUBLISHABLE_KEY.trim()
            return runCatching {
                val endpoint = URI(baseUrl)
                require(endpoint.scheme.equals("https", ignoreCase = true))
                require(!endpoint.host.isNullOrBlank())
                require(endpoint.rawQuery == null && endpoint.rawFragment == null)
                require(endpoint.path.isNullOrEmpty() || endpoint.path == "/")
                require(publishableKey.startsWith("sb_publishable_"))
                require(publishableKey.length <= MAX_PUBLISHABLE_KEY_CHARACTERS)
                SupabaseDiagnosticConfig(baseUrl, publishableKey)
            }.getOrNull()
        }

        private const val MAX_PUBLISHABLE_KEY_CHARACTERS = 1_024
    }
}

internal sealed interface SupabaseAuthResult {
    data class Success(val session: SupabaseAuthSession) : SupabaseAuthResult

    data object RetryableFailure : SupabaseAuthResult

    data object PermanentFailure : SupabaseAuthResult
}

internal class SupabaseAnonymousAuthClient(
    context: Context,
    private val config: SupabaseDiagnosticConfig,
    private val transport: DiagnosticHttpTransport = UrlConnectionDiagnosticHttpTransport(),
    private val clockEpochSeconds: () -> Long = { System.currentTimeMillis() / 1_000L },
) {
    private val sessionStore = EncryptedSupabaseSessionStore(context)

    fun validSession(): SupabaseAuthResult = synchronized(AUTH_LOCK) {
        val current = sessionStore.load()
        if (current?.isAccessTokenUsable(clockEpochSeconds()) == true) {
            return@synchronized SupabaseAuthResult.Success(current)
        }
        if (current != null) {
            when (val refreshed = requestRefresh(current.refreshToken)) {
                is SupabaseAuthResult.Success -> return@synchronized refreshed
                SupabaseAuthResult.RetryableFailure -> return@synchronized refreshed
                SupabaseAuthResult.PermanentFailure -> sessionStore.clear()
            }
        }
        requestAnonymousSession()
    }

    /** Refreshes once after a Function 401 without ever exposing the refresh token to that Function. */
    fun refreshAfterUnauthorized(): SupabaseAuthResult = synchronized(AUTH_LOCK) {
        val current = sessionStore.load()
        if (current != null) {
            when (val refreshed = requestRefresh(current.refreshToken)) {
                is SupabaseAuthResult.Success -> return@synchronized refreshed
                SupabaseAuthResult.RetryableFailure -> return@synchronized refreshed
                SupabaseAuthResult.PermanentFailure -> sessionStore.clear()
            }
        }
        requestAnonymousSession()
    }

    private fun requestAnonymousSession(): SupabaseAuthResult = requestSession(
        url = "${config.baseUrl}/auth/v1/signup",
        requestBody = JSONObject().toString().toByteArray(StandardCharsets.UTF_8),
        fallbackRefreshToken = null,
    )

    private fun requestRefresh(refreshToken: String): SupabaseAuthResult = requestSession(
        url = "${config.baseUrl}/auth/v1/token?grant_type=refresh_token",
        requestBody = JSONObject()
            .put("refresh_token", refreshToken)
            .toString()
            .toByteArray(StandardCharsets.UTF_8),
        fallbackRefreshToken = refreshToken,
    )

    private fun requestSession(
        url: String,
        requestBody: ByteArray,
        fallbackRefreshToken: String?,
    ): SupabaseAuthResult {
        val response = try {
            transport.execute(
                DiagnosticHttpRequest(
                    url = url,
                    headers = mapOf(
                        "apikey" to config.publishableKey,
                        "Accept" to "application/json",
                        "Content-Type" to "application/json; charset=utf-8",
                    ),
                    body = requestBody,
                ),
            )
        } catch (_: IOException) {
            return SupabaseAuthResult.RetryableFailure
        } catch (_: Exception) {
            return SupabaseAuthResult.PermanentFailure
        }
        if (response.statusCode !in 200..299) {
            return when (DiagnosticUploadPolicy.classifyHttpStatus(response.statusCode)) {
                UploadDisposition.RETRYABLE_FAILURE -> SupabaseAuthResult.RetryableFailure
                else -> SupabaseAuthResult.PermanentFailure
            }
        }
        val session = runCatching {
            val payload = JSONObject(response.body.toString(StandardCharsets.UTF_8))
            val now = clockEpochSeconds()
            val expiresAt = payload.optLong("expires_at", 0L).takeIf { it > now }
                ?: payload.optLong("expires_in", 0L)
                    .takeIf { it in 1..MAX_SESSION_LIFETIME_SECONDS }
                    ?.let(now::plus)
                ?: error("missing token expiry")
            SupabaseAuthSession(
                accessToken = payload.getString("access_token"),
                refreshToken = payload.optString("refresh_token")
                    .takeIf(String::isNotBlank)
                    ?: fallbackRefreshToken
                    ?: error("missing refresh token"),
                expiresAtEpochSeconds = expiresAt,
            )
        }.getOrElse { return SupabaseAuthResult.PermanentFailure }
        return if (sessionStore.save(session)) {
            SupabaseAuthResult.Success(session)
        } else {
            SupabaseAuthResult.PermanentFailure
        }
    }

    private companion object {
        val AUTH_LOCK = Any()
        const val MAX_SESSION_LIFETIME_SECONDS = 7L * 24L * 60L * 60L
    }
}
