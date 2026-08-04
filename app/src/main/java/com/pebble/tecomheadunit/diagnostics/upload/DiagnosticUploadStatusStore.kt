/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.pebble.tecomheadunit.diagnostics.upload

import android.content.Context

enum class DiagnosticUploadTerminalOutcome {
    SUCCESS,
    PERMANENT_FAILURE,
}

data class DiagnosticUploadStatusSnapshot(
    val pendingCount: Int,
    val lastOutcome: DiagnosticUploadTerminalOutcome?,
    val lastFailure: WorkerFailure?,
    val lastCompletedAtEpochMillis: Long?,
    val lastUserApprovedAtEpochMillis: Long?,
) {
    companion object {
        val EMPTY = DiagnosticUploadStatusSnapshot(0, null, null, null, null)
    }
}

internal class DiagnosticUploadStatusStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE,
    )

    fun recordSuccess(nowEpochMillis: Long = System.currentTimeMillis()) {
        record(DiagnosticUploadTerminalOutcome.SUCCESS, null, nowEpochMillis)
    }

    fun recordPermanentFailure(
        failure: WorkerFailure,
        nowEpochMillis: Long = System.currentTimeMillis(),
    ) {
        record(DiagnosticUploadTerminalOutcome.PERMANENT_FAILURE, failure, nowEpochMillis)
    }

    fun userApprovalCheckpoint(): DiagnosticUserApprovalCheckpoint = synchronized(STATUS_LOCK) {
        readUserApprovalCheckpointLocked()
    }

    fun recordUserApprovedSubmission(
        approvedAtEpochMillis: Long,
        reconciledCheckpoint: DiagnosticUserApprovalCheckpoint,
    ): Boolean =
        restoreUserApprovedSubmission(
            DiagnosticUserApprovalCheckpoint(
                approvedAtEpochMillis = approvedAtEpochMillis.coerceAtLeast(1L),
                futureEvidenceEpochMillis = reconciledCheckpoint.futureEvidenceEpochMillis,
                futureEvidenceRebasedAtEpochMillis =
                    reconciledCheckpoint.futureEvidenceRebasedAtEpochMillis,
            ),
        )

    fun reconcileUserApprovedSubmission(
        pendingApprovedAtEpochMillis: Long?,
        nowEpochMillis: Long,
    ): Boolean = synchronized(STATUS_LOCK) {
        val stored = readUserApprovalCheckpointLocked()
        val reconciled = DiagnosticUploadPolicy.reconcileUserApprovalClock(
            storedApprovedAtEpochMillis = stored.approvedAtEpochMillis,
            pendingApprovedAtEpochMillis = pendingApprovedAtEpochMillis,
            storedFutureEvidenceEpochMillis = stored.futureEvidenceEpochMillis,
            storedFutureEvidenceRebasedAtEpochMillis =
                stored.futureEvidenceRebasedAtEpochMillis,
            nowEpochMillis = nowEpochMillis,
        )
        val target = DiagnosticUserApprovalCheckpoint(
            approvedAtEpochMillis = reconciled.effectiveApprovedAtEpochMillis,
            futureEvidenceEpochMillis = reconciled.futureEvidenceEpochMillis,
            futureEvidenceRebasedAtEpochMillis =
                reconciled.futureEvidenceRebasedAtEpochMillis,
        )
        if (target == stored) {
            true
        } else {
            writeUserApprovalCheckpointLocked(target)
        }
    }

    fun restoreUserApprovedSubmission(checkpoint: DiagnosticUserApprovalCheckpoint): Boolean =
        synchronized(STATUS_LOCK) {
            writeUserApprovalCheckpointLocked(checkpoint)
        }

    fun snapshot(pendingCount: Int): DiagnosticUploadStatusSnapshot = synchronized(STATUS_LOCK) {
        val outcome = preferences.getString(KEY_OUTCOME, null)
            ?.let { stored -> DiagnosticUploadTerminalOutcome.entries.find { it.name == stored } }
        val failure = preferences.getString(KEY_FAILURE, null)
            ?.let { stored -> WorkerFailure.entries.find { it.name == stored } }
        val completedAt = preferences.getLong(KEY_COMPLETED_AT, 0L).takeIf { it > 0L }
        val approvedAt = preferences.getLong(KEY_USER_APPROVED_AT, 0L).takeIf { it > 0L }
        DiagnosticUploadStatusSnapshot(
            pendingCount = pendingCount.coerceAtLeast(0),
            lastOutcome = outcome,
            lastFailure = failure.takeIf {
                outcome == DiagnosticUploadTerminalOutcome.PERMANENT_FAILURE
            },
            lastCompletedAtEpochMillis = completedAt.takeIf { outcome != null },
            lastUserApprovedAtEpochMillis = approvedAt,
        )
    }

    private fun record(
        outcome: DiagnosticUploadTerminalOutcome,
        failure: WorkerFailure?,
        nowEpochMillis: Long,
    ) = synchronized(STATUS_LOCK) {
        preferences.edit()
            .putString(KEY_OUTCOME, outcome.name)
            .putLong(KEY_COMPLETED_AT, nowEpochMillis.coerceAtLeast(1L))
            .apply {
                if (failure == null) remove(KEY_FAILURE) else putString(KEY_FAILURE, failure.name)
            }
            .commit()
    }

    private fun readUserApprovalCheckpointLocked(): DiagnosticUserApprovalCheckpoint =
        DiagnosticUserApprovalCheckpoint(
            approvedAtEpochMillis = preferences.getLong(KEY_USER_APPROVED_AT, 0L)
                .takeIf { it > 0L },
            futureEvidenceEpochMillis = preferences.getLong(KEY_FUTURE_EVIDENCE, 0L)
                .takeIf { it > 0L },
            futureEvidenceRebasedAtEpochMillis = preferences.getLong(
                KEY_FUTURE_EVIDENCE_REBASED_AT,
                0L,
            ).takeIf { it > 0L },
        )

    private fun writeUserApprovalCheckpointLocked(
        checkpoint: DiagnosticUserApprovalCheckpoint,
    ): Boolean = preferences.edit().apply {
        fun putOrRemove(key: String, value: Long?) {
            if (value == null) remove(key) else putLong(key, value.coerceAtLeast(1L))
        }
        putOrRemove(KEY_USER_APPROVED_AT, checkpoint.approvedAtEpochMillis)
        putOrRemove(KEY_FUTURE_EVIDENCE, checkpoint.futureEvidenceEpochMillis)
        putOrRemove(
            KEY_FUTURE_EVIDENCE_REBASED_AT,
            checkpoint.futureEvidenceRebasedAtEpochMillis,
        )
    }.commit()

    private companion object {
        val STATUS_LOCK = Any()
        const val PREFERENCES_NAME = "navonweb_diagnostic_upload_status"
        const val KEY_OUTCOME = "last_outcome"
        const val KEY_FAILURE = "last_failure"
        const val KEY_COMPLETED_AT = "last_completed_at"
        const val KEY_USER_APPROVED_AT = "last_user_approved_at"
        const val KEY_FUTURE_EVIDENCE = "future_user_approval_evidence"
        const val KEY_FUTURE_EVIDENCE_REBASED_AT = "future_user_approval_rebased_at"
    }
}

internal data class DiagnosticUserApprovalCheckpoint(
    val approvedAtEpochMillis: Long?,
    val futureEvidenceEpochMillis: Long?,
    val futureEvidenceRebasedAtEpochMillis: Long?,
) {
    companion object {
        val EMPTY = DiagnosticUserApprovalCheckpoint(null, null, null)
    }
}
