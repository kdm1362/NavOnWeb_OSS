/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.eigenkodex.navonweb.diagnostics.upload

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
    private val applicationContext = context.applicationContext
    private val workManager = WorkManager.getInstance(applicationContext)
    private val migrationPreferences = applicationContext.getSharedPreferences(
        MIGRATION_PREFERENCES,
        Context.MODE_PRIVATE,
    )

    fun enqueue(
        reportId: UUID,
        existingWorkPolicy: ExistingWorkPolicy = ExistingWorkPolicy.KEEP,
        expiryDelayMillis: Long = DiagnosticUploadPolicy.MAX_REPORT_AGE.toMillis(),
        priorAttemptCount: Int = 0,
    ): Boolean = try {
        val reportIdData = workDataOf(
            DiagnosticUploadWorker.KEY_REPORT_ID to reportId.toString(),
            DiagnosticUploadWorker.KEY_PRIOR_ATTEMPTS to priorAttemptCount.coerceIn(
                0,
                DiagnosticUploadPolicy.MAX_ATTEMPTS,
            ),
        )
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
                existingWorkPolicy,
                uploadRequest,
            ),
        )
        if (!uploadEnqueued) return false
        val expiryRequest = OneTimeWorkRequestBuilder<DiagnosticOutboxExpiryWorker>()
            .setInputData(reportIdData)
            .setInitialDelay(
                expiryDelayMillis.coerceIn(
                    0L,
                    DiagnosticUploadPolicy.MAX_REPORT_AGE.toMillis(),
                ),
                TimeUnit.MILLISECONDS,
            )
            .addTag(EXPIRY_WORK_TAG)
            .build()
        val expiryEnqueued = await(
            workManager.enqueueUniqueWork(
                expiryWorkName(reportId),
                existingWorkPolicy,
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

    fun prepareWorkerNamespaceMigration(reportIds: List<UUID>): WorkerNamespaceMigration {
        if (migrationPreferences.getBoolean(WORKER_NAMESPACE_MIGRATION, false)) {
            return WorkerNamespaceMigration.NotRequired
        }
        val priorAttempts = runCatching {
            reportIds.associateWith { reportId ->
                workManager.getWorkInfosForUniqueWork(workName(reportId))
                    .get(OPERATION_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    .maxOfOrNull { it.runAttemptCount }
                    ?.coerceIn(0, DiagnosticUploadPolicy.MAX_ATTEMPTS)
                    ?: 0
            }
        }.getOrElse { return WorkerNamespaceMigration.Failed }
        val uploadsCancelled = runCatching {
            await(workManager.cancelAllWorkByTag(WORK_TAG))
        }.getOrDefault(false)
        val expiriesCancelled = runCatching {
            await(workManager.cancelAllWorkByTag(EXPIRY_WORK_TAG))
        }.getOrDefault(false)
        return if (uploadsCancelled && expiriesCancelled) {
            WorkerNamespaceMigration.Required(priorAttempts)
        } else {
            WorkerNamespaceMigration.Failed
        }
    }

    fun completeWorkerNamespaceMigration(): Boolean = migrationPreferences.edit()
        .putBoolean(WORKER_NAMESPACE_MIGRATION, true)
        .commit()

    private fun await(operation: Operation): Boolean = runCatching {
        operation.result.get(OPERATION_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        true
    }.getOrDefault(false)

    internal companion object {
        const val WORK_TAG = "diagnostic-upload"
        const val EXPIRY_WORK_TAG = "diagnostic-upload-expiry"
        const val OPERATION_TIMEOUT_SECONDS = 10L
        private const val PACKAGE_RECOVERY_TIMEOUT_SECONDS = 8L
        private const val MIGRATION_PREFERENCES = "diagnostic-worker-migrations"
        private const val WORKER_NAMESPACE_MIGRATION = "navonweb-worker-namespace-v1"
        private const val PACKAGE_UPGRADE_RECOVERY_WORK =
            "diagnostic-worker-namespace-recovery-v1"
        fun workName(reportId: UUID): String = "diagnostic-upload-$reportId"
        fun expiryWorkName(reportId: UUID): String = "diagnostic-upload-expiry-$reportId"

        fun enqueuePackageUpgradeRecovery(context: Context): Boolean = runCatching {
            val request = OneTimeWorkRequestBuilder<DiagnosticNamespaceMigrationWorker>()
                .setBackoffCriteria(
                    BackoffPolicy.LINEAR,
                    DiagnosticUploadPolicy.RETRY_BACKOFF.toMinutes(),
                    TimeUnit.MINUTES,
                )
                .build()
            WorkManager.getInstance(context.applicationContext)
                .enqueueUniqueWork(
                    PACKAGE_UPGRADE_RECOVERY_WORK,
                    ExistingWorkPolicy.KEEP,
                    request,
                )
                .result
                .get(PACKAGE_RECOVERY_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            true
        }.getOrDefault(false)
    }
}

internal sealed interface WorkerNamespaceMigration {
    data object NotRequired : WorkerNamespaceMigration

    data class Required(val priorAttempts: Map<UUID, Int>) : WorkerNamespaceMigration

    data object Failed : WorkerNamespaceMigration
}
