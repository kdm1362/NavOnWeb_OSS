/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.pebble.tecomheadunit.diagnostics.upload

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.Operation
import androidx.work.WorkManager
import androidx.work.workDataOf
import java.util.UUID
import java.util.concurrent.TimeUnit

internal class DiagnosticUploadScheduler(context: Context) {
    private val workManager = WorkManager.getInstance(context.applicationContext)

    fun enqueue(reportId: UUID): Boolean = try {
        val reportIdData = workDataOf(DiagnosticUploadWorker.KEY_REPORT_ID to reportId.toString())
        val uploadRequest = OneTimeWorkRequestBuilder<DiagnosticUploadWorker>()
            .setInputData(reportIdData)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build(),
            )
            .setBackoffCriteria(
                BackoffPolicy.LINEAR,
                DiagnosticUploadPolicy.RETRY_BACKOFF.toMinutes(),
                TimeUnit.MINUTES,
            )
            .addTag(WORK_TAG)
            .build()
        val uploadEnqueued = await(
            workManager.enqueueUniqueWork(
                workName(reportId),
                ExistingWorkPolicy.KEEP,
                uploadRequest,
            ),
        )
        if (!uploadEnqueued) return false
        val expiryRequest = OneTimeWorkRequestBuilder<DiagnosticOutboxExpiryWorker>()
            .setInputData(reportIdData)
            .setInitialDelay(
                DiagnosticUploadPolicy.MAX_REPORT_AGE.toMinutes(),
                TimeUnit.MINUTES,
            )
            .addTag(EXPIRY_WORK_TAG)
            .build()
        val expiryEnqueued = await(
            workManager.enqueueUniqueWork(
                expiryWorkName(reportId),
                ExistingWorkPolicy.KEEP,
                expiryRequest,
            ),
        )
        if (!expiryEnqueued) {
            cancel(reportId)
            false
        } else {
            true
        }
    } catch (_: Exception) {
        false
    }

    fun cancel(reportId: UUID): Boolean {
        val uploadCancelled = runCatching {
            await(workManager.cancelUniqueWork(workName(reportId)))
        }.getOrDefault(false)
        val expiryCancelled = runCatching {
            await(workManager.cancelUniqueWork(expiryWorkName(reportId)))
        }.getOrDefault(false)
        return uploadCancelled && expiryCancelled
    }

    private fun await(operation: Operation): Boolean = runCatching {
        operation.result.get(OPERATION_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        true
    }.getOrDefault(false)

    internal companion object {
        const val WORK_TAG = "diagnostic-upload"
        const val EXPIRY_WORK_TAG = "diagnostic-upload-expiry"
        const val OPERATION_TIMEOUT_SECONDS = 10L
        fun workName(reportId: UUID): String = "diagnostic-upload-$reportId"
        fun expiryWorkName(reportId: UUID): String = "diagnostic-upload-expiry-$reportId"
    }
}
