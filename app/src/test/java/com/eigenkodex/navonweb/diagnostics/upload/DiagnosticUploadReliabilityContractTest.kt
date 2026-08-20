/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.eigenkodex.navonweb.diagnostics.upload

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticUploadReliabilityContractTest {
    @Test
    fun schedulerWaitsForEnqueueAndCancellationOperations() {
        val source = readSource(
            "main/java/com/eigenkodex/navonweb/diagnostics/upload/DiagnosticUploadScheduler.kt",
        )

        assertTrue(source.contains("operation.result.get(OPERATION_TIMEOUT_SECONDS"))
        assertTrue(source.contains("await(workManager.cancelUniqueWork(workName(reportId)))"))
        assertTrue(source.contains("await(workManager.cancelUniqueWork(expiryWorkName(reportId)))"))
        assertTrue(source.contains("await(workManager.cancelAllWorkByTag(WORK_TAG))"))
        assertTrue(source.contains("await(workManager.cancelAllWorkByTag(EXPIRY_WORK_TAG))"))
        assertTrue(source.contains("getWorkInfosForUniqueWork(workName(reportId))"))
        assertTrue(source.contains("DiagnosticUploadWorker.KEY_PRIOR_ATTEMPTS"))
        assertTrue(source.contains("expiryDelayMillis.coerceIn("))
        assertTrue(source.contains("WORKER_NAMESPACE_MIGRATION"))
    }

    @Test
    fun packageReplacementSchedulesWorkerMigrationAndClearsStaleNotifications() {
        val receiver = readSource(
            "main/java/com/eigenkodex/navonweb/automation/AutomationBootReceiver.kt",
        )
        val scheduler = readSource(
            "main/java/com/eigenkodex/navonweb/diagnostics/upload/DiagnosticUploadScheduler.kt",
        )
        val migrationWorker = readSource(
            "main/java/com/eigenkodex/navonweb/diagnostics/upload/DiagnosticNamespaceMigrationWorker.kt",
        )

        val packageReplacement = receiver.indexOf("Intent.ACTION_MY_PACKAGE_REPLACED")
        val recoverySchedule = receiver.indexOf(
            "DiagnosticUploadScheduler.enqueuePackageUpgradeRecovery(applicationContext)",
        )
        assertTrue(packageReplacement >= 0)
        assertTrue(recoverySchedule > packageReplacement)
        assertTrue(receiver.contains("AndroidAutomationStartFallback.cancelUpgradeStaleNotification("))
        assertTrue(receiver.contains("ProjectionService.cancelUpgradeStaleConnectionAlert(applicationContext)"))
        assertTrue(receiver.contains("val pendingResult = goAsync()"))
        assertTrue(receiver.contains("pendingResult.finish()"))
        assertTrue(scheduler.contains("OneTimeWorkRequestBuilder<DiagnosticNamespaceMigrationWorker>()"))
        assertTrue(scheduler.contains("PACKAGE_UPGRADE_RECOVERY_WORK"))
        assertTrue(scheduler.contains("ExistingWorkPolicy.KEEP"))
        assertTrue(scheduler.contains(".result"))
        assertTrue(scheduler.contains(".get(OPERATION_TIMEOUT_SECONDS, TimeUnit.SECONDS)"))
        assertTrue(migrationWorker.contains("DiagnosticReportUploadManager(applicationContext)"))
        assertTrue(migrationWorker.contains("recovery.workMigrationSucceeded"))
        assertTrue(migrationWorker.contains("Result.retry()"))
        assertTrue(migrationWorker.contains("runAttemptCount + 1 >= MAX_ATTEMPTS"))
    }

    @Test
    fun managerRestoresOnlyExplicitlyApprovedOutboxIdsAndCanCancelAll() {
        val manager = readSource(
            "main/java/com/eigenkodex/navonweb/diagnostics/upload/DiagnosticReportUploadManager.kt",
        )
        val outbox = readSource(
            "main/java/com/eigenkodex/navonweb/diagnostics/upload/DiagnosticReportOutbox.kt",
        )
        val worker = readSource(
            "main/java/com/eigenkodex/navonweb/diagnostics/upload/DiagnosticUploadWorker.kt",
        )

        assertTrue(outbox.contains("fun pendingApprovedReportIds(): List<UUID>"))
        assertTrue(outbox.contains("!report.hasExplicitUploadConsent"))
        assertTrue(outbox.contains("remainingRetentionMillis"))
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
        val activity = readSource("main/java/com/eigenkodex/navonweb/MainActivity.kt")
        val ui = readSource("main/java/com/eigenkodex/navonweb/ui/NavOnWebApp.kt")
        val gate = readSource(
            "main/java/com/eigenkodex/navonweb/diagnostics/upload/DiagnosticUploadConsentGate.kt",
        )
        val manager = readSource(
            "main/java/com/eigenkodex/navonweb/diagnostics/upload/DiagnosticReportUploadManager.kt",
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
        val migrationPreparation = recoverySource.indexOf(
            "scheduler.prepareWorkerNamespaceMigration(reportIds)",
        )
        val recoveryEnqueue = recoverySource.indexOf("scheduler.enqueue(")
        assertTrue(recoverySnapshot >= 0)
        assertTrue(recoveryReconciliation > recoverySnapshot)
        assertTrue(migrationPreparation > recoveryReconciliation)
        assertTrue(recoveryEnqueue > recoveryReconciliation)
        assertTrue(recoverySource.contains("ExistingWorkPolicy.REPLACE"))
        assertTrue(recoverySource.contains("expiryDelayMillis = report.remainingRetentionMillis"))
        assertTrue(recoverySource.contains("priorAttemptCount = priorAttemptCount"))
        assertTrue(recoverySource.contains("scheduler.completeWorkerNamespaceMigration()"))
    }

    @Test
    fun activityRestoresOnStartupAndSettingsExposePendingCancellationAndLastResult() {
        val activity = readSource("main/java/com/eigenkodex/navonweb/MainActivity.kt")
        val ui = readSource("main/java/com/eigenkodex/navonweb/ui/NavOnWebApp.kt")

        assertTrue(activity.contains("restorePendingDiagnosticUploads()"))
        assertTrue(activity.contains("diagnosticUploadManager.restorePendingUploads()"))
        assertTrue(activity.contains("diagnosticUploadManager.cancelAllPending()"))
        assertTrue(ui.contains("R.string.ui_diagnostic_cancel_pending"))
        assertTrue(ui.contains("formatLastDiagnosticUpload(uploadStatus, locale)"))
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
