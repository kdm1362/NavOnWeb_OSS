/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.eigenkodex.navonweb.automation

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.eigenkodex.navonweb.MainActivity
import com.eigenkodex.navonweb.R
import com.eigenkodex.navonweb.billing.PremiumAccessGate
import com.eigenkodex.navonweb.notification.NavOnWebNotificationAssets
import com.eigenkodex.navonweb.service.ProjectionService

/**
 * Keeps Android 16's public tethering callback registered while hotspot automation is selected.
 *
 * This is an event listener, not a poller. The persistent low-priority notification is required
 * because a normal application process cannot otherwise be awakened by a hotspot state change.
 */
class HotspotAutomationMonitorService : Service() {
    private var source: HotspotAutomationSource? = null
    private var monitorGeneration: Long? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_DISABLE_HOTSPOT_AUTOMATION) {
            disableHotspotAutomation()
            return START_NOT_STICKY
        }
        if (!PremiumAccessGate.isPremium(applicationContext)) {
            stopMonitoringAndSelf()
            return START_NOT_STICKY
        }
        val controller = AutomationServiceControllerProvider.get(applicationContext)
        val state = controller.snapshot()
        if (state.mode != AutomationTriggerMode.HOTSPOT || Build.VERSION.SDK_INT < 36) {
            stopMonitoringAndSelf()
            return START_NOT_STICKY
        }

        promoteToForeground(waitingForBaseline = source == null)
        if (source != null && monitorGeneration == state.generation) return START_STICKY

        source?.stop()
        monitorGeneration = state.generation
        val newSource = HotspotAutomationSource(
            context = applicationContext,
            controller = controller,
        ) {
            promoteToForeground(waitingForBaseline = false)
        }
        source = newSource
        when (val result = newSource.startIfSelected()) {
            is HotspotAutomationSourceStartResult.NotSelected -> stopMonitoringAndSelf()
            is HotspotAutomationSourceStartResult.Monitoring -> when (result.monitorResult) {
                is HotspotMonitorStartResult.Started,
                is HotspotMonitorStartResult.AlreadyStarted,
                -> Unit
                is HotspotMonitorStartResult.Unsupported,
                is HotspotMonitorStartResult.Failed,
                -> stopMonitoringAndSelf()
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        source?.stop()
        source = null
        monitorGeneration = null
        super.onDestroy()
    }

    private fun promoteToForeground(waitingForBaseline: Boolean) {
        val notificationManager = getSystemService(NotificationManager::class.java) ?: return
        notificationManager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.hotspot_automation_channel),
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
        val openSettings = PendingIntent.getActivity(
            this,
            NOTIFICATION_ID,
            Intent(this, MainActivity::class.java)
                .setAction(ACTION_OPEN_AUTOMATION_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val disableAutomation = PendingIntent.getService(
            this,
            DISABLE_AUTOMATION_REQUEST_CODE,
            Intent(this, HotspotAutomationMonitorService::class.java)
                .setAction(ACTION_DISABLE_HOTSPOT_AUTOMATION),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val text = if (waitingForBaseline) {
            getString(R.string.hotspot_automation_waiting)
        } else {
            getString(R.string.hotspot_automation_monitoring)
        }
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(NavOnWebNotificationAssets.smallIcon)
            .setContentTitle(getString(R.string.hotspot_automation_title))
            .setContentText(text)
            .setContentIntent(openSettings)
            .addAction(
                NavOnWebNotificationAssets.smallIcon,
                getString(R.string.hotspot_automation_disable_action),
                disableAutomation,
            )
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            notification,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            } else {
                0
            },
        )
    }

    private fun disableHotspotAutomation() {
        val result = AutomationServiceControllerProvider.get(applicationContext)
            .disableModeIfSelected(AutomationTriggerMode.HOTSPOT)
        if (result is AutomationDispatchResult.ModeDisabled) {
            ProjectionService.stopIfStartedByAutomation(
                context = applicationContext,
                owner = AutomationProjectionOwner(
                    source = result.disabledSource,
                    generation = result.disabledGeneration,
                ),
            )
        }
        stopMonitoringAndSelf()
    }

    private fun stopMonitoringAndSelf() {
        source?.stop()
        source = null
        monitorGeneration = null
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    companion object {
        const val ACTION_OPEN_AUTOMATION_SETTINGS =
            "com.eigenkodex.navonweb.action.OPEN_AUTOMATION_SETTINGS"
        const val ACTION_DISABLE_HOTSPOT_AUTOMATION =
            "com.eigenkodex.navonweb.action.DISABLE_HOTSPOT_AUTOMATION"
        private const val CHANNEL_ID = "hotspot_automation_monitor"
        private const val NOTIFICATION_ID = 5282
        private const val DISABLE_AUTOMATION_REQUEST_CODE = 5283

        /** Applies the persisted mutually-exclusive mode after a user setting change. */
        fun refresh(context: Context): Boolean {
            val applicationContext = context.applicationContext
            val mode = AndroidAutomationControlStateStore(applicationContext).load().mode
            val intent = Intent(applicationContext, HotspotAutomationMonitorService::class.java)
            return runCatching {
                if (
                    PremiumAccessGate.isPremium(applicationContext) &&
                    mode == AutomationTriggerMode.HOTSPOT &&
                    Build.VERSION.SDK_INT >= 36
                ) {
                    ContextCompat.startForegroundService(applicationContext, intent)
                } else {
                    applicationContext.stopService(intent)
                }
                true
            }.getOrDefault(false)
        }
    }
}
