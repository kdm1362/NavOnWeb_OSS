/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.eigenkodex.navonweb.diagnostics.upload

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Best-effort privacy cleanup that does not wait for a network constraint. */
internal class DiagnosticOutboxExpiryWorker(
    applicationContext: Context,
    workerParameters: WorkerParameters,
) : CoroutineWorker(applicationContext, workerParameters) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val reportId = inputData.getString(DiagnosticUploadWorker.KEY_REPORT_ID)
            ?.let { value -> runCatching { UUID.fromString(value) }.getOrNull() }
            ?: return@withContext Result.failure()
        val outbox = DiagnosticReportOutbox(applicationContext)
        if (outbox.load(reportId) != null && outbox.remove(reportId)) {
            DiagnosticUploadStatusStore(applicationContext).recordPermanentFailure(
                WorkerFailure.EXPIRED_OR_ATTEMPTS_EXHAUSTED,
            )
        }
        Result.success()
    }
}
