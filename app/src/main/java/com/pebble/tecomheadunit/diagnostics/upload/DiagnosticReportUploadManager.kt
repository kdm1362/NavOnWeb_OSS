/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.pebble.tecomheadunit.diagnostics.upload

import android.content.Context
import java.time.Duration
import java.util.UUID

/** UI-facing facade. Call [submitApproved] from an IO dispatcher because it snapshots file logs. */
internal class DiagnosticReportUploadManager(
    context: Context,
    private val clockMillis: () -> Long = System::currentTimeMillis,
) {
    private val outbox = DiagnosticReportOutbox(context)
    private val scheduler = DiagnosticUploadScheduler(context)
    private val statusStore = DiagnosticUploadStatusStore(context)

    fun submitApproved(approval: DiagnosticUploadApproval): DiagnosticUploadSubmissionResult =
        synchronized(SUBMISSION_LOCK) {
            val acceptedAtEpochMillis = clockMillis().coerceAtLeast(1L)
            val pendingApprovalEvidence = outbox.approvedSnapshot().latestApprovedAtEpochMillis
            if (!statusStore.reconcileUserApprovedSubmission(
                    pendingApprovedAtEpochMillis = pendingApprovalEvidence,
                    nowEpochMillis = acceptedAtEpochMillis,
                )
            ) {
                return@synchronized DiagnosticUploadSubmissionResult.PersistenceFailed
            }
            val previousApproval = statusStore.userApprovalCheckpoint()
            DiagnosticUploadPolicy.userApprovalRateLimitRemaining(
                lastApprovedAtEpochMillis = previousApproval.approvedAtEpochMillis,
                nowEpochMillis = acceptedAtEpochMillis,
            )?.let { remaining ->
                return@synchronized DiagnosticUploadSubmissionResult.RateLimited(remaining)
            }

            when (val created = outbox.create(approval)) {
                is CreateDiagnosticReportResult.Created -> {
                    val approvalPersisted = statusStore.recordUserApprovedSubmission(
                        acceptedAtEpochMillis,
                        previousApproval,
                    )
                    if (!approvalPersisted) {
                        outbox.remove(created.report.id)
                        DiagnosticUploadSubmissionResult.PersistenceFailed
                    } else {
                        if (scheduler.enqueue(created.report.id)) {
                            DiagnosticUploadSubmissionResult.Queued(created.report.id)
                        } else {
                            val approvalRestored = statusStore.restoreUserApprovedSubmission(
                                previousApproval,
                            )
                            val reportRemoved = outbox.remove(created.report.id)
                            if (approvalRestored && reportRemoved) {
                                DiagnosticUploadSubmissionResult.SchedulingFailed
                            } else {
                                DiagnosticUploadSubmissionResult.PersistenceFailed
                            }
                        }
                    }
                }
                is CreateDiagnosticReportResult.InvalidDescription -> {
                    DiagnosticUploadSubmissionResult.InvalidDescription(created.reason)
                }
                CreateDiagnosticReportResult.NoDiagnosticLogs -> {
                    DiagnosticUploadSubmissionResult.NoDiagnosticLogs
                }
                CreateDiagnosticReportResult.OutboxFull -> {
                    DiagnosticUploadSubmissionResult.OutboxFull
                }
                CreateDiagnosticReportResult.PersistenceFailed -> {
                    DiagnosticUploadSubmissionResult.PersistenceFailed
                }
            }
        }

    fun pendingCount(): Int = outbox.pendingCount()

    fun status(): DiagnosticUploadStatusSnapshot = statusStore.snapshot(outbox.pendingCount())

    /** Re-establishes unique WorkManager work for every valid app-private outbox report. */
    fun restorePendingUploads(): DiagnosticUploadRecoveryResult {
        val approvedSnapshot = outbox.approvedSnapshot()
        val reportIds = approvedSnapshot.reportIds
        if (!statusStore.reconcileUserApprovedSubmission(
                pendingApprovedAtEpochMillis = approvedSnapshot.latestApprovedAtEpochMillis,
                nowEpochMillis = clockMillis().coerceAtLeast(1L),
            )
        ) {
            return DiagnosticUploadRecoveryResult(
                pendingCount = reportIds.size,
                ensuredCount = 0,
                failedCount = reportIds.size,
            )
        }
        var ensuredCount = 0
        reportIds.forEach { reportId ->
            if (scheduler.enqueue(reportId)) ensuredCount += 1
        }
        return DiagnosticUploadRecoveryResult(
            pendingCount = reportIds.size,
            ensuredCount = ensuredCount,
            failedCount = reportIds.size - ensuredCount,
        )
    }

    fun cancel(reportId: UUID): Boolean {
        // Privacy takes precedence over WorkManager bookkeeping: even if cancellation cannot be
        // confirmed, removing the app-private payload makes any surviving worker a safe no-op.
        scheduler.cancel(reportId)
        return outbox.remove(reportId)
    }

    fun cancelAllPending(): DiagnosticUploadCancellationResult {
        val reportIds = outbox.approvedSnapshot().reportIds
        var removedCount = 0
        reportIds.forEach { reportId ->
            if (cancel(reportId)) removedCount += 1
        }
        return DiagnosticUploadCancellationResult(
            requestedCount = reportIds.size,
            removedCount = removedCount,
            failedCount = reportIds.size - removedCount,
        )
    }

    private companion object {
        val SUBMISSION_LOCK = Any()
    }
}

internal data class DiagnosticUploadRecoveryResult(
    val pendingCount: Int,
    val ensuredCount: Int,
    val failedCount: Int,
)

internal data class DiagnosticUploadCancellationResult(
    val requestedCount: Int,
    val removedCount: Int,
    val failedCount: Int,
)

internal sealed interface DiagnosticUploadSubmissionResult {
    data class Queued(val reportId: UUID) : DiagnosticUploadSubmissionResult

    data class InvalidDescription(val reason: DescriptionFailure) :
        DiagnosticUploadSubmissionResult

    data object NoDiagnosticLogs : DiagnosticUploadSubmissionResult

    data object OutboxFull : DiagnosticUploadSubmissionResult

    data class RateLimited(val retryAfter: Duration) : DiagnosticUploadSubmissionResult

    data object PersistenceFailed : DiagnosticUploadSubmissionResult

    data object SchedulingFailed : DiagnosticUploadSubmissionResult
}
