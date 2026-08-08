/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.pebble.tecomheadunit.automation

import android.Manifest
import android.app.ForegroundServiceStartNotAllowedException
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.pebble.tecomheadunit.MainActivity
import com.pebble.tecomheadunit.R
import com.pebble.tecomheadunit.billing.PremiumAccessGate
import com.pebble.tecomheadunit.notification.NavOnWebNotificationAssets
import com.pebble.tecomheadunit.service.ProjectionService
import com.pebble.tecomheadunit.session.SessionController
import com.pebble.tecomheadunit.session.SessionPhase

class AndroidAutomationControlStateStore(context: Context) : AutomationControlStateStore {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE,
    )

    override fun load(): AutomationControlState {
        val mode = preferences.getString(KEY_MODE, null)
            ?.let { stored -> AutomationTriggerMode.entries.firstOrNull { it.name == stored } }
            ?: AutomationTriggerMode.NONE
        val generation = preferences.getLong(KEY_GENERATION, 0L).coerceAtLeast(0L)
        val lastActive = if (mode != AutomationTriggerMode.NONE && preferences.contains(KEY_LAST_ACTIVE)) {
            preferences.getBoolean(KEY_LAST_ACTIVE, false)
        } else {
            null
        }
        return AutomationControlState(mode, generation, lastActive)
    }

    override fun save(state: AutomationControlState): Boolean = preferences.edit()
        .putString(KEY_MODE, state.mode.name)
        .putLong(KEY_GENERATION, state.generation)
        .applyLastActive(state.lastTriggerActive)
        .commit()

    private fun SharedPreferences.Editor.applyLastActive(active: Boolean?): SharedPreferences.Editor =
        if (active == null) remove(KEY_LAST_ACTIVE) else putBoolean(KEY_LAST_ACTIVE, active)

    private companion object {
        const val PREFERENCES_NAME = "service_automation_control"
        const val KEY_MODE = "trigger_mode_v1"
        const val KEY_GENERATION = "trigger_generation_v1"
        const val KEY_LAST_ACTIVE = "last_trigger_active_v1"
    }
}

/** Android wiring is kept behind the pure [AutomationServiceRuntime] contract for unit tests. */
class AndroidProjectionServiceAutomationRuntime(context: Context) : AutomationServiceRuntime {
    private val applicationContext = context.applicationContext

    override fun isRunning(): Boolean = SessionController.state.value.phase.let { phase ->
        phase == SessionPhase.STARTING || phase == SessionPhase.READY
    }

    override fun start(): AutomationRuntimeResult {
        if (!PremiumAccessGate.isPremium(applicationContext)) {
            return AutomationRuntimeResult.Failed(
                AutomationRuntimeFailureReason.ENTITLEMENT_REQUIRED,
            )
        }
        return try {
            ProjectionService.start(
                context = applicationContext,
                benchConfirmed = true,
                startCancellationGeneration = ProjectionStartCancellationSignal.snapshot(),
            )
            AutomationRuntimeResult.Success
        } catch (error: RuntimeException) {
            if (
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                error is ForegroundServiceStartNotAllowedException
            ) {
                AutomationRuntimeResult.Deferred(
                    AutomationStartDeferredReason.BACKGROUND_FOREGROUND_SERVICE_START_RESTRICTED,
                )
            } else {
                error.toRuntimeFailure()
            }
        }
    }

    override fun stop(): AutomationRuntimeResult = try {
        ProjectionService.stop(applicationContext)
        AutomationRuntimeResult.Success
    } catch (_: SecurityException) {
        AutomationRuntimeResult.Failed(AutomationRuntimeFailureReason.SECURITY_RESTRICTION)
    } catch (_: RuntimeException) {
        AutomationRuntimeResult.Failed(AutomationRuntimeFailureReason.PLATFORM_REJECTED)
    }

    override fun cancelPendingStarts() {
        ProjectionStartCancellationSignal.cancelPendingStarts()
    }

    private fun RuntimeException.toRuntimeFailure(): AutomationRuntimeResult.Failed =
        AutomationRuntimeResult.Failed(
            if (this is SecurityException) {
                AutomationRuntimeFailureReason.SECURITY_RESTRICTION
            } else {
                AutomationRuntimeFailureReason.PLATFORM_REJECTED
            },
        )
}

class AndroidAutomationStartFallback(context: Context) : AutomationStartFallback {
    private val applicationContext = context.applicationContext

    override fun show(reason: AutomationStartDeferredReason): Boolean {
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(applicationContext, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return false
        }

        val notificationManager = applicationContext.getSystemService(NotificationManager::class.java)
            ?: return false
        notificationManager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                applicationContext.getString(R.string.automation_notification_channel),
                NotificationManager.IMPORTANCE_DEFAULT,
            ),
        )
        val openApp = PendingIntent.getActivity(
            applicationContext,
            NOTIFICATION_ID,
            Intent(applicationContext, MainActivity::class.java)
                .setAction(ACTION_RETRY_AUTOMATION)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val text = when (reason) {
            AutomationStartDeferredReason.BACKGROUND_FOREGROUND_SERVICE_START_RESTRICTED ->
                applicationContext.getString(R.string.automation_start_restricted)
        }
        notificationManager.notify(
            NOTIFICATION_ID,
            NotificationCompat.Builder(applicationContext, CHANNEL_ID)
                .setSmallIcon(NavOnWebNotificationAssets.smallIcon)
                .setContentTitle(applicationContext.getString(R.string.automation_start_required))
                .setContentText(text)
                .setContentIntent(openApp)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .build(),
        )
        return true
    }

    companion object {
        const val ACTION_RETRY_AUTOMATION = "com.eigenkodex.navonweb.action.RETRY_AUTOMATION"
        private const val CHANNEL_ID = "service_automation_action"
        private const val NOTIFICATION_ID = 5281
    }
}

/** Process singleton so Bluetooth broadcasts and tethering callbacks serialize state transitions. */
object AutomationServiceControllerProvider {
    @Volatile
    private var controller: AutomationServiceController? = null

    fun get(context: Context): AutomationServiceController = controller ?: synchronized(this) {
        controller ?: CoordinatingAutomationServiceController(
            stateStore = AndroidAutomationControlStateStore(context),
            runtime = AndroidProjectionServiceAutomationRuntime(context),
            startFallback = AndroidAutomationStartFallback(context),
        ).also { controller = it }
    }
}
