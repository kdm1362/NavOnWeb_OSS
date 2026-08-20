/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.eigenkodex.navonweb.diagnostics.upload

import android.content.Context
import com.eigenkodex.navonweb.diagnostics.AppDiagnostics
import java.io.File
import java.io.FileOutputStream
import java.time.Duration
import java.util.UUID

internal class DiagnosticReportOutbox(
    context: Context,
    private val clockMillis: () -> Long = System::currentTimeMillis,
    private val localeProvider: () -> String = DiagnosticClientLocale::current,
) {
    private val directory = File(context.applicationContext.noBackupFilesDir, DIRECTORY_NAME)

    /** Call from an IO dispatcher. It snapshots the already-redacted AppDiagnostics export. */
    fun create(approval: DiagnosticUploadApproval): CreateDiagnosticReportResult {
        val exportedText = runCatching(AppDiagnostics::exportText)
            .getOrElse { return CreateDiagnosticReportResult.PersistenceFailed }
        return createFromExportedText(approval, exportedText)
    }

    internal fun createFromExportedText(
        approval: DiagnosticUploadApproval,
        exportedText: String,
    ): CreateDiagnosticReportResult = synchronized(OUTBOX_LOCK) {
        val description = when (val validation =
            DiagnosticUploadPolicy.normalizeDescription(approval.userDescription)
        ) {
            is DescriptionValidation.Valid -> validation.value
            is DescriptionValidation.Invalid -> {
                return@synchronized CreateDiagnosticReportResult.InvalidDescription(validation.reason)
            }
        }
        val snapshot = DiagnosticLogSnapshotter.newestCompleteRecords(exportedText)
        if (snapshot.logText.isEmpty()) {
            return@synchronized CreateDiagnosticReportResult.NoDiagnosticLogs
        }
        if (!ensureDirectory()) return@synchronized CreateDiagnosticReportResult.PersistenceFailed
        pruneLocked()
        if (reportFilesLocked().size >= DiagnosticUploadPolicy.MAX_OUTBOX_REPORTS) {
            return@synchronized CreateDiagnosticReportResult.OutboxFull
        }

        val report = DiagnosticReport(
            id = UUID.randomUUID(),
            occurredAtEpochMillis = maxOf(clockMillis(), approval.approvedAtEpochMillis),
            userConsentVersion = approval.consentVersion,
            userApprovedAtEpochMillis = approval.approvedAtEpochMillis,
            userDescription = description,
            snapshot = snapshot,
            clientLocale = DiagnosticClientLocale.normalize(localeProvider()),
        )
        val encoded = runCatching { DiagnosticReportCodec.encode(report) }
            .getOrElse { return@synchronized CreateDiagnosticReportResult.PersistenceFailed }
        val destination = reportFile(report.id)
        val temporary = File(directory, ".${report.id}$TEMPORARY_SUFFIX")
        val persisted = runCatching {
            FileOutputStream(temporary).use { output ->
                output.write(encoded)
                output.flush()
                output.fd.sync()
            }
            if (!temporary.renameTo(destination)) error("atomic report rename failed")
        }.isSuccess
        if (!persisted) {
            runCatching { temporary.delete() }
            return@synchronized CreateDiagnosticReportResult.PersistenceFailed
        }
        CreateDiagnosticReportResult.Created(report)
    }

    fun load(reportId: UUID): DiagnosticReport? = synchronized(OUTBOX_LOCK) {
        if (!ensureDirectory()) return@synchronized null
        val file = reportFile(reportId)
        decodeFileLocked(file)
            ?.takeIf(DiagnosticReport::hasExplicitUploadConsent)
            .also { approvedReport ->
                if (approvedReport == null && file.exists()) runCatching(file::delete)
            }
    }

    fun remove(reportId: UUID): Boolean = synchronized(OUTBOX_LOCK) {
        val file = reportFile(reportId)
        !file.exists() || runCatching(file::delete).getOrDefault(false)
    }

    fun pendingCount(): Int = synchronized(OUTBOX_LOCK) {
        if (!ensureDirectory()) return@synchronized 0
        pruneLocked()
        reportFilesLocked().size
    }

    /** Only reports carrying the current persisted consent marker may be scheduled. */
    fun approvedSnapshot(): DiagnosticApprovedOutboxSnapshot = synchronized(OUTBOX_LOCK) {
        if (!ensureDirectory()) return@synchronized DiagnosticApprovedOutboxSnapshot.EMPTY
        pruneLocked()
        val approvedReports = reportFilesLocked()
            .mapNotNull(::decodeFileLocked)
            .filter(DiagnosticReport::hasExplicitUploadConsent)
        val nowEpochMillis = clockMillis()
        DiagnosticApprovedOutboxSnapshot(
            reports = approvedReports
                .map { report ->
                    DiagnosticApprovedOutboxReport(
                        id = report.id,
                        remainingRetentionMillis = DiagnosticUploadPolicy.remainingReportLifetime(
                            occurredAtEpochMillis = report.occurredAtEpochMillis,
                            nowEpochMillis = nowEpochMillis,
                        ).toMillis(),
                    )
                }
                .sortedBy { it.id.toString() },
            latestApprovedAtEpochMillis = approvedReports
                .mapNotNull(DiagnosticReport::userApprovedAtEpochMillis)
                .maxOrNull(),
        )
    }

    fun pendingApprovedReportIds(): List<UUID> = approvedSnapshot().reportIds

    private fun ensureDirectory(): Boolean =
        directory.isDirectory || (!directory.exists() && directory.mkdirs())

    private fun pruneLocked() {
        directory.listFiles()
            .orEmpty()
            .filter { it.name.endsWith(TEMPORARY_SUFFIX) }
            .forEach { runCatching(it::delete) }
        val now = clockMillis()
        reportFilesLocked().forEach { file ->
            val report = decodeFileLocked(file)
            val expired = report == null ||
                !report.hasExplicitUploadConsent ||
                (now >= report.occurredAtEpochMillis &&
                    Duration.ofMillis(now - report.occurredAtEpochMillis) >=
                    DiagnosticUploadPolicy.MAX_REPORT_AGE)
            if (expired) runCatching(file::delete)
        }
    }

    private fun decodeFileLocked(file: File): DiagnosticReport? {
        if (!file.isFile || file.length() <= 0L ||
            file.length() > DiagnosticReportCodec.MAX_ENCODED_BYTES.toLong()
        ) {
            return null
        }
        return runCatching { DiagnosticReportCodec.decode(file.readBytes()) }
            .getOrNull()
            ?.takeIf { reportFile(it.id).name == file.name }
    }

    private fun reportFilesLocked(): List<File> = directory.listFiles()
        .orEmpty()
        .filter { file ->
            file.isFile && REPORT_FILE_NAME.matches(file.name)
        }

    private fun reportFile(reportId: UUID): File = File(directory, "$reportId$REPORT_SUFFIX")

    private companion object {
        val OUTBOX_LOCK = Any()
        const val DIRECTORY_NAME = "diagnostic-upload-outbox"
        const val REPORT_SUFFIX = ".report"
        const val TEMPORARY_SUFFIX = ".tmp"
        val REPORT_FILE_NAME = Regex(
            "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.report",
        )
    }
}

internal data class DiagnosticApprovedOutboxSnapshot(
    val reports: List<DiagnosticApprovedOutboxReport>,
    val latestApprovedAtEpochMillis: Long?,
) {
    val reportIds: List<UUID>
        get() = reports.map(DiagnosticApprovedOutboxReport::id)

    companion object {
        val EMPTY = DiagnosticApprovedOutboxSnapshot(emptyList(), null)
    }
}

internal data class DiagnosticApprovedOutboxReport(
    val id: UUID,
    val remainingRetentionMillis: Long,
)
