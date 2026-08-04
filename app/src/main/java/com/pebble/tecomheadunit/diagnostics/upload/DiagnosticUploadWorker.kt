/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.pebble.tecomheadunit.diagnostics.upload

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.WorkerParameters
import java.time.Duration
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal class DiagnosticUploadWorker(
    applicationContext: Context,
    workerParameters: WorkerParameters,
) : CoroutineWorker(applicationContext, workerParameters) {
    private val statusStore = DiagnosticUploadStatusStore(applicationContext)

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val reportId = inputData.getString(KEY_REPORT_ID)
            ?.let { value -> runCatching { UUID.fromString(value) }.getOrNull() }
            ?: return@withContext permanentFailure(WorkerFailure.INVALID_INPUT)
        val outbox = DiagnosticReportOutbox(applicationContext)
        val report = outbox.load(reportId)
            ?: return@withContext Result.success(
                output(WorkerOutcome.ALREADY_REMOVED),
            )
        val age = reportAge(report.occurredAtEpochMillis)
        if (!DiagnosticUploadPolicy.shouldAttempt(runAttemptCount, age)) {
            outbox.remove(reportId)
            return@withContext permanentFailure(WorkerFailure.EXPIRED_OR_ATTEMPTS_EXHAUSTED)
        }
        val config = SupabaseDiagnosticConfig.fromBuildConfig()
        if (config == null) {
            outbox.remove(reportId)
            return@withContext permanentFailure(WorkerFailure.CONFIGURATION)
        }
        val authClient = SupabaseAnonymousAuthClient(applicationContext, config)
        val uploadClient = SupabaseDiagnosticUploadClient(config, authClient)
        val attempt = runAttemptCount + 1
        when (uploadClient.submit(report, attempt)) {
            DiagnosticUploadOutcome.Success -> {
                outbox.remove(reportId)
                statusStore.recordSuccess()
                Result.success(output(WorkerOutcome.UPLOADED))
            }
            DiagnosticUploadOutcome.PermanentFailure -> {
                outbox.remove(reportId)
                permanentFailure(WorkerFailure.SERVER_REJECTED)
            }
            DiagnosticUploadOutcome.RetryableFailure -> {
                val currentAge = reportAge(report.occurredAtEpochMillis)
                if (DiagnosticUploadPolicy.shouldRetryAfterFailure(attempt, currentAge)) {
                    Result.retry()
                } else {
                    outbox.remove(reportId)
                    permanentFailure(WorkerFailure.EXPIRED_OR_ATTEMPTS_EXHAUSTED)
                }
            }
        }
    }

    private fun reportAge(occurredAtEpochMillis: Long): Duration = Duration.ofMillis(
        (System.currentTimeMillis() - occurredAtEpochMillis).coerceAtLeast(0L),
    )

    private fun permanentFailure(reason: WorkerFailure): Result =
        Result.failure(output(WorkerOutcome.PERMANENT_FAILURE, reason)).also {
            statusStore.recordPermanentFailure(reason)
        }

    private fun output(outcome: WorkerOutcome, reason: WorkerFailure? = null): Data =
        Data.Builder()
            .putString(KEY_OUTCOME, outcome.name)
            .apply { reason?.let { putString(KEY_FAILURE, it.name) } }
            .build()

    internal companion object {
        const val KEY_REPORT_ID = "diagnostic_report_id"
        const val KEY_OUTCOME = "diagnostic_upload_outcome"
        const val KEY_FAILURE = "diagnostic_upload_failure"
    }
}

internal enum class WorkerOutcome {
    UPLOADED,
    ALREADY_REMOVED,
    PERMANENT_FAILURE,
}

enum class WorkerFailure {
    INVALID_INPUT,
    CONFIGURATION,
    SERVER_REJECTED,
    EXPIRED_OR_ATTEMPTS_EXHAUSTED,
}
