/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.pebble.tecomheadunit.diagnostics.upload

import java.util.UUID

internal data class DiagnosticReport(
    val id: UUID,
    val occurredAtEpochMillis: Long,
    /** Zero for legacy reports that predate the explicit consent dialog. */
    val userConsentVersion: Int,
    /** Null for legacy reports that have no persisted explicit approval marker. */
    val userApprovedAtEpochMillis: Long?,
    val userDescription: String,
    val snapshot: DiagnosticLogSnapshot,
    /** BCP-47 UI locale captured when the report was created; `und` means unknown. */
    val clientLocale: String = DiagnosticClientLocale.UNKNOWN,
) {
    val hasExplicitUploadConsent: Boolean
        get() = userConsentVersion == DIAGNOSTIC_UPLOAD_CONSENT_VERSION &&
            userApprovedAtEpochMillis != null && userApprovedAtEpochMillis > 0L
}

/**
 * Capability passed to the outbox only after the UI's final consent confirmation.
 * Merely opening or cancelling either dialog cannot construct or persist a report.
 */
internal class DiagnosticUploadApproval private constructor(
    val userDescription: String,
    val consentVersion: Int,
    val approvedAtEpochMillis: Long,
) {
    companion object {
        fun confirmedByUser(
            userDescription: String,
            approvedAtEpochMillis: Long = System.currentTimeMillis(),
        ): DiagnosticUploadApproval = DiagnosticUploadApproval(
            userDescription = userDescription,
            consentVersion = DIAGNOSTIC_UPLOAD_CONSENT_VERSION,
            approvedAtEpochMillis = approvedAtEpochMillis.coerceAtLeast(1L),
        )
    }
}

internal sealed interface CreateDiagnosticReportResult {
    data class Created(val report: DiagnosticReport) : CreateDiagnosticReportResult

    data class InvalidDescription(val reason: DescriptionFailure) : CreateDiagnosticReportResult

    data object NoDiagnosticLogs : CreateDiagnosticReportResult

    data object OutboxFull : CreateDiagnosticReportResult

    data object PersistenceFailed : CreateDiagnosticReportResult
}
