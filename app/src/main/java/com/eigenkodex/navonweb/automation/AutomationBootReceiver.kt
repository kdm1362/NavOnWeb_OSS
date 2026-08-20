/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.eigenkodex.navonweb.automation

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.eigenkodex.navonweb.diagnostics.upload.DiagnosticUploadScheduler
import com.eigenkodex.navonweb.service.ProjectionService
import java.util.concurrent.Executors

/** Restores user-selected background state and local maintenance; it never starts projection. */
class AutomationBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (
            intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != Intent.ACTION_MY_PACKAGE_REPLACED
        ) {
            return
        }
        HotspotAutomationMonitorService.refresh(context)
        val applicationContext = context.applicationContext
        val pendingResult = goAsync()
        runCatching {
            PACKAGE_MAINTENANCE_EXECUTOR.execute {
                try {
                    if (intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
                        AndroidAutomationStartFallback.cancelUpgradeStaleNotification(
                            applicationContext,
                        )
                        ProjectionService.cancelUpgradeStaleConnectionAlert(applicationContext)
                    }
                    DiagnosticUploadScheduler.enqueuePackageUpgradeRecovery(applicationContext)
                } finally {
                    pendingResult.finish()
                }
            }
        }.onFailure {
            pendingResult.finish()
        }
    }

    private companion object {
        val PACKAGE_MAINTENANCE_EXECUTOR = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "navonweb-package-maintenance").apply { isDaemon = true }
        }
    }
}
