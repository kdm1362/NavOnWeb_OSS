/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.pebble.tecomheadunit.diagnostics.upload

import android.os.Build
import com.pebble.tecomheadunit.BuildConfig
import com.pebble.tecomheadunit.diagnostics.AppDiagnostics
import com.pebble.tecomheadunit.diagnostics.DiagnosticEventCode
import com.pebble.tecomheadunit.diagnostics.DiagnosticLevel
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.time.format.DateTimeFormatter
import org.json.JSONObject

internal sealed interface DiagnosticUploadOutcome {
    data object Success : DiagnosticUploadOutcome

    data object RetryableFailure : DiagnosticUploadOutcome

    data object PermanentFailure : DiagnosticUploadOutcome
}

internal class SupabaseDiagnosticUploadClient(
    private val config: SupabaseDiagnosticConfig,
    private val authClient: SupabaseAnonymousAuthClient,
    private val transport: DiagnosticHttpTransport = UrlConnectionDiagnosticHttpTransport(),
) {
    fun submit(report: DiagnosticReport, attempt: Int): DiagnosticUploadOutcome {
        require(attempt in 1..DiagnosticUploadPolicy.MAX_ATTEMPTS)
        val requestBody = runCatching { encodeRequest(report, attempt) }
            .getOrElse { return DiagnosticUploadOutcome.PermanentFailure }
        if (requestBody.size > MAX_REQUEST_BODY_BYTES) {
            return DiagnosticUploadOutcome.PermanentFailure
        }
        val initialSession = when (val auth = authClient.validSession()) {
            is SupabaseAuthResult.Success -> auth.session
            SupabaseAuthResult.RetryableFailure -> return DiagnosticUploadOutcome.RetryableFailure
            SupabaseAuthResult.PermanentFailure -> return DiagnosticUploadOutcome.PermanentFailure
        }
        val firstResponse = when (val submitted = submitWithSession(requestBody, initialSession)) {
            is SubmissionAttempt.Response -> submitted.value
            SubmissionAttempt.IoFailure -> return DiagnosticUploadOutcome.RetryableFailure
            SubmissionAttempt.ClientFailure -> return DiagnosticUploadOutcome.PermanentFailure
        }
        if (firstResponse.statusCode != 401) return classify(firstResponse, report.id)

        val refreshedSession = when (val auth = authClient.refreshAfterUnauthorized()) {
            is SupabaseAuthResult.Success -> auth.session
            SupabaseAuthResult.RetryableFailure -> return DiagnosticUploadOutcome.RetryableFailure
            SupabaseAuthResult.PermanentFailure -> return DiagnosticUploadOutcome.PermanentFailure
        }
        val secondResponse = when (val submitted = submitWithSession(requestBody, refreshedSession)) {
            is SubmissionAttempt.Response -> submitted.value
            SubmissionAttempt.IoFailure -> return DiagnosticUploadOutcome.RetryableFailure
            SubmissionAttempt.ClientFailure -> return DiagnosticUploadOutcome.PermanentFailure
        }
        return classify(secondResponse, report.id)
    }

    private fun submitWithSession(
        requestBody: ByteArray,
        session: SupabaseAuthSession,
    ): SubmissionAttempt = try {
        SubmissionAttempt.Response(
            transport.execute(
                DiagnosticHttpRequest(
                    url = "${config.baseUrl}/functions/v1/submit-diagnostic",
                    headers = mapOf(
                        "apikey" to config.publishableKey,
                        "Authorization" to "Bearer ${session.accessToken}",
                        "Accept" to "application/json",
                        "Content-Type" to "application/json; charset=utf-8",
                    ),
                    body = requestBody,
                ),
            ),
        )
    } catch (_: IOException) {
        SubmissionAttempt.IoFailure
    } catch (_: Exception) {
        SubmissionAttempt.ClientFailure
    }

    private fun classify(
        response: DiagnosticHttpResponse,
        expectedReportId: java.util.UUID,
    ): DiagnosticUploadOutcome {
        val disposition = DiagnosticUploadPolicy.classifyHttpStatus(response.statusCode)
        val confirmed = disposition == UploadDisposition.SUCCESS && response.confirms(expectedReportId)
        AppDiagnostics.record(
            event = DiagnosticEventCode.DIAGNOSTIC_UPLOAD_RESULT,
            level = when {
                confirmed -> DiagnosticLevel.INFO
                disposition == UploadDisposition.PERMANENT_FAILURE -> DiagnosticLevel.ERROR
                else -> DiagnosticLevel.WARN
            },
            fields = buildMap {
                put("status_code", response.statusCode)
                put("disposition", disposition.name.lowercase())
                response.safeErrorCode()?.let { put("server_error", it) }
                if (disposition == UploadDisposition.SUCCESS) put("response_confirmed", confirmed)
            },
        )
        return when (disposition) {
            UploadDisposition.SUCCESS -> if (confirmed) {
                DiagnosticUploadOutcome.Success
            } else {
                // Preserve the outbox when a wrong/misconfigured endpoint returns
                // a generic 2xx/409 page instead of the diagnostic contract.
                DiagnosticUploadOutcome.RetryableFailure
            }
            UploadDisposition.RETRYABLE_FAILURE -> DiagnosticUploadOutcome.RetryableFailure
            UploadDisposition.PERMANENT_FAILURE -> DiagnosticUploadOutcome.PermanentFailure
        }
    }

    private fun encodeRequest(report: DiagnosticReport, attempt: Int): ByteArray = JSONObject()
        .put("client_report_id", report.id.toString())
        .put(
            "occurred_at",
            DateTimeFormatter.ISO_INSTANT.format(Instant.ofEpochMilli(report.occurredAtEpochMillis)),
        )
        .put("user_description", report.userDescription)
        .put("log_text", report.snapshot.logText)
        .put("truncated", report.snapshot.truncated)
        .put("original_byte_count", report.snapshot.originalByteCount)
        .put("app_version_code", BuildConfig.VERSION_CODE)
        .put("app_version_name", BuildConfig.VERSION_NAME)
        .put("android_sdk", Build.VERSION.SDK_INT)
        .put("device_model", boundedDeviceModel())
        .put("attempt", attempt)
        .put("client_locale", report.clientLocale)
        .toString()
        .toByteArray(StandardCharsets.UTF_8)

    private fun boundedDeviceModel(): String = "${Build.MANUFACTURER} ${Build.MODEL}"
        .replace(Regex("[\\u0000-\\u001f\\u007f]"), " ")
        .trim()
        .take(MAX_DEVICE_MODEL_CHARACTERS)

    private companion object {
        // JSON escaping can make a 256 KiB JSONL payload larger than its raw UTF-8 form.
        const val MAX_REQUEST_BODY_BYTES = 1 * 1_024 * 1_024
        const val MAX_DEVICE_MODEL_CHARACTERS = 120
    }

    private sealed interface SubmissionAttempt {
        data class Response(val value: DiagnosticHttpResponse) : SubmissionAttempt

        data object IoFailure : SubmissionAttempt

        data object ClientFailure : SubmissionAttempt
    }
}

private fun DiagnosticHttpResponse.confirms(expectedReportId: java.util.UUID): Boolean = runCatching {
    val payload = JSONObject(body.toString(StandardCharsets.UTF_8))
    payload.optBoolean("ok", false) &&
        payload.optString("client_report_id") == expectedReportId.toString() &&
        payload.optString("outcome") in setOf("created", "duplicate") &&
        payload.optString("report_id").matches(REPORT_ID_PATTERN)
}.getOrDefault(false)

private fun DiagnosticHttpResponse.safeErrorCode(): String? = runCatching {
    JSONObject(body.toString(StandardCharsets.UTF_8))
        .optString("error")
        .takeIf(SAFE_SERVER_ERROR_PATTERN::matches)
}.getOrNull()

private val REPORT_ID_PATTERN = Regex(
    "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-8][0-9a-fA-F]{3}-[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}",
)

private val SAFE_SERVER_ERROR_PATTERN = Regex("[a-z0-9_]{1,64}")
