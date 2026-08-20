/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.eigenkodex.navonweb.diagnostics.upload

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Repairs persisted WorkManager class references immediately after a package update. */
internal class DiagnosticNamespaceMigrationWorker(
    applicationContext: Context,
    workerParameters: WorkerParameters,
) : CoroutineWorker(applicationContext, workerParameters) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val recovery = DiagnosticReportUploadManager(applicationContext).restorePendingUploads()
        if (recovery.workMigrationSucceeded && recovery.failedCount == 0) {
            Result.success()
        } else if (runAttemptCount + 1 >= MAX_ATTEMPTS) {
            Result.failure()
        } else {
            Result.retry()
        }
    }

    private companion object {
        const val MAX_ATTEMPTS = 3
    }
}
