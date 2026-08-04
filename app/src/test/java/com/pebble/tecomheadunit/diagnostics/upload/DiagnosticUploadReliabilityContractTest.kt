/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.pebble.tecomheadunit.diagnostics.upload

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticUploadReliabilityContractTest {
    @Test
    fun schedulerWaitsForEnqueueAndCancellationOperations() {
        val source = readSource(
            "main/java/com/pebble/tecomheadunit/diagnostics/upload/DiagnosticUploadScheduler.kt",
        )

        assertTrue(source.contains("operation.result.get(OPERATION_TIMEOUT_SECONDS"))
        assertTrue(source.contains("await(workManager.cancelUniqueWork(workName(reportId)))"))
        assertTrue(source.contains("await(workManager.cancelUniqueWork(expiryWorkName(reportId)))"))
    }

    @Test
    fun managerRestoresOnlyExplicitlyApprovedOutboxIdsAndCanCancelAll() {
        val manager = readSource(
            "main/java/com/pebble/tecomheadunit/diagnostics/upload/DiagnosticReportUploadManager.kt",
        )
        val outbox = readSource(
            "main/java/com/pebble/tecomheadunit/diagnostics/upload/DiagnosticReportOutbox.kt",
        )
        val worker = readSource(
            "main/java/com/pebble/tecomheadunit/diagnostics/upload/DiagnosticUploadWorker.kt",
        )

        assertTrue(outbox.contains("fun pendingApprovedReportIds(): List<UUID>"))
        assertTrue(outbox.contains("!report.hasExplicitUploadConsent"))
        assertTrue(manager.contains("fun restorePendingUploads(): DiagnosticUploadRecoveryResult"))
        assertTrue(manager.contains("outbox.approvedSnapshot()"))
        val workerLoadsOutbox = worker.indexOf("val report = outbox.load(reportId)")
        val workerCreatesNetworkConfig = worker.indexOf("SupabaseDiagnosticConfig.fromBuildConfig()")
        assertTrue(workerLoadsOutbox >= 0)
        assertTrue(workerCreatesNetworkConfig > workerLoadsOutbox)
        assertTrue(manager.contains("reportIds.forEach { reportId"))
        assertTrue(manager.contains("fun cancelAllPending(): DiagnosticUploadCancellationResult"))
        val cancelStart = manager.indexOf("fun cancel(reportId: UUID): Boolean")
        val schedulerCancel = manager.indexOf("scheduler.cancel(reportId)", cancelStart)
        val payloadRemoval = manager.indexOf("outbox.remove(reportId)", cancelStart)
        assertTrue(schedulerCancel > cancelStart)
        assertTrue(payloadRemoval > schedulerCancel)
        assertFalse(
            manager.substring(cancelStart, payloadRemoval).contains("&&"),
        )
    }

    @Test
    fun submissionRequiresFinalConsentAndPersistsRateLimitBeforeScheduling() {
        val activity = readSource("main/java/com/pebble/tecomheadunit/MainActivity.kt")
        val ui = readSource("main/java/com/pebble/tecomheadunit/ui/TecomHeadUnitApp.kt")
        val gate = readSource(
            "main/java/com/pebble/tecomheadunit/diagnostics/upload/DiagnosticUploadConsentGate.kt",
        )
        val manager = readSource(
            "main/java/com/pebble/tecomheadunit/diagnostics/upload/DiagnosticReportUploadManager.kt",
        )

        assertTrue(
            gate.contains(
                "개발자에게 진단데이터를 전송합니다. 진단 데이터 확인 및 분석에 동의하신다면 계속해 주세요",
            ),
        )
        assertTrue(ui.contains("uploadConsentGate.requestConfirmation(uploadDescription)"))
        assertTrue(ui.contains("uploadConsentGate.cancel()"))
        assertTrue(ui.contains("uploadConsentGate.confirm()"))
        assertTrue(activity.contains("DiagnosticUploadApproval.confirmedByUser(userDescription)"))
        assertTrue(activity.contains("diagnosticUploadManager.submitApproved(approval)"))
        assertFalse(manager.contains("fun submit(userDescription: String)"))
        assertTrue(manager.contains("fun submitApproved(approval: DiagnosticUploadApproval)"))
        val submitStart = manager.indexOf("fun submitApproved(")
        val submitEnd = manager.indexOf("fun pendingCount()", submitStart)
        val submitSource = manager.substring(submitStart, submitEnd)
        val pendingEvidence = submitSource.indexOf("outbox.approvedSnapshot()")
        val evidenceReconciliation = submitSource.indexOf(
            "statusStore.reconcileUserApprovedSubmission(",
        )
        val rateLimitCheck = submitSource.indexOf("userApprovalRateLimitRemaining(")
        val outboxCreation = submitSource.indexOf("outbox.create(approval)")
        val persistApproval = submitSource.indexOf("statusStore.recordUserApprovedSubmission(")
        val enqueue = submitSource.indexOf("scheduler.enqueue(created.report.id)")
        assertTrue(pendingEvidence >= 0)
        assertTrue(evidenceReconciliation > pendingEvidence)
        assertTrue(rateLimitCheck > evidenceReconciliation)
        assertTrue(rateLimitCheck >= 0)
        assertTrue(outboxCreation > rateLimitCheck)
        assertTrue(persistApproval >= 0)
        assertTrue(enqueue > persistApproval)
        assertTrue(submitSource.contains("val approvalRestored ="))
        assertTrue(submitSource.contains("if (approvalRestored && reportRemoved)"))

        val recoveryStart = manager.indexOf("fun restorePendingUploads()")
        val recoveryEnd = manager.indexOf("fun cancel(reportId", recoveryStart)
        val recoverySource = manager.substring(recoveryStart, recoveryEnd)
        val recoverySnapshot = recoverySource.indexOf("outbox.approvedSnapshot()")
        val recoveryReconciliation = recoverySource.indexOf(
            "statusStore.reconcileUserApprovedSubmission(",
        )
        val recoveryEnqueue = recoverySource.indexOf("scheduler.enqueue(reportId)")
        assertTrue(recoverySnapshot >= 0)
        assertTrue(recoveryReconciliation > recoverySnapshot)
        assertTrue(recoveryEnqueue > recoveryReconciliation)
    }

    @Test
    fun activityRestoresOnStartupAndSettingsExposePendingCancellationAndLastResult() {
        val activity = readSource("main/java/com/pebble/tecomheadunit/MainActivity.kt")
        val ui = readSource("main/java/com/pebble/tecomheadunit/ui/TecomHeadUnitApp.kt")

        assertTrue(activity.contains("restorePendingDiagnosticUploads()"))
        assertTrue(activity.contains("diagnosticUploadManager.restorePendingUploads()"))
        assertTrue(activity.contains("diagnosticUploadManager.cancelAllPending()"))
        assertTrue(ui.contains("대기 전송 모두 취소·삭제"))
        assertTrue(ui.contains("formatLastDiagnosticUpload(uploadStatus)"))
    }

    private fun readSource(relativePath: String): String {
        val candidates = listOf(
            File("app/src/$relativePath"),
            File("src/$relativePath"),
        )
        val source = candidates.firstOrNull(File::isFile)
            ?: error("missing source $relativePath")
        return source.readText(Charsets.UTF_8)
    }
}
