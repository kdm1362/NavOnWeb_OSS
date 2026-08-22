/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.eigenkodex.navonweb.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.CancellationSignal
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.eigenkodex.navonweb.MainActivity
import com.eigenkodex.navonweb.R
import com.eigenkodex.navonweb.automation.AndroidAutomationControlStateStore
import com.eigenkodex.navonweb.automation.AutomationProjectionOwner
import com.eigenkodex.navonweb.automation.AutomationTriggerMode
import com.eigenkodex.navonweb.automation.ProjectionStartCancellationSignal
import com.eigenkodex.navonweb.automation.ProjectionAutomationSessionOwnership
import com.eigenkodex.navonweb.automation.isAutomationProjectionStartAuthorized
import com.eigenkodex.navonweb.browser.BrowserFrameStore
import com.eigenkodex.navonweb.browser.BrowserJpegFallbackPolicy
import com.eigenkodex.navonweb.browser.BrowserProbeServer
import com.eigenkodex.navonweb.browser.BrowserSurfaceFrameCapture
import com.eigenkodex.navonweb.browser.BrowserWebRtcCodec
import com.eigenkodex.navonweb.browser.BrowserWebRtcIceServer
import com.eigenkodex.navonweb.browser.BrowserWebRtcSignaling
import com.eigenkodex.navonweb.browser.LocalAddressResolver
import com.eigenkodex.navonweb.browser.SwitchableBrowserWebRtcSignaling
import com.eigenkodex.navonweb.browser.audio.BrowserAudioPcmPipeline
import com.eigenkodex.navonweb.browser.audio.BrowserAudioTrack
import com.eigenkodex.navonweb.browser.audio.BrowserAudioTrackSnapshot
import com.eigenkodex.navonweb.browser.audio.BrowserMicrophonePcmSource
import com.eigenkodex.navonweb.browser.cloud.CloudBrowserRelayClient
import com.eigenkodex.navonweb.browser.cloud.CloudBrowserRelayConfig
import com.eigenkodex.navonweb.browser.cloud.BrowserAddressPolicy
import com.eigenkodex.navonweb.browser.cloud.CloudBrowserPageAvailability
import com.eigenkodex.navonweb.browser.cloud.CloudBrowserPageAvailabilityTracker
import com.eigenkodex.navonweb.notification.NavOnWebNotificationAssets
import com.eigenkodex.navonweb.browser.cloud.CloudBrowserPageProbe
import com.eigenkodex.navonweb.browser.cloud.CloudPairingRegistrationStatus
import com.eigenkodex.navonweb.browser.cloud.CloudRelayIdentityStore
import com.eigenkodex.navonweb.browser.stun.LanStunServer
import com.eigenkodex.navonweb.browser.webrtc.WebRtcProjectionController
import com.eigenkodex.navonweb.browser.webrtc.WebRtcProjectionConfig
import com.eigenkodex.navonweb.audio.AudioStreamAccessTier
import com.eigenkodex.navonweb.core.OpenAutoCoordinator
import com.eigenkodex.navonweb.core.OpenAutoTouchEvent
import com.eigenkodex.navonweb.core.SafetyGate
import com.eigenkodex.navonweb.core.VehicleState
import com.eigenkodex.navonweb.core.VideoViewport
import com.eigenkodex.navonweb.diagnostics.AppDiagnostics
import com.eigenkodex.navonweb.diagnostics.DiagnosticEventCode
import com.eigenkodex.navonweb.diagnostics.DiagnosticLevel
import com.eigenkodex.navonweb.remote.SupabaseNoticeRepository
import com.eigenkodex.navonweb.openauto.AasdkDecoderUnavailableException
import com.eigenkodex.navonweb.openauto.AasdkPeerShutdownException
import com.eigenkodex.navonweb.openauto.AasdkProjectionRuntime
import com.eigenkodex.navonweb.openauto.BenchOpenAutoEngine
import com.eigenkodex.navonweb.openauto.MediaCodecVideoSink
import com.eigenkodex.navonweb.openauto.NativeOpenAutoBridge
import com.eigenkodex.navonweb.openauto.AndroidProjectionProfilePreferenceStore
import com.eigenkodex.navonweb.openauto.AndroidProjectionDpiPreferenceStore
import com.eigenkodex.navonweb.openauto.OpenAutoConfig
import com.eigenkodex.navonweb.openauto.OpenAutoInputSink
import com.eigenkodex.navonweb.openauto.OpenAutoKeyEvent
import com.eigenkodex.navonweb.openauto.OpenAutoPreflight
import com.eigenkodex.navonweb.openauto.ProjectionEntitlementProviderFactory
import com.eigenkodex.navonweb.openauto.ProjectionEndpointMode
import com.eigenkodex.navonweb.openauto.ProjectionAccessTier
import com.eigenkodex.navonweb.openauto.ProjectionDpiSettingsManager
import com.eigenkodex.navonweb.openauto.ProjectionProfileApplyScheduler
import com.eigenkodex.navonweb.openauto.ProjectionProfileManager
import com.eigenkodex.navonweb.openauto.ProjectionProfileRequestResult
import com.eigenkodex.navonweb.openauto.ProjectionProfileSnapshot
import com.eigenkodex.navonweb.openauto.ProjectionVideoProfile
import com.eigenkodex.navonweb.openauto.ProjectionViewportApplyScheduler
import com.eigenkodex.navonweb.openauto.ProjectionViewportLayout
import com.eigenkodex.navonweb.openauto.ProjectionViewportManager
import com.eigenkodex.navonweb.openauto.ProjectionViewportResolver
import com.eigenkodex.navonweb.openauto.requestProjectionProfileWithCurrentEntitlement
import com.eigenkodex.navonweb.openauto.protocol.AasdkProtocolException
import com.eigenkodex.navonweb.openauto.protocol.AasdkTlsEngineException
import com.eigenkodex.navonweb.openauto.protocol.AasdkTlsPolicyException
import com.eigenkodex.navonweb.openauto.protocol.AasdkVersionMismatchException
import com.eigenkodex.navonweb.openauto.sensor.AndroidNightModeStateProvider
import com.eigenkodex.navonweb.openauto.sensor.AndroidNightModeLocationSource
import com.eigenkodex.navonweb.openauto.sensor.NightModeDecision
import com.eigenkodex.navonweb.openauto.sensor.ProjectionNightModeLocation
import com.eigenkodex.navonweb.session.BrowserTrustStore
import com.eigenkodex.navonweb.session.BrowserDevicePermission
import com.eigenkodex.navonweb.session.PairedBrowserDevice
import com.eigenkodex.navonweb.session.PairedBrowserMutationResult
import com.eigenkodex.navonweb.session.PairedBrowserRemovalResult
import com.eigenkodex.navonweb.session.AndroidAutoConnectionState
import com.eigenkodex.navonweb.session.AndroidAutoConnectionStatus
import com.eigenkodex.navonweb.session.PairingToken
import com.eigenkodex.navonweb.session.SessionController
import com.eigenkodex.navonweb.session.SessionPhase
import com.eigenkodex.navonweb.ui.WebRtcCodecPreferenceOption
import com.eigenkodex.navonweb.ui.WebRtcCodecPreferenceStore
import java.io.EOFException
import java.io.IOException
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal fun projectionReconfigurationCooldownDelayMillis(
    lastMediaAcceptedElapsedRealtime: Long,
    lastCommitElapsedRealtime: Long,
    nowElapsedRealtime: Long,
    minimumIntervalMillis: Long,
): Long {
    require(minimumIntervalMillis >= 0L) { "minimum interval must be non-negative" }
    val anchor = maxOf(lastMediaAcceptedElapsedRealtime, lastCommitElapsedRealtime)
    if (anchor <= 0L || minimumIntervalMillis == 0L) return 0L
    if (nowElapsedRealtime < anchor) return minimumIntervalMillis
    return (minimumIntervalMillis - (nowElapsedRealtime - anchor)).coerceAtLeast(0L)
}

internal fun shouldTriggerPortraitPreVideoFallback(
    portraitViewport: Boolean,
    serviceDiscoveryResponseSent: Boolean,
    videoMediaAccepted: Boolean,
    runtimeStatus: String,
): Boolean =
    portraitViewport &&
        serviceDiscoveryResponseSent &&
        !videoMediaAccepted &&
        (
            runtimeStatus.startsWith("DISCONNECTED reason=") ||
                runtimeStatus == "AV_STREAM_STOPPED channel=3"
            )

internal class ProjectionMediaAttemptState {
    var generation: Long = 0L
        private set
    var serviceDiscoveryResponseSent: Boolean = false
        private set
    var videoMediaAccepted: Boolean = false
        private set

    fun beginAttempt(): Long {
        generation += 1L
        serviceDiscoveryResponseSent = false
        videoMediaAccepted = false
        return generation
    }

    fun markServiceDiscoveryResponseSent(): Long {
        serviceDiscoveryResponseSent = true
        return generation
    }

    /** Returns true exactly once per connection attempt. */
    fun markVideoMediaAccepted(): Boolean {
        if (videoMediaAccepted) return false
        videoMediaAccepted = true
        return true
    }

    fun isAwaitingFirstVideo(expectedGeneration: Long): Boolean =
        generation == expectedGeneration && serviceDiscoveryResponseSent && !videoMediaAccepted
}

internal fun <T : Any> replaceBrowserSessionControllerAtomically(
    lock: Any,
    current: () -> T?,
    replacement: T?,
    replaceDelegate: (expected: T?, replacement: T?) -> Boolean,
    updateCurrent: (T?) -> Unit,
    retire: (T) -> Unit,
): Boolean = synchronized(lock) {
    val previous = current()
    if (!replaceDelegate(previous, replacement)) return@synchronized false
    updateCurrent(replacement)
    previous?.let(retire)
    true
}

class ProjectionService : Service() {
    private var server: BrowserProbeServer? = null
    private var cloudRelayClient: CloudBrowserRelayClient? = null
    private var cloudBrowserPageProbe: CloudBrowserPageProbe? = null
    private var lanStunServer: LanStunServer? = null
    private var browserFrameStore: BrowserFrameStore? = null
    private var browserFrameCapture: BrowserSurfaceFrameCapture? = null
    private var webRtcController: WebRtcProjectionController? = null
    private var webRtcSignaling: SwitchableBrowserWebRtcSignaling? = null
    private var browserAudioPipeline: BrowserAudioPcmPipeline? = null
    private var browserMicrophoneSource: BrowserMicrophonePcmSource? = null
    private var coordinator: OpenAutoCoordinator? = null
    private var profileControl: ProjectionProfileManager? = null
    private var sessionPreflight: OpenAutoPreflight? = null
    private var sessionBenchConfirmed = false
    @Volatile
    private var projectionRuntime: AasdkProjectionRuntime? = null
    private var projectionDecoderController: WebRtcProjectionController? = null
    private var projectionDecoderFrameCapture: BrowserSurfaceFrameCapture? = null
    private val projectionSurfaceLock = Any()
    private val browserSessionAdmissionLock = Any()
    private val localBinder = LocalBinder()
    private var projectionSurface: Surface? = null
    private var projectionSurfaceGeneration = NO_SURFACE_GENERATION
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val projectionReconfigurationMutex = Mutex()
    private var startJob: Job? = null
    private var stopJob: Job? = null
    private var projectionJob: Job? = null
    private var portraitMediaAcceptanceWatchdogJob: Job? = null
    private var profileApplyJob: Job? = null
    private var viewportApplyJob: Job? = null
    private var browserAddressMonitorJob: Job? = null
    private var diagnosticHeartbeatJob: Job? = null
    private var noticeRefreshJob: Job? = null
    private var pendingProjectionProfile: ProjectionVideoProfile? = null
    private var pendingProjectionViewport: ProjectionViewportLayout? = null
    private var lastProjectionMediaAcceptedElapsedRealtime = 0L
    private var lastProjectionReconfigurationCommitElapsedRealtime = 0L
    private val videoEventsSinceDiagnostic = AtomicLong(0L)
    private val touchEventsSinceDiagnostic = AtomicLong(0L)
    private val webRtcCodecPreferenceStore by lazy { WebRtcCodecPreferenceStore(applicationContext) }
    private val nightModeStateProvider by lazy { AndroidNightModeStateProvider(applicationContext) }
    private val nightModeLocationSource by lazy { AndroidNightModeLocationSource(applicationContext) }
    private val projectionStartupStore by lazy { ProjectionStartupStore(applicationContext) }
    private val browserTrustStore by lazy { BrowserTrustStore(applicationContext) }
    private val projectionDpiSettings by lazy {
        ProjectionDpiSettingsManager(AndroidProjectionDpiPreferenceStore(applicationContext))
    }
    private val noticeRepository by lazy { SupabaseNoticeRepository(applicationContext) }
    private var nightModeLocationCancellation: CancellationSignal? = null
    @Volatile
    private var latestNightModeDecision: NightModeDecision? = null
    private var sessionEpoch = 0L
    private var wasAndroidAutoConnected = false
    private var connectionAlertPosted = false
    private var lastForegroundConnectionText: String? = null
    private var activeProjectionConfig: OpenAutoConfig =
        ProjectionVideoProfile.FREE_800X480.toOpenAutoConfig()
    private var viewportControl: ProjectionViewportManager? = null

    private fun freshStandbyProfileControl(): ProjectionProfileManager =
        ProjectionProfileManager(
            entitlementProvider = ProjectionEntitlementProviderFactory.create(applicationContext),
            preferenceStore = AndroidProjectionProfilePreferenceStore(applicationContext),
        )

    inner class LocalBinder : Binder() {
        fun attachProjectionSurface(surface: Surface, generation: Long): Boolean =
            this@ProjectionService.attachProjectionSurface(surface, generation)

        fun detachProjectionSurface(generation: Long) {
            this@ProjectionService.detachProjectionSurface(generation)
        }

        fun projectionProfileSnapshot(): ProjectionProfileSnapshot =
            profileControl?.snapshot()
                ?: freshStandbyProfileControl().snapshot()

        fun requestProjectionProfile(profileId: String?): ProjectionProfileRequestResult =
            requestProjectionProfileWithCurrentEntitlement(
                activeControl = profileControl,
                currentEntitlementControl = freshStandbyProfileControl(),
                profileId = profileId,
            )

        /** The Activity persists the preference first; only the active profile is renegotiated. */
        fun notifyProjectionDpiPreferenceChanged(profileId: String?, densityDpi: Int): Boolean {
            val profile = ProjectionVideoProfile.fromProfileId(profileId) ?: return false
            if (!ProjectionVideoProfile.isSupportedDensityDpi(densityDpi)) return false
            if (projectionDpiSettings.densityDpi(profile) != densityDpi) return false
            val current = profileControl?.snapshot() ?: return false
            if (
                current.activationState !=
                com.eigenkodex.navonweb.openauto.ProjectionProfileActivationState.ACTIVE ||
                current.activeProfile != profile
            ) {
                return false
            }
            return when (val result = viewportControl?.requestDensityDpi(profile, densityDpi)) {
                is com.eigenkodex.navonweb.openauto.ProjectionViewportRequestResult.Accepted ->
                    result.changed
                else -> false
            }
        }

        /** Read-only, caller must discard ICE server fields before placing this in UI state. */
        fun webRtcCapabilities() =
            webRtcController?.capabilities()
                ?: BrowserWebRtcSignaling.Unavailable.capabilities()

        /** The preference itself is persisted by MainActivity before this notification. */
        fun notifyWebRtcCodecPreferenceChanged() {
            webRtcController?.closeActiveSessionForReconfiguration()
        }

        /** Location coordinates never leave the provider; only the resolved mode is exposed. */
        fun notifyNightModeEnvironmentChanged() {
            latestNightModeDecision = projectionRuntime?.refreshNightMode()
                ?: runCatching(nightModeStateProvider::currentDecision).getOrNull()
        }

        fun nightModeDecision(): NightModeDecision? =
            projectionRuntime?.currentNightModeDecision() ?: latestNightModeDecision

        fun browserAudioAccessTier(): AudioStreamAccessTier? = browserAudioPipeline?.accessTier

        fun browserAudioSnapshot(): Map<BrowserAudioTrack, BrowserAudioTrackSnapshot> =
            browserAudioPipeline?.streamHub?.snapshot().orEmpty()

        fun pairedBrowserDevices(): List<PairedBrowserDevice> {
            server?.let { return it.pairedBrowserDevices() }
            val liveColorSlots = webRtcSignaling
                ?.connectedSessionMetadata()
                .orEmpty()
                .associate { it.deviceId to it.colorSlot }
            return browserTrustStore.devices().map { device ->
                liveColorSlots[device.deviceId]
                    ?.let { activeSlot -> device.copy(colorSlot = activeSlot) }
                    ?: device
            }
        }

        fun connectedBrowserDeviceIds(): Set<String> =
            server?.connectedBrowserDeviceIds()
                ?: webRtcSignaling?.connectedDeviceIds().orEmpty()

        fun renamePairedBrowserDevice(
            deviceId: String,
            displayName: String,
        ): PairedBrowserMutationResult {
            server?.let { return it.renamePairedBrowserDevice(deviceId, displayName) }
            return synchronized(browserSessionAdmissionLock) {
                browserTrustStore.renameDevice(deviceId, displayName).also { result ->
                    if (result is PairedBrowserMutationResult.Updated) {
                        result.device?.let { renamed ->
                            webRtcSignaling?.updateDeviceDisplayName(deviceId, renamed.displayName)
                        }
                    }
                }
            }
        }

        fun updatePairedBrowserAccess(
            deviceId: String,
            permission: BrowserDevicePermission,
        ): PairedBrowserMutationResult {
            server?.let { return it.updatePairedBrowserAccess(deviceId, permission) }
            return synchronized(browserSessionAdmissionLock) {
                val previousPermission = browserTrustStore.devices()
                    .firstOrNull { it.deviceId == deviceId }
                    ?.permission
                val result = browserTrustStore.updateAccess(deviceId, permission)
                if (
                    result is PairedBrowserMutationResult.Updated &&
                    previousPermission != permission
                ) {
                    webRtcSignaling?.closeSessionsForDevice(deviceId)
                    ensurePreferredMainFromLiveSession()
                }
                result
            }
        }

        fun setPreferredMainBrowserDevice(deviceId: String?): PairedBrowserMutationResult {
            server?.let { return it.setPreferredMainBrowserDevice(deviceId) }
            return synchronized(browserSessionAdmissionLock) {
                val result = browserTrustStore.setPreferredMain(deviceId)
                if (result is PairedBrowserMutationResult.Updated && deviceId == null) {
                    ensurePreferredMainFromLiveSession() ?: result
                } else {
                    result
                }
            }
        }

        fun removePairedBrowserDevice(deviceId: String): PairedBrowserRemovalResult {
            server?.let { return it.removePairedBrowserDevice(deviceId) }
            return synchronized(browserSessionAdmissionLock) {
                val result = browserTrustStore.remove(deviceId)
                if (result is PairedBrowserRemovalResult.Removed) {
                    webRtcSignaling?.closeSessionsForOwner(result.ownerKey)
                    ensurePreferredMainFromLiveSession()
                }
                result
            }
        }

        /** Opens (or returns) one explicit ten-minute code without touching trusted credentials. */
        fun requestNewBrowserPairing(): Boolean =
            server?.let {
                it.requestNewBrowserPairing()
                true
            } == true
    }

    private fun ensurePreferredMainFromLiveSession(): PairedBrowserMutationResult? =
        synchronized(browserSessionAdmissionLock) {
            if (browserTrustStore.preferredMainDeviceId() != null) return@synchronized null
            val candidate = webRtcSignaling?.oldestInteractiveDeviceId()
                ?: return@synchronized null
            browserTrustStore.setPreferredMainIfUnset(candidate)
        }

    private fun claimPreferredMainBrowserDeviceIfUnset(deviceId: String): Boolean =
        synchronized(browserSessionAdmissionLock) {
            val mutation = browserTrustStore.setPreferredMainIfUnset(deviceId)
            (mutation as? PairedBrowserMutationResult.Updated)
                ?.preferredMainDeviceId == deviceId
        }

    override fun onCreate() {
        super.onCreate()
        AppDiagnostics.initialize(applicationContext)
        AppDiagnostics.record(DiagnosticEventCode.SERVICE_CREATED)
        noticeRefreshJob = serviceScope.launch(Dispatchers.IO) {
            while (isActive) {
                noticeRepository.refresh()
                delay(NOTICE_REFRESH_INTERVAL_MILLIS)
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder = localBinder

    override fun onUnbind(intent: Intent?): Boolean {
        detachCurrentProjectionSurface()
        return false
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> stopSession(getString(R.string.session_stopped_by_user))
            ACTION_STOP_IF_AUTOMATION_OWNER -> stopIfAutomationOwnerMatches(intent, startId)
            ACTION_START -> handleStartRequest(intent, startId)
        }
        return START_NOT_STICKY
    }

    private fun handleStartRequest(intent: Intent, startId: Int) {
        val hasAutomationOwner =
            intent.hasExtra(EXTRA_AUTOMATION_SOURCE) ||
                intent.hasExtra(EXTRA_AUTOMATION_GENERATION)
        val automationOwner = automationOwnerFrom(intent)
        if (
            hasAutomationOwner &&
            (
                automationOwner == null ||
                    !runCatching {
                        isAutomationProjectionStartAuthorized(
                            owner = automationOwner,
                            state = AndroidAutomationControlStateStore(applicationContext).load(),
                        )
                    }.getOrDefault(false)
                )
        ) {
            if (!hasActiveSession()) stopSelf(startId)
            return
        }

        val begin = {
            val alreadyActive = hasActiveSession()
            if (automationOwner == null) {
                ProjectionAutomationSessionOwnership.clearForManualStart()
            } else if (!alreadyActive) {
                ProjectionAutomationSessionOwnership.claim(automationOwner)
            }
            beginStartSession(
                benchConfirmed = intent.getBooleanExtra(EXTRA_BENCH_CONFIRMED, false),
            )
        }
        if (intent.hasExtra(EXTRA_START_CANCELLATION_GENERATION)) {
            ProjectionStartCancellationSignal.runIfCurrent(
                intent.getLongExtra(EXTRA_START_CANCELLATION_GENERATION, Long.MIN_VALUE),
                begin,
            )
        } else {
            begin()
        }
    }

    private fun stopIfAutomationOwnerMatches(intent: Intent, startId: Int) {
        val owner = automationOwnerFrom(intent)
        if (owner != null && ProjectionAutomationSessionOwnership.matches(owner)) {
            stopSession(getString(R.string.session_stopped_by_user))
        } else if (!hasActiveSession()) {
            stopSelf(startId)
        }
    }

    private fun hasActiveSession(): Boolean =
        server != null ||
            startJob?.isActive == true ||
            projectionJob?.isActive == true ||
            SessionController.state.value.phase == SessionPhase.STARTING ||
            SessionController.state.value.phase == SessionPhase.READY

    private fun automationOwnerFrom(intent: Intent): AutomationProjectionOwner? {
        val source = intent.getStringExtra(EXTRA_AUTOMATION_SOURCE)
            ?.let { stored -> AutomationTriggerMode.entries.firstOrNull { it.name == stored } }
            ?.takeUnless { it == AutomationTriggerMode.NONE }
            ?: return null
        if (!intent.hasExtra(EXTRA_AUTOMATION_GENERATION)) return null
        val generation = intent.getLongExtra(EXTRA_AUTOMATION_GENERATION, Long.MIN_VALUE)
        if (generation < 0L) return null
        return AutomationProjectionOwner(source, generation)
    }

    private fun beginStartSession(
        benchConfirmed: Boolean,
    ) {
        if (server != null || startJob?.isActive == true || projectionJob?.isActive == true) return

        // No credential, USB, TCP, or TLS work is allowed before this decision.
        if (!benchConfirmed) {
            AppDiagnostics.record(
                DiagnosticEventCode.SESSION_START_REQUESTED,
                fields = mapOf(
                    "endpoint_mode" to ProjectionEndpointMode.THIS_PHONE.name,
                    "identity_provider" to "NONE",
                ),
            )
            AppDiagnostics.record(
                DiagnosticEventCode.SESSION_START_REJECTED,
                level = DiagnosticLevel.WARN,
                fields = mapOf("reason" to "safety_confirmation_missing"),
            )
            SessionController.error(
                getString(R.string.projection_start_rejected_safety),
                "SAFETY_GATE_DENIED",
            )
            stopSelf()
            return
        }

        val epoch = ++sessionEpoch
        SessionController.starting("LOCAL_PREFLIGHT")
        startForeground(
            NOTIFICATION_ID,
            buildNotification(getString(R.string.projection_preflight_reconnecting)),
        )
        startJob = serviceScope.launch {
            try {
                val preflight = withContext(Dispatchers.IO) {
                    // The preflight resolves only the same-phone loopback endpoint.
                    // It never tries to start Android Auto's private developer
                    // Head Unit Server on behalf of the user.
                    NativeOpenAutoBridge.preflight(applicationContext)
                }
                ensureActive()
                if (epoch != sessionEpoch) return@launch
                AppDiagnostics.record(
                    DiagnosticEventCode.SESSION_START_REQUESTED,
                    fields = mapOf(
                        "endpoint_mode" to preflight.endpointMode.name,
                        "identity_provider" to preflight.credentialSource.name,
                    ),
                )
                startSession(benchConfirmed = true, preflight = preflight, epoch = epoch)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                if (epoch == sessionEpoch) {
                    AppDiagnostics.recordFailure(
                        DiagnosticEventCode.SESSION_FAILED,
                        error,
                        fields = mapOf("stage" to "preflight"),
                    )
                    SessionController.error(
                        getString(R.string.projection_preflight_failed),
                        "PREFLIGHT_FAILED",
                    )
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            } finally {
                if (epoch == sessionEpoch) startJob = null
            }
        }
    }

    private fun startSession(
        benchConfirmed: Boolean,
        preflight: OpenAutoPreflight,
        epoch: Long,
    ) {
        if (epoch != sessionEpoch || server != null) return
        refreshNightModeLocation()

        val newProfileControl = ProjectionProfileManager(
            entitlementProvider = ProjectionEntitlementProviderFactory.create(applicationContext),
            preferenceStore = AndroidProjectionProfilePreferenceStore(applicationContext),
            applyScheduler = ProjectionProfileApplyScheduler { profile ->
                scheduleProjectionProfileApply(profile, epoch)
            },
        )
        val initialProfileSnapshot = newProfileControl.snapshot()
        val activeProfile = initialProfileSnapshot.activeProfile
        val newViewportControl = ProjectionViewportManager(
            profileSnapshot = newProfileControl::snapshot,
            initialProfile = activeProfile,
            applyScheduler = ProjectionViewportApplyScheduler { layout ->
                scheduleProjectionViewportApply(layout, epoch)
            },
            densityDpiForProfile = projectionDpiSettings::densityDpi,
        )
        val newAudioPipeline = BrowserAudioPcmPipeline(
            accessTier = when (initialProfileSnapshot.entitlementTier) {
                ProjectionAccessTier.FREE -> AudioStreamAccessTier.FREE
                ProjectionAccessTier.PREMIUM -> AudioStreamAccessTier.PREMIUM
            },
        )
        val newMicrophoneSource = BrowserMicrophonePcmSource()
        val projectionConfig = activeProfile.toOpenAutoConfig(
            densityDpi = projectionDpiSettings.densityDpi(activeProfile),
        )
        val engine = BenchOpenAutoEngine()
        val runtimeInput = object : OpenAutoInputSink {
            override fun sendTouch(event: OpenAutoTouchEvent): Boolean =
                projectionRuntime?.sendTouch(event) == true

            override fun sendKey(event: OpenAutoKeyEvent): Boolean =
                projectionRuntime?.sendKey(event) == true
        }
        val newCoordinator = OpenAutoCoordinator(
            engine = engine,
            safetyGate = SafetyGate(),
            config = projectionConfig,
            inputSink = runtimeInput,
        )
        val startResult = newCoordinator.start(VehicleState.BENCH_STATIONARY)
        if (startResult.isFailure) {
            AppDiagnostics.recordFailure(
                DiagnosticEventCode.SESSION_FAILED,
                startResult.exceptionOrNull() ?: IllegalStateException("coordinator start failed"),
                fields = mapOf("stage" to "coordinator_start"),
            )
            SessionController.error(
                getString(R.string.projection_service_start_failed),
                preflight.summary,
            )
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }

        val pairingCode = PairingToken.create()
        val newFrameStore = BrowserFrameStore(
            maxFrameBytes = BrowserJpegFallbackPolicy.FRAME_STORE_MAX_BYTES,
        )
        val newFrameCapture = createBrowserFrameCapture(newFrameStore, activeProfile)
        lateinit var newServer: BrowserProbeServer
        val webRtcConfig = WebRtcProjectionConfig.forProjectionProfile(activeProfile)
        val newWebRtcController = WebRtcProjectionController(
            context = applicationContext,
            config = webRtcConfig,
            microphoneSource = newMicrophoneSource,
            audioStreams = newAudioPipeline.streamHub,
            controlRequestHandler = { ownerKey, request ->
                newServer.dispatchWebRtcControl(ownerKey, request)
            },
            preferredMainDeviceId = browserTrustStore::preferredMainDeviceId,
            claimMainDeviceIfUnset = ::claimPreferredMainBrowserDeviceIfUnset,
            sessionAdmissionLock = browserSessionAdmissionLock,
        ).let { candidate ->
            candidate.start().fold(
                onSuccess = { candidate },
                onFailure = { error ->
                    Log.w(
                        WEBRTC_LOG_TAG,
                        "WEBRTC_CORE_UNAVAILABLE ${error.javaClass.simpleName}: ${error.message}",
                    )
                    candidate.close()
                    null
                },
            )
        }
        val newWebRtcSignaling = SwitchableBrowserWebRtcSignaling(
            newWebRtcController ?: BrowserWebRtcSignaling.Unavailable,
        )
        val cloudRelayConfig = CloudBrowserRelayConfig.configuredOrNull()
        var newLanStunServer: LanStunServer? = null
        newServer = BrowserProbeServer(
            context = applicationContext,
            pairingCode = pairingCode,
            browserTrustStore = browserTrustStore,
            pairingCodeOperationLock = browserSessionAdmissionLock,
            frameStore = newFrameStore,
            snapshot = { coordinator?.snapshot() ?: newCoordinator.snapshot() },
            androidAutoConnection = { SessionController.state.value.androidAutoConnection },
            touchInputReady = { projectionRuntime?.isTouchInputReady() == true },
            keyInputReady = { projectionRuntime?.isKeyInputReady() == true },
            onTouch = { touch ->
                coordinator?.submitTouch(touch)?.onSuccess {
                    SessionController.touch(it)
                    touchEventsSinceDiagnostic.incrementAndGet()
                }?.isSuccess == true
            },
            onKey = runtimeInput::sendKey,
            webRtcSignaling = newWebRtcSignaling,
            cloudLanStunIceServer = {
                newLanStunServer?.let { stun ->
                    LocalAddressResolver.resolveAll()
                        .filter(BrowserProbeServer::isEligibleDirectPeerIpv4)
                        .map { address -> "stun:$address:${stun.localPort}" }
                        .takeIf(List<String>::isNotEmpty)
                        ?.let { urls ->
                            BrowserWebRtcIceServer(
                                urls = urls,
                            )
                        }
                }
            },
            projectionProfiles = newProfileControl,
            projectionViewport = newViewportControl,
            preferredWebRtcCodec = {
                webRtcCodecPreferenceStore.load().toBrowserWebRtcCodec()
            },
            audioStreams = newAudioPipeline.streamHub,
            microphoneSource = newMicrophoneSource,
            noticeFeed = noticeRepository::snapshot,
            onPairingCodePublicationChanged = { publication ->
                serviceScope.launch {
                    if (epoch == sessionEpoch && server === newServer) {
                        val relay = cloudRelayClient
                        SessionController.updatePairingCode(
                            pairingCode = publication?.code,
                            cloudPairingRegistrationStatus =
                                CloudPairingRegistrationStatus.REGISTERING
                                    .takeIf { publication != null && relay != null },
                        )
                        relay?.let {
                            if (publication == null) {
                                it.stopPublishingPairingCode()
                            } else {
                                runCatching { it.publishPairingCode(publication) }
                                    .onFailure { error ->
                                        Log.w(
                                            CLOUD_RELAY_STATE_LOG_TAG,
                                            "CLOUD_PAIRING_PUBLISH_FAILED " +
                                                error.javaClass.simpleName,
                                        )
                                        SessionController.updatePairingCode(
                                            pairingCode = publication.code,
                                            cloudPairingRegistrationStatus =
                                                CloudPairingRegistrationStatus.RETRY,
                                        )
                                    }
                            }
                        }
                    }
                }
            },
        )

        var newCloudRelayClient: CloudBrowserRelayClient? = null
        var newCloudBrowserPageProbe: CloudBrowserPageProbe? = null
        val cloudPairingPublicationEpochFloor = AtomicLong(0L)
        runCatching {
            val port = newServer.start()
            val address = LocalAddressResolver.resolveOrLoopback()
            val localUrl = "http://$address:$port"
            if (cloudRelayConfig != null) {
                val stunCandidate = LanStunServer(
                    onFirstDatagram = { decoded, length ->
                        Log.i(WEBRTC_LOG_TAG, "LAN_STUN_DATAGRAM decoded=$decoded bytes=$length")
                    },
                    onFirstResponse = {
                        Log.i(WEBRTC_LOG_TAG, "LAN_STUN_BINDING_RESPONDED")
                    },
                )
                newLanStunServer = runCatching {
                    stunCandidate.start()
                    Log.i(WEBRTC_LOG_TAG, "LAN_STUN_READY port=${stunCandidate.localPort}")
                    stunCandidate
                }.getOrElse { error ->
                    stunCandidate.close()
                    Log.w(
                        WEBRTC_LOG_TAG,
                        "LAN_STUN_UNAVAILABLE ${error.javaClass.simpleName}",
                    )
                    null
                }
            }
            cloudRelayConfig?.let { cloudConfig ->
                var pageProbe: CloudBrowserPageProbe? = null
                runCatching {
                    pageProbe = CloudBrowserPageProbe()
                    createCloudRelayClient(
                        cloudConfig = cloudConfig,
                        relayServer = newServer,
                        epoch = epoch,
                        publicationEpochFloor = cloudPairingPublicationEpochFloor,
                    )
                }.onSuccess { relay ->
                    newCloudBrowserPageProbe = pageProbe
                    newCloudRelayClient = relay
                }.onFailure { error ->
                    pageProbe?.close()
                    Log.w(
                        CLOUD_RELAY_STATE_LOG_TAG,
                        "CLOUD_RELAY_INIT_FAILED ${error.javaClass.simpleName}",
                    )
                }
            }

            coordinator = newCoordinator
            server = newServer
            cloudRelayClient = newCloudRelayClient
            cloudBrowserPageProbe = newCloudBrowserPageProbe
            lanStunServer = newLanStunServer
            browserFrameStore = newFrameStore
            browserFrameCapture = newFrameCapture
            webRtcController = newWebRtcController
            webRtcSignaling = newWebRtcSignaling
            browserAudioPipeline = newAudioPipeline
            browserMicrophoneSource = newMicrophoneSource
            profileControl = newProfileControl
            viewportControl = newViewportControl
            sessionPreflight = preflight
            sessionBenchConfirmed = benchConfirmed
            lastProjectionMediaAcceptedElapsedRealtime = 0L
            lastProjectionReconfigurationCommitElapsedRealtime = 0L
            activeProjectionConfig = projectionConfig
            connectBrowserFrameSink()
            attachWebRtcPreviewToCurrentSurface()
            newCloudRelayClient?.let { relay ->
                runCatching { relay.start() }
                    .onFailure { error ->
                        Log.w(
                            CLOUD_RELAY_STATE_LOG_TAG,
                            "CLOUD_RELAY_START_FAILED ${error.javaClass.simpleName}",
                        )
                        relay.close()
                        if (cloudRelayClient === relay) cloudRelayClient = null
                        newCloudRelayClient = null
                        newCloudBrowserPageProbe?.close()
                        if (cloudBrowserPageProbe === newCloudBrowserPageProbe) {
                            cloudBrowserPageProbe = null
                        }
                        newCloudBrowserPageProbe = null
                    }
            }
            val initialBrowserUrl = BrowserAddressPolicy.select(
                cloudUrl = newCloudRelayClient?.browserUrl,
                localUrl = localUrl,
                cloudAvailability = CloudBrowserPageAvailability.UNKNOWN,
            )
            SessionController.ready(
                url = initialBrowserUrl,
                localUrl = localUrl,
                pairingCode = newServer.publishedPairingCode(),
                nativeStatus = preflight.summary,
                cloudPairingRegistrationStatus =
                    CloudPairingRegistrationStatus.REGISTERING.takeIf {
                        newCloudRelayClient != null && newServer.publishedPairingCode() != null
                    },
            )
            updateNotification(getString(R.string.browser_ready_notification, initialBrowserUrl))
            if (cloudRelayConfig != null && newCloudBrowserPageProbe != null) {
                startBrowserAddressMonitor(
                    cloudConfig = cloudRelayConfig,
                    localPort = port,
                    probe = requireNotNull(newCloudBrowserPageProbe),
                    epoch = epoch,
                )
            }
            AppDiagnostics.record(
                DiagnosticEventCode.SESSION_READY,
                fields = mapOf(
                    "endpoint_mode" to preflight.endpointMode.name,
                    "profile" to activeProfile.name,
                    "webrtc_available" to
                        (newWebRtcController?.capabilities()?.available == true),
                    "browser_transport" to if (newCloudRelayClient == null) "lan_http" else "cloud_wss",
                    "lan_stun_available" to (newLanStunServer != null),
                ),
            )
            startDiagnosticHeartbeat(epoch)
            recordRuntimeSnapshot()
            launchProjection(preflight, benchConfirmed, projectionConfig, epoch)
        }.onFailure { error ->
            if (server === newServer) server = null
            if (cloudRelayClient === newCloudRelayClient) cloudRelayClient = null
            if (cloudBrowserPageProbe === newCloudBrowserPageProbe) {
                cloudBrowserPageProbe = null
            }
            if (lanStunServer === newLanStunServer) lanStunServer = null
            if (browserFrameCapture === newFrameCapture) browserFrameCapture = null
            if (browserFrameStore === newFrameStore) browserFrameStore = null
            if (webRtcController === newWebRtcController) webRtcController = null
            if (webRtcSignaling === newWebRtcSignaling) webRtcSignaling = null
            if (browserAudioPipeline === newAudioPipeline) browserAudioPipeline = null
            if (browserMicrophoneSource === newMicrophoneSource) browserMicrophoneSource = null
            if (profileControl === newProfileControl) profileControl = null
            if (viewportControl === newViewportControl) viewportControl = null
            if (sessionPreflight === preflight) sessionPreflight = null
            sessionBenchConfirmed = false
            activeProjectionConfig = ProjectionVideoProfile.FREE_800X480.toOpenAutoConfig()
            browserAddressMonitorJob?.cancel()
            browserAddressMonitorJob = null
            newCloudRelayClient?.close()
            newCloudBrowserPageProbe?.close()
            newLanStunServer?.close()
            newServer.close()
            newWebRtcController?.close()
            newAudioPipeline.close()
            newMicrophoneSource.close()
            newFrameCapture.close()
            newFrameStore.close()
            newCoordinator.stop()
            AppDiagnostics.recordFailure(
                DiagnosticEventCode.SESSION_FAILED,
                error,
                fields = mapOf("stage" to "browser_server_start"),
            )
            SessionController.error(
                getString(R.string.browser_server_start_failed),
                preflight.summary,
            )
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    /** Builds one relay client for [cloudConfig]; shared by session start and leg failover. */
    private fun createCloudRelayClient(
        cloudConfig: CloudBrowserRelayConfig,
        relayServer: BrowserProbeServer,
        epoch: Long,
        publicationEpochFloor: AtomicLong,
    ): CloudBrowserRelayClient {
        val identityStore = CloudRelayIdentityStore(applicationContext)
        val identity = identityStore.getOrCreate()
        lateinit var relay: CloudBrowserRelayClient
        relay = CloudBrowserRelayClient(
            config = cloudConfig,
            identity = identity,
            initialPairingCode = relayServer.publishedPairingCodeLease(),
            nextPairingPublicationEpoch = identityStore::nextPairingPublicationEpoch,
            gateway = relayServer,
            onConnectionChanged = { connected ->
                // Relay reconnects independently. Address fallback is based only on a
                // real request to the public page, not on a transient WSS reconnect.
                Log.i(
                    CLOUD_RELAY_STATE_LOG_TAG,
                    "CLOUD_RELAY_STATE connected=$connected",
                )
            },
            onPairingRegistrationConflictForPublication = {
                    publicationEpoch,
                    expectedPublication,
                ->
                serviceScope.launch {
                    if (
                        epoch == sessionEpoch &&
                        server === relayServer &&
                        cloudRelayClient === relay &&
                        relay.isCurrentPairingPublication(publicationEpoch)
                    ) {
                        relayServer.rotatePairingCodeAfterBootstrapConflict(
                            expectedPublication,
                        )
                    }
                }
            },
            onPairingRegistrationChanged = { update ->
                serviceScope.launch {
                    if (
                        epoch != sessionEpoch ||
                        server !== relayServer ||
                        cloudRelayClient !== relay ||
                        !relay.isCurrentPairingPublication(update.publicationEpoch) ||
                        update.publicationEpoch < publicationEpochFloor.get()
                    ) {
                        return@launch
                    }
                    publicationEpochFloor.set(update.publicationEpoch)
                    SessionController.updateCloudPairingRegistration(update)
                    if (update.status == CloudPairingRegistrationStatus.EXPIRED) {
                        // The Worker slot intentionally expires before the local gate's
                        // deadline. Rotate while the publication is still current so the
                        // phone never displays a cloud code during that safety margin.
                        relayServer.requestNewBrowserPairing()
                    }
                }
            },
        )
        return relay
    }

    private fun startBrowserAddressMonitor(
        cloudConfig: CloudBrowserRelayConfig,
        localPort: Int,
        probe: CloudBrowserPageProbe,
        epoch: Long,
    ) {
        browserAddressMonitorJob?.cancel()
        browserAddressMonitorJob = serviceScope.launch {
            val tracker = CloudBrowserPageAvailabilityTracker()
            var loggedAvailability = CloudBrowserPageAvailability.UNKNOWN
            while (
                isActive &&
                epoch == sessionEpoch &&
                cloudBrowserPageProbe === probe
            ) {
                val availability = tracker.record(probe.isReachable(cloudConfig.browserUrl()))
                if (availability != loggedAvailability) {
                    loggedAvailability = availability
                    Log.i(
                        CLOUD_RELAY_STATE_LOG_TAG,
                        "CLOUD_BROWSER_PAGE availability=${availability.name}",
                    )
                }
                val localUrl = "http://${LocalAddressResolver.resolveOrLoopback()}:$localPort"
                val displayedUrl = BrowserAddressPolicy.select(
                    cloudUrl = cloudConfig.browserUrl(),
                    localUrl = localUrl,
                    cloudAvailability = availability,
                )
                val currentBrowserState = SessionController.state.value
                if (currentBrowserState.browserUrl != displayedUrl ||
                    currentBrowserState.localBrowserUrl != localUrl
                ) {
                    SessionController.updateBrowserAddresses(
                        browserUrl = displayedUrl,
                        localBrowserUrl = localUrl,
                    )
                    if (currentBrowserState.browserUrl != displayedUrl) {
                        updateNotification(
                            getString(R.string.browser_ready_notification, displayedUrl),
                        )
                    }
                }
                delay(
                    when (availability) {
                        CloudBrowserPageAvailability.UNKNOWN ->
                            CLOUD_BROWSER_INITIAL_RETRY_MILLIS
                        CloudBrowserPageAvailability.REACHABLE ->
                            CLOUD_BROWSER_REACHABLE_CHECK_MILLIS
                        CloudBrowserPageAvailability.UNREACHABLE ->
                            CLOUD_BROWSER_FALLBACK_CHECK_MILLIS
                    },
                )
            }
        }
    }

    /** Uses only an already-granted coarse permission; the visible Activity owns permission UI. */
    private fun refreshNightModeLocation() {
        if (!nightModeLocationSource.hasPermission()) {
            ProjectionNightModeLocation.clear()
            latestNightModeDecision = runCatching(nightModeStateProvider::currentDecision).getOrNull()
            return
        }
        nightModeLocationSource.bestLastKnownLocation()?.let(ProjectionNightModeLocation::update)
        latestNightModeDecision = runCatching(nightModeStateProvider::currentDecision).getOrNull()
        nightModeLocationCancellation?.cancel()
        val requestEpoch = sessionEpoch
        nightModeLocationCancellation = nightModeLocationSource.requestCurrentLocation locationCallback@ { location ->
            if (requestEpoch != sessionEpoch) return@locationCallback
            if (location != null) ProjectionNightModeLocation.update(location)
            nightModeLocationCancellation = null
            latestNightModeDecision = projectionRuntime?.refreshNightMode()
                ?: runCatching(nightModeStateProvider::currentDecision).getOrNull()
        }
    }

    /**
     * Coalesces rapid UI changes. Resolution is part of AA ServiceDiscovery and the native WebRTC
     * source size, so applying a profile means an automatic transport-only reconnect rather than
     * mutating either pipeline underneath an active codec.
     */
    private fun scheduleProjectionProfileApply(
        profile: ProjectionVideoProfile,
        epoch: Long,
    ) {
        serviceScope.launch {
            if (epoch != sessionEpoch || profileControl == null || server == null) return@launch
            pendingProjectionProfile = profile
            if (profileApplyJob?.isActive == true) return@launch
            profileApplyJob = serviceScope.launch {
                try {
                    while (epoch == sessionEpoch) {
                        val target = pendingProjectionProfile ?: break
                        pendingProjectionProfile = null
                        val applied = applyProjectionProfile(target, epoch)
                        if (!applied && epoch == sessionEpoch) {
                            profileControl?.rejectApply(target)
                        }
                    }
                } finally {
                    profileApplyJob = null
                    val pending = pendingProjectionProfile
                    if (pending != null && epoch == sessionEpoch) {
                        scheduleProjectionProfileApply(pending, epoch)
                    }
                }
            }
        }
    }

    private fun currentProjectionReconfigurationCooldownDelayMillis(): Long =
        projectionReconfigurationCooldownDelayMillis(
            lastMediaAcceptedElapsedRealtime = lastProjectionMediaAcceptedElapsedRealtime,
            lastCommitElapsedRealtime = lastProjectionReconfigurationCommitElapsedRealtime,
            nowElapsedRealtime = SystemClock.elapsedRealtime(),
            minimumIntervalMillis = MIN_PROJECTION_RECONFIGURATION_INTERVAL_MILLIS,
        )

    private suspend fun applyProjectionProfile(
        profile: ProjectionVideoProfile,
        epoch: Long,
    ): Boolean {
        while (true) {
            var cooldownDelayMillis = 0L
            val result: Boolean? = projectionReconfigurationMutex.withLock {
                val manager = profileControl ?: return@withLock false
                if (epoch != sessionEpoch) return@withLock false
                val snapshot = manager.snapshot()
                if (snapshot.requestedProfile != profile) return@withLock true
                if (snapshot.activeProfile == profile) {
                    manager.confirmActive(profile)
                    return@withLock true
                }
                cooldownDelayMillis =
                    currentProjectionReconfigurationCooldownDelayMillis()
                if (cooldownDelayMillis > 0L) null else applyProjectionProfileLocked(profile, epoch)
            }
            if (result != null) return result
            Log.i(
                LOG_TAG,
                "PROJECTION_RECONFIGURATION_DEFERRED reason=PROFILE_CHANGE " +
                    "target=${profile.profileId} delayMs=$cooldownDelayMillis",
            )
            delay(cooldownDelayMillis)
        }
    }

    private suspend fun applyProjectionProfileLocked(
        profile: ProjectionVideoProfile,
        epoch: Long,
    ): Boolean {
        val manager = profileControl ?: return false
        val preflight = sessionPreflight ?: return false
        val signaling = webRtcSignaling ?: return false
        if (epoch != sessionEpoch) return false
        val snapshot = manager.snapshot()
        if (snapshot.requestedProfile != profile) return true
        if (snapshot.activeProfile == profile) {
            manager.confirmActive(profile)
            return true
        }

        publishNativeStatus(
            nativeStatus = "${preflight.summary} • AASDK RECONNECTING reason=PROFILE_CHANGE " +
                "target=${profile.profileId}",
            message = getString(R.string.projection_profile_applying),
        )

        val replacementLayout =
            ProjectionViewportTransportReplacementPolicy.replacementLayoutForProfile(
                profile = profile,
                currentConfig = activeProjectionConfig,
                portraitEncodedViewportsEnabled = viewportControl
                    ?.portraitEncodedViewportsEnabledForSession() == true,
                configuredDensityDpi = projectionDpiSettings.densityDpi(profile),
            )
        val replacementViewport = replacementLayout.encodedViewport
        val replacementConfig = replacementLayout.applyTo(
            profile.toOpenAutoConfig(
                viewport = replacementViewport,
                densityDpi = replacementLayout.densityDpi,
            ),
        )

        var candidateForCancellation: WebRtcProjectionController? = null
        val replacementController = try {
            withContext(Dispatchers.IO) {
                val candidate = WebRtcProjectionController(
                    context = applicationContext,
                    config = WebRtcProjectionConfig.forProjectionViewport(
                        profile,
                        replacementViewport,
                    ),
                    microphoneSource = browserMicrophoneSource,
                    audioStreams = browserAudioPipeline?.streamHub,
                    controlRequestHandler = { ownerKey, request ->
                        checkNotNull(server) { "Browser relay gateway is unavailable" }
                            .dispatchWebRtcControl(ownerKey, request)
                    },
                    preferredMainDeviceId = browserTrustStore::preferredMainDeviceId,
                    claimMainDeviceIfUnset = ::claimPreferredMainBrowserDeviceIfUnset,
                    sessionAdmissionLock = browserSessionAdmissionLock,
                )
                candidateForCancellation = candidate
                candidate.start().fold(
                    onSuccess = { candidate },
                    onFailure = {
                        candidate.close()
                        null
                    },
                )
            }
        } catch (error: CancellationException) {
            candidateForCancellation?.close()
            throw error
        } catch (error: Throwable) {
            candidateForCancellation?.close()
            Log.w(
                WEBRTC_LOG_TAG,
                "WEBRTC_PROFILE_REPLACEMENT_FAILED ${error.javaClass.simpleName}: " +
                    error.message,
            )
            null
        }
        if (epoch != sessionEpoch) {
            replacementController?.close()
            return false
        }
        if (
            !ProjectionViewportTransportReplacementPolicy.canCommit(
                currentWebRtcAvailable = webRtcController?.capabilities()?.available == true,
                replacementWebRtcAvailable =
                    replacementController?.capabilities()?.available == true,
            )
        ) {
            replacementController?.close()
            Log.w(
                WEBRTC_LOG_TAG,
                "PROFILE_APPLY_REJECTED reason=WEBRTC_REPLACEMENT_UNAVAILABLE " +
                    "size=${replacementViewport.width}x${replacementViewport.height}",
            )
            return false
        }

        val replacementCoordinator = OpenAutoCoordinator(
            engine = BenchOpenAutoEngine(),
            safetyGate = SafetyGate(),
            config = replacementConfig,
            inputSink = object : OpenAutoInputSink {
                override fun sendTouch(event: OpenAutoTouchEvent): Boolean =
                    projectionRuntime?.sendTouch(event) == true

                override fun sendKey(event: OpenAutoKeyEvent): Boolean =
                    projectionRuntime?.sendKey(event) == true
            },
        )
        if (replacementCoordinator.start(VehicleState.BENCH_STATIONARY).isFailure) {
            replacementController?.close()
            publishNativeStatus(
                nativeStatus = "${preflight.summary} • AASDK PROFILE_APPLY_FAILED reason=SAFETY_GATE",
                message = getString(R.string.projection_profile_safety_cancelled),
            )
            return false
        }

        // Codec probing can outlive a newer profile selection. Do not tear down the healthy
        // runtime for a candidate that is no longer authoritative.
        if (epoch != sessionEpoch || manager.snapshot().requestedProfile != profile) {
            replacementController?.close()
            replacementCoordinator.stop()
            return epoch == sessionEpoch
        }
        if (currentProjectionReconfigurationCooldownDelayMillis() > 0L) {
            // The old AA runtime may have completed a reconnect while encoder probing was in
            // flight. Preserve the latest request, discard the prepared candidate, and let the
            // outer admission loop wait without holding the shared reconfiguration mutex.
            replacementController?.close()
            replacementCoordinator.stop()
            pendingProjectionProfile = profile
            return true
        }

        // Commit order: stop AA input/decoder first, install the new geometry and optional WebRTC
        // source, then perform a fresh ServiceDiscovery exchange. The HTTP server and pairing
        // survive. If no browser encoder exists, AA continues on the service-owned JPEG decoder.
        val previousRuntime = projectionRuntime
        projectionRuntime = null
        projectionDecoderController = null
        projectionDecoderFrameCapture = null
        val previousProjectionJob = projectionJob
        projectionJob = null
        try {
            previousRuntime?.close()
            previousProjectionJob?.cancelAndJoin()
        } catch (error: Throwable) {
            replacementController?.close()
            replacementCoordinator.stop()
            throw error
        }

        val controllerCommitted = replaceBrowserSessionControllerAtomically(
            lock = browserSessionAdmissionLock,
            current = { webRtcController },
            replacement = replacementController,
            replaceDelegate = { expected, replacement ->
                signaling.replaceIfCurrent(
                    expected ?: BrowserWebRtcSignaling.Unavailable,
                    replacement ?: BrowserWebRtcSignaling.Unavailable,
                )
            },
            updateCurrent = { webRtcController = it },
            retire = { previous ->
                previous.detachPreviewSurface()
                previous.close()
            },
        )
        if (!controllerCommitted) {
            replacementController?.close()
            replacementCoordinator.stop()
            return false
        }
        activeProjectionConfig = replacementConfig

        replaceBrowserFrameCapture(profile, replacementViewport)

        coordinator?.stop()
        coordinator = replacementCoordinator
        attachWebRtcPreviewToCurrentSurface()

        manager.confirmActive(profile)
        viewportControl?.activateProfile(profile, replacementLayout)
        lastProjectionReconfigurationCommitElapsedRealtime = SystemClock.elapsedRealtime()
        launchProjection(
            preflight = preflight,
            benchConfirmed = sessionBenchConfirmed,
            projectionConfig = replacementConfig,
            epoch = epoch,
        )
        val replacementWebRtcAvailable =
            replacementController?.capabilities()?.available == true
        publishNativeStatus(
            nativeStatus = "${preflight.summary} • AASDK PROFILE_APPLIED ${profile.profileId} " +
                "RECONNECTING WEBRTC=${if (replacementWebRtcAvailable) "READY" else "UNAVAILABLE"}",
            message = if (!replacementWebRtcAvailable) {
                getString(
                    R.string.projection_profile_applied_jpeg_fallback,
                    replacementViewport.width,
                    replacementViewport.height,
                )
            } else {
                getString(
                    R.string.projection_profile_applied_reconnecting,
                    replacementViewport.width,
                    replacementViewport.height,
                )
            },
        )
        return true
    }

    /**
     * Browser resize events are last-write-wins. A short settle window prevents browser chrome
     * animation from causing an Android Auto ServiceDiscovery reconnect for every intermediate
     * width while still applying a stable narrow driving viewport automatically.
     */
    private fun scheduleProjectionViewportApply(
        layout: ProjectionViewportLayout,
        epoch: Long,
    ) {
        serviceScope.launch {
            if (epoch != sessionEpoch || viewportControl == null || server == null) return@launch
            pendingProjectionViewport = layout
            if (viewportApplyJob?.isActive == true) return@launch
            viewportApplyJob = serviceScope.launch {
                try {
                    while (epoch == sessionEpoch) {
                        delay(VIEWPORT_APPLY_SETTLE_MILLIS)
                        val target = pendingProjectionViewport ?: break
                        pendingProjectionViewport = null
                        val applied = applyProjectionViewport(target, epoch)
                        if (!applied && epoch == sessionEpoch) {
                            viewportControl?.rejectApply(target)
                        }
                    }
                } finally {
                    viewportApplyJob = null
                    val pending = pendingProjectionViewport
                    if (pending != null && epoch == sessionEpoch) {
                        scheduleProjectionViewportApply(pending, epoch)
                    }
                }
            }
        }
    }

    private suspend fun applyProjectionViewport(
        layout: ProjectionViewportLayout,
        epoch: Long,
    ): Boolean {
        while (true) {
            var cooldownDelayMillis = 0L
            val result: Boolean? = projectionReconfigurationMutex.withLock {
                val manager = viewportControl ?: return@withLock false
                if (epoch != sessionEpoch) return@withLock false
                val snapshot = manager.snapshot()
                if (snapshot.requestedLayout != layout) return@withLock true
                if (snapshot.activeLayout == layout) {
                    manager.confirmActive(layout)
                    return@withLock true
                }
                cooldownDelayMillis =
                    currentProjectionReconfigurationCooldownDelayMillis()
                if (cooldownDelayMillis > 0L) null else applyProjectionViewportLocked(layout, epoch)
            }
            if (result != null) return result
            Log.i(
                LOG_TAG,
                "PROJECTION_RECONFIGURATION_DEFERRED reason=VIEWPORT_CHANGE " +
                    "delayMs=$cooldownDelayMillis",
            )
            delay(cooldownDelayMillis)
        }
    }

    private suspend fun applyProjectionViewportLocked(
        layout: ProjectionViewportLayout,
        epoch: Long,
    ): Boolean {
        val manager = viewportControl ?: return false
        val preflight = sessionPreflight ?: return false
        if (epoch != sessionEpoch) return false
        val viewportSnapshot = manager.snapshot()
        if (viewportSnapshot.requestedLayout != layout) return true
        if (viewportSnapshot.activeLayout == layout) {
            manager.confirmActive(layout)
            return true
        }

        val profileSnapshot = profileControl?.snapshot() ?: return false
        if (profileSnapshot.activationState !=
            com.eigenkodex.navonweb.openauto.ProjectionProfileActivationState.ACTIVE
        ) {
            return false
        }
        val activeProfile = profileSnapshot.activeProfile
        if (!activeProfile.supportsEncodedViewport(layout.encodedViewport)) {
            return false
        }
        check(
            layout.densityDpi == activeProfile.effectiveDensityDpi(
                projectionDpiSettings.densityDpi(activeProfile),
                layout.encodedViewport,
            ),
        ) {
            "viewport changes must preserve the effective profile density"
        }
        val replacementConfig = layout.applyTo(
            activeProfile.toOpenAutoConfig(
                viewport = layout.encodedViewport,
                densityDpi = layout.densityDpi,
            ),
        )
        val encodedViewportChanged =
            ProjectionViewportTransportReplacementPolicy.requiresMediaPipelineReplacement(
                currentViewport = activeProjectionConfig.viewport,
                requestedViewport = layout.encodedViewport,
            )
        var candidateForCancellation: WebRtcProjectionController? = null
        val replacementController = if (encodedViewportChanged) {
            try {
                withContext(Dispatchers.IO) {
                    val candidate = WebRtcProjectionController(
                        context = applicationContext,
                        config = WebRtcProjectionConfig.forProjectionViewport(
                            activeProfile,
                            layout.encodedViewport,
                        ),
                        microphoneSource = browserMicrophoneSource,
                        audioStreams = browserAudioPipeline?.streamHub,
                        controlRequestHandler = { ownerKey, request ->
                            checkNotNull(server) { "Browser relay gateway is unavailable" }
                                .dispatchWebRtcControl(ownerKey, request)
                        },
                        preferredMainDeviceId = browserTrustStore::preferredMainDeviceId,
                        claimMainDeviceIfUnset = ::claimPreferredMainBrowserDeviceIfUnset,
                        sessionAdmissionLock = browserSessionAdmissionLock,
                    )
                    candidateForCancellation = candidate
                    candidate.start().fold(
                        onSuccess = { candidate },
                        onFailure = {
                            candidate.close()
                            null
                        },
                    )
                }
            } catch (error: CancellationException) {
                candidateForCancellation?.close()
                throw error
            } catch (error: Throwable) {
                candidateForCancellation?.close()
                Log.w(
                    WEBRTC_LOG_TAG,
                    "WEBRTC_VIEWPORT_REPLACEMENT_FAILED ${error.javaClass.simpleName}: " +
                        error.message,
                )
                null
            }
        } else {
            null
        }
        // Do not downgrade a healthy WebRTC session to JPEG because the device encoder rejected
        // a portrait size. In JPEG-only local sessions there is no native sender to preserve, so
        // the rotated fallback capture remains a valid path.
        if (
            encodedViewportChanged &&
            !ProjectionViewportTransportReplacementPolicy.canCommit(
                currentWebRtcAvailable = webRtcController?.capabilities()?.available == true,
                replacementWebRtcAvailable =
                    replacementController?.capabilities()?.available == true,
            )
        ) {
            replacementController?.close()
            Log.w(
                WEBRTC_LOG_TAG,
                "VIEWPORT_APPLY_REJECTED reason=WEBRTC_REPLACEMENT_UNAVAILABLE " +
                    "size=${layout.encodedViewport.width}x${layout.encodedViewport.height}",
            )
            return false
        }
        val replacementSignaling = if (encodedViewportChanged) {
            webRtcSignaling ?: run {
                replacementController?.close()
                return false
            }
        } else {
            null
        }
        val replacementCoordinator = OpenAutoCoordinator(
            engine = BenchOpenAutoEngine(),
            safetyGate = SafetyGate(),
            config = replacementConfig,
            inputSink = object : OpenAutoInputSink {
                override fun sendTouch(event: OpenAutoTouchEvent): Boolean =
                    projectionRuntime?.sendTouch(event) == true

                override fun sendKey(event: OpenAutoKeyEvent): Boolean =
                    projectionRuntime?.sendKey(event) == true
            },
        )
        if (replacementCoordinator.start(VehicleState.BENCH_STATIONARY).isFailure) {
            replacementController?.close()
            publishNativeStatus(
                nativeStatus = "${preflight.summary} — AASDK VIEWPORT_APPLY_FAILED reason=SAFETY_GATE",
                message = getString(R.string.projection_viewport_safety_cancelled),
            )
            return false
        }

        // Encoder probing and coordinator preparation suspend. A newer resize can win meanwhile;
        // discard this candidate before touching the healthy active runtime.
        if (epoch != sessionEpoch || manager.snapshot().requestedLayout != layout) {
            replacementController?.close()
            replacementCoordinator.stop()
            return epoch == sessionEpoch
        }
        if (currentProjectionReconfigurationCooldownDelayMillis() > 0L) {
            replacementController?.close()
            replacementCoordinator.stop()
            pendingProjectionViewport = layout
            return true
        }

        publishNativeStatus(
            nativeStatus = "${preflight.summary} — AASDK RECONNECTING reason=VIEWPORT_CHANGE " +
                "marginWidth=${layout.totalMarginWidth} marginHeight=${layout.totalMarginHeight} " +
                "densityDpi=${layout.densityDpi}",
            message = getString(R.string.projection_viewport_applying),
        )

        val previousRuntime = projectionRuntime
        projectionRuntime = null
        projectionDecoderController = null
        projectionDecoderFrameCapture = null
        val previousProjectionJob = projectionJob
        projectionJob = null
        try {
            previousRuntime?.close()
            previousProjectionJob?.cancelAndJoin()
        } catch (error: Throwable) {
            // Neither candidate has been installed yet. Cancellation or join failure must not
            // leak its EGL/codec resources or leave a started coordinator behind.
            replacementController?.close()
            replacementCoordinator.stop()
            throw error
        }
        if (epoch != sessionEpoch) {
            replacementController?.close()
            replacementCoordinator.stop()
            return false
        }

        if (encodedViewportChanged) {
            val controllerCommitted = replaceBrowserSessionControllerAtomically(
                lock = browserSessionAdmissionLock,
                current = { webRtcController },
                replacement = replacementController,
                replaceDelegate = { expected, replacement ->
                    checkNotNull(replacementSignaling).replaceIfCurrent(
                        expected ?: BrowserWebRtcSignaling.Unavailable,
                        replacement ?: BrowserWebRtcSignaling.Unavailable,
                    )
                },
                updateCurrent = { webRtcController = it },
                retire = { previous ->
                    previous.detachPreviewSurface()
                    previous.close()
                },
            )
            if (!controllerCommitted) {
                replacementController?.close()
                replacementCoordinator.stop()
                return false
            }
            replaceBrowserFrameCapture(activeProfile, layout.encodedViewport)
        }
        coordinator?.stop()
        coordinator = replacementCoordinator
        activeProjectionConfig = replacementConfig
        manager.confirmTransportActive(layout)
        lastProjectionReconfigurationCommitElapsedRealtime = SystemClock.elapsedRealtime()
        if (encodedViewportChanged) {
            attachWebRtcPreviewToCurrentSurface()
        }
        launchProjection(
            preflight = preflight,
            benchConfirmed = sessionBenchConfirmed,
            projectionConfig = replacementConfig,
            epoch = epoch,
        )
        Log.i(
            LOG_TAG,
            "VIEWPORT_APPLIED marginWidth=${layout.totalMarginWidth} " +
                "marginHeight=${layout.totalMarginHeight} densityDpi=${layout.densityDpi} " +
                if (encodedViewportChanged) {
                    "WEBRTC_MEDIA_RENEGOTIATING SIGNALING_SESSION_PRESERVED"
                } else {
                    "WEBRTC_SESSION_PRESERVED"
                },
        )
        publishNativeStatus(
            nativeStatus = "${preflight.summary} — AASDK VIEWPORT_APPLIED " +
                "marginWidth=${layout.totalMarginWidth} marginHeight=${layout.totalMarginHeight} " +
                "densityDpi=${layout.densityDpi} " +
                if (encodedViewportChanged) {
                    "RECONNECTING WEBRTC_MEDIA=RENEGOTIATING SIGNALING_SESSION=PRESERVED"
                } else {
                    "RECONNECTING WEBRTC_SESSION=PRESERVED"
                },
            message = getString(R.string.projection_viewport_applied),
        )
        return true
    }

    private fun launchProjection(
        preflight: OpenAutoPreflight,
        benchConfirmed: Boolean,
        projectionConfig: OpenAutoConfig,
        epoch: Long,
    ) {
        portraitMediaAcceptanceWatchdogJob?.cancel()
        portraitMediaAcceptanceWatchdogJob = null
        val config = preflight.runtimeConfig(benchConfirmed, projectionConfig)
        if (config == null) {
            val reason = when {
                preflight.endpointHost.isBlank() -> "DISABLED"
                preflight.credential == null -> "CREDENTIAL_UNAVAILABLE"
                else -> "PREFLIGHT_UNAVAILABLE"
            }
            publishNativeStatus("${preflight.summary} • AASDK $reason")
            return
        }

        connectBrowserFrameSink()
        val decoderController = webRtcController
        val nativeDecoderSurface = decoderController
            ?.decoderSurface()
            ?.takeIf(Surface::isValid)
        val fallbackCapture = browserFrameCapture
        val fallbackDecoderSurface = fallbackCapture
            ?.decoderSurface
            ?.takeIf(Surface::isValid)
        val decoderSurface = nativeDecoderSurface ?: fallbackDecoderSurface
        if (decoderSurface == null) {
            Log.e(LOG_TAG, "VIDEO_DECODER_SURFACE_UNAVAILABLE owner=SERVICE")
            publishNativeStatus("${preflight.summary} ??AASDK DECODER_SURFACE_UNAVAILABLE")
            return
        }
        // Freeze one service-owned target for this runtime. MediaCodec cannot switch Surface
        // identity while configured, so an invalid native target must stop instead of silently
        // rebinding to the fallback target.
        val videoSurfaceProvider: () -> Surface? = {
            decoderSurface.takeIf(Surface::isValid)
        }
        val runtime = AasdkProjectionRuntime(
            videoSink = MediaCodecVideoSink(videoSurfaceProvider),
            audioSink = browserAudioPipeline,
            microphoneSource = browserMicrophoneSource,
            nightModeStateProvider = nightModeStateProvider,
            onNightModeDecision = { decision ->
                val previousDecision = latestNightModeDecision
                latestNightModeDecision = decision
                if (previousDecision != decision) {
                    AppDiagnostics.record(
                        DiagnosticEventCode.NIGHT_MODE_CHANGED,
                        fields = mapOf(
                            "night" to decision.isNight,
                            "night_source" to decision.source.wireName,
                        ),
                    )
                }
                Log.i(
                    LOG_TAG,
                    "NIGHT_MODE_DECISION night=${decision.isNight} source=${decision.source.wireName}",
                )
            },
        )
        latestNightModeDecision = runtime.currentNightModeDecision()
        projectionDecoderController = decoderController.takeIf { nativeDecoderSurface != null }
        projectionDecoderFrameCapture = fallbackCapture.takeIf { nativeDecoderSurface == null }
        projectionRuntime = runtime
        attachWebRtcPreviewToCurrentSurface()
        val portraitViewport =
            projectionConfig.viewport.height > projectionConfig.viewport.width
        val mediaAttemptState = ProjectionMediaAttemptState()
        var portraitFallbackAttemptGeneration = -1L
        fun triggerPortraitProtocolFallback(
            reason: String,
            expectedAttemptGeneration: Long,
        ) {
            if (
                portraitFallbackAttemptGeneration == expectedAttemptGeneration ||
                !portraitViewport ||
                !mediaAttemptState.isAwaitingFirstVideo(expectedAttemptGeneration) ||
                epoch != sessionEpoch ||
                projectionRuntime !== runtime ||
                activeProjectionConfig != projectionConfig
            ) {
                return
            }
            portraitFallbackAttemptGeneration = expectedAttemptGeneration
            val watchdogJob = portraitMediaAcceptanceWatchdogJob
            portraitMediaAcceptanceWatchdogJob = null
            Log.w(
                LOG_TAG,
                "PORTRAIT_PROTOCOL_FALLBACK reason=$reason " +
                    "size=${projectionConfig.viewport.width}x${projectionConfig.viewport.height}",
            )
            viewportControl?.disablePortraitEncodedViewportsForSession()
            // Interrupt the failed read/reconnect loop. The idempotent viewport fallback
            // scheduler starts a landscape+margin session after the shared cooldown admits it.
            runtime.close()
            watchdogJob?.cancel()
        }
        projectionJob = serviceScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    runtime.runWithReconnect(config) { status ->
                        withContext(Dispatchers.Main.immediate) {
                            if (epoch == sessionEpoch && projectionRuntime === runtime) {
                                if (status == CONNECTING_STATUS) {
                                    mediaAttemptState.beginAttempt()
                                    portraitMediaAcceptanceWatchdogJob?.cancel()
                                    portraitMediaAcceptanceWatchdogJob = null
                                } else if (status.startsWith(VIDEO_MEDIA_ACCEPTED_STATUS)) {
                                    if (mediaAttemptState.markVideoMediaAccepted()) {
                                        lastProjectionMediaAcceptedElapsedRealtime =
                                            SystemClock.elapsedRealtime()
                                    }
                                    portraitMediaAcceptanceWatchdogJob?.cancel()
                                    portraitMediaAcceptanceWatchdogJob = null
                                } else if (
                                    status.startsWith(SERVICE_DISCOVERY_RESPONSE_SENT_STATUS) &&
                                    portraitViewport &&
                                    viewportControl
                                        ?.portraitEncodedViewportsEnabledForSession() == true &&
                                    portraitMediaAcceptanceWatchdogJob == null
                                ) {
                                    val watchdogAttemptGeneration =
                                        mediaAttemptState.markServiceDiscoveryResponseSent()
                                    portraitMediaAcceptanceWatchdogJob = serviceScope.launch {
                                        delay(PORTRAIT_MEDIA_ACCEPTANCE_TIMEOUT_MILLIS)
                                        triggerPortraitProtocolFallback(
                                            "VIDEO_MEDIA_ACCEPT_TIMEOUT",
                                            watchdogAttemptGeneration,
                                        )
                                    }
                                }
                                if (
                                    shouldTriggerPortraitPreVideoFallback(
                                        portraitViewport = portraitViewport,
                                        serviceDiscoveryResponseSent =
                                        mediaAttemptState.serviceDiscoveryResponseSent,
                                        videoMediaAccepted = mediaAttemptState.videoMediaAccepted,
                                        runtimeStatus = status,
                                    )
                                ) {
                                    triggerPortraitProtocolFallback(
                                        if (status.startsWith("DISCONNECTED reason=")) {
                                            "DISCONNECTED_BEFORE_VIDEO"
                                        } else {
                                            "VIDEO_STREAM_STOPPED_BEFORE_VIDEO"
                                        },
                                        mediaAttemptState.generation,
                                    )
                                }
                                publishNativeStatus(
                                    "${preflight.summary} • AASDK ${endpointAwareStatus(preflight, status)}",
                                )
                            }
                        }
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                if (epoch == sessionEpoch && projectionRuntime === runtime) {
                    triggerPortraitProtocolFallback(
                        "TERMINAL_BEFORE_VIDEO_${error.javaClass.simpleName}",
                        mediaAttemptState.generation,
                    )
                    AppDiagnostics.recordFailure(
                        DiagnosticEventCode.SESSION_FAILED,
                        error,
                        fields = mapOf("stage" to "projection_runtime"),
                    )
                    Log.e(
                        LOG_TAG,
                        "Projection runtime failed: ${error.javaClass.simpleName}: ${error.message}",
                    )
                    publishNativeStatus(
                        "${preflight.summary} • AASDK ${safeProjectionFailure(error)}",
                    )
                } else {
                    Log.i(LOG_TAG, "Projection runtime closed intentionally")
                }
            } finally {
                runtime.close()
                if (epoch == sessionEpoch && projectionRuntime === runtime) {
                    portraitMediaAcceptanceWatchdogJob?.cancel()
                    portraitMediaAcceptanceWatchdogJob = null
                    projectionRuntime = null
                    projectionDecoderController = null
                    projectionDecoderFrameCapture = null
                    projectionJob = null
                    webRtcController?.detachPreviewSurface()
                    synchronized(projectionSurfaceLock) { projectionSurfaceGeneration }
                        .let { generation -> browserFrameCapture?.detach(generation) }
                }
            }
        }
    }

    private fun safeProjectionFailure(error: Throwable): String = when (error) {
        is AasdkPeerShutdownException -> "PEER_SHUTDOWN"
        is AasdkDecoderUnavailableException -> "DECODER_UNAVAILABLE"
        is AasdkVersionMismatchException ->
            "VERSION_MISMATCH peer=${error.peerMajorVersion}.${error.peerMinorVersion}"
        is AasdkTlsPolicyException -> "TLS_POLICY_REJECTED"
        is AasdkTlsEngineException -> "TLS_HANDSHAKE_FAILED"
        is AasdkProtocolException -> "PROTOCOL_ERROR"
        is EOFException -> "PEER_DISCONNECTED"
        is IOException -> "TRANSPORT_DISCONNECTED"
        is SecurityException -> "SAFETY_OR_CREDENTIAL_REJECTED"
        else -> "SESSION_FAILED"
    }

    private fun endpointAwareStatus(
        preflight: OpenAutoPreflight,
        status: String,
    ): String = if (
        preflight.endpointMode == ProjectionEndpointMode.THIS_PHONE &&
        "reason=CONNECT_FAILED" in status
    ) {
        status.replace(
            oldValue = "reason=CONNECT_FAILED",
            newValue = "reason=HEAD_UNIT_SERVER_NOT_RUNNING",
        )
    } else {
        status
    }

    private fun attachProjectionSurface(surface: Surface, generation: Long): Boolean {
        if (generation <= NO_SURFACE_GENERATION || !surface.isValid) return false
        val attached = synchronized(projectionSurfaceLock) {
            when {
                generation < projectionSurfaceGeneration -> false
                generation == projectionSurfaceGeneration &&
                    projectionSurface != null && projectionSurface !== surface -> false
                else -> {
                    projectionSurface = surface
                    projectionSurfaceGeneration = generation
                    true
                }
            }
        }
        if (attached) {
            AppDiagnostics.record(
                DiagnosticEventCode.PROJECTION_SURFACE_CHANGED,
                fields = mapOf(
                    "state" to "attached",
                    "width" to activeProjectionConfig.viewport.width,
                    "height" to activeProjectionConfig.viewport.height,
                ),
            )
            Log.i(
                LOG_TAG,
                "VIDEO_SURFACE_ATTACHED generation=$generation " +
                    "size=${activeProjectionConfig.viewport.width}x" +
                    "${activeProjectionConfig.viewport.height}",
            )
            attachWebRtcPreviewToCurrentSurface()
        }
        return attached
    }

    private fun detachProjectionSurface(generation: Long) {
        val detached = synchronized(projectionSurfaceLock) {
            if (generation != projectionSurfaceGeneration || projectionSurface == null) {
                false
            } else {
                projectionSurface = null
                true
            }
        }
        if (!detached) return

        webRtcController?.detachPreviewSurface(generation)
        browserFrameCapture?.detach(generation)
        AppDiagnostics.record(
            DiagnosticEventCode.PROJECTION_SURFACE_CHANGED,
            fields = mapOf("state" to "detached"),
        )
        Log.i(LOG_TAG, "VIDEO_SURFACE_DETACHED generation=$generation")

        val controller = projectionDecoderController
        val detachAction = ProjectionSurfaceLossPolicy.decide(
            runtimeUsesNativeWebRtcDecoder = controller != null,
            nativeWebRtcDecoderSurfaceValid = controller?.decoderSurface()?.isValid == true,
            fallbackDecoderSurfaceValid = projectionDecoderFrameCapture
                ?.decoderSurface
                ?.isValid == true,
        )
        if (detachAction == ProjectionSurfaceDetachAction.KEEP_RUNTIME) {
            // MediaCodec and browser capture use a service-owned SurfaceTexture. Only the
            // Activity preview disappeared, so AA and browser frame counters keep advancing.
            val owner = if (controller != null) "WEBRTC" else "JPEG_FALLBACK"
            Log.i(LOG_TAG, "VIDEO_SURFACE_BACKGROUND_CONTINUE decoder=$owner generation=$generation")
        } else {
            stopProjectionForSurfaceLoss()
        }
    }

    private fun detachCurrentProjectionSurface() {
        val generation = synchronized(projectionSurfaceLock) {
            if (projectionSurface == null) null else projectionSurfaceGeneration
        } ?: return
        detachProjectionSurface(generation)
    }

    private fun replaceBrowserFrameCapture(
        profile: ProjectionVideoProfile,
        viewport: VideoViewport = VideoViewport(profile.width, profile.height),
    ) {
        val frameStore = browserFrameStore ?: return
        // A controller swap happens before the geometry-specific capture replacement. Keep the
        // old capture connected if replacement allocation fails, then commit the new owner.
        connectBrowserFrameSink()
        val replacement = createBrowserFrameCapture(frameStore, profile, viewport)
        webRtcController?.setBrowserFrameSink(null)
        val previous = browserFrameCapture
        browserFrameCapture = replacement
        connectBrowserFrameSink()
        previous?.close()
    }

    private fun attachWebRtcPreviewToCurrentSurface() {
        val target = synchronized(projectionSurfaceLock) {
            val surface = projectionSurface?.takeIf(Surface::isValid) ?: return@synchronized null
            surface to projectionSurfaceGeneration
        } ?: return
        val controller = if (projectionRuntime != null) {
            projectionDecoderController
        } else {
            webRtcController?.takeIf { it.decoderSurface()?.isValid == true }
        }
        if (controller != null) {
            // Owner switching is not an Activity stale-callback check. Unconditionally retire the
            // inactive producer before the selected owner connects the native window target.
            browserFrameCapture?.detach()
            controller.attachPreviewSurface(target.first, target.second).onFailure { error ->
                Log.w(
                    WEBRTC_LOG_TAG,
                    "WEBRTC_PREVIEW_ATTACH_FAILED ${error.javaClass.simpleName}: ${error.message}",
                )
            }
        } else {
            webRtcController?.detachPreviewSurface()
            runCatching { browserFrameCapture?.attach(target.first, target.second) }
                .onFailure { error ->
                    Log.w(
                        WEBRTC_LOG_TAG,
                        "JPEG_FALLBACK_PREVIEW_ATTACH_FAILED " +
                            "${error.javaClass.simpleName}: ${error.message}",
                    )
                }
        }
    }

    private fun connectBrowserFrameSink() {
        webRtcController?.setBrowserFrameSink(browserFrameCapture)
    }

    private fun stopProjectionForSurfaceLoss() {
        val runtime = projectionRuntime ?: return
        portraitMediaAcceptanceWatchdogJob?.cancel()
        portraitMediaAcceptanceWatchdogJob = null
        projectionRuntime = null
        projectionDecoderController = null
        projectionDecoderFrameCapture = null
        val activeJob = projectionJob
        projectionJob = null

        // MediaCodecVideoSink.stop() completes before close() returns, so a
        // UI-owned or otherwise invalid decoder Surface is no longer used when
        // surfaceDestroyed completes.
        runtime.close()
        activeJob?.cancel()
        publishNativeStatus(
            nativeStatus = "AASDK SURFACE_LOST",
            message = getString(R.string.projection_surface_lost),
        )
    }

    private fun stopSession(message: String) {
        if (stopJob?.isActive == true) return
        ProjectionAutomationSessionOwnership.clear()
        AppDiagnostics.record(
            DiagnosticEventCode.SESSION_STOPPED,
            fields = mapOf("reason" to "user_or_service_request"),
        )
        stopJob = serviceScope.launch {
            withContext(Dispatchers.IO) {
                releaseResources()
            }
            SessionController.idle(message)
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    /** Idempotent fail-closed order: input gate, socket, jobs, then browser. */
    private fun releaseResources() {
        sessionEpoch += 1
        clearConnectionNotifications()
        nightModeLocationCancellation?.cancel()
        nightModeLocationCancellation = null

        val activeProfileApplyJob = profileApplyJob
        profileApplyJob = null
        pendingProjectionProfile = null
        activeProfileApplyJob?.cancel()
        val activeViewportApplyJob = viewportApplyJob
        viewportApplyJob = null
        pendingProjectionViewport = null
        activeViewportApplyJob?.cancel()
        val activeDiagnosticHeartbeatJob = diagnosticHeartbeatJob
        diagnosticHeartbeatJob = null
        activeDiagnosticHeartbeatJob?.cancel()
        profileControl = null
        viewportControl = null
        sessionPreflight = null
        sessionBenchConfirmed = false
        lastProjectionMediaAcceptedElapsedRealtime = 0L
        lastProjectionReconfigurationCommitElapsedRealtime = 0L

        coordinator?.stop()
        coordinator = null

        val runtime = projectionRuntime
        projectionRuntime = null
        projectionDecoderController = null
        projectionDecoderFrameCapture = null
        runtime?.close()

        val activeProjectionJob = projectionJob
        projectionJob = null
        activeProjectionJob?.cancel()
        val activePortraitWatchdogJob = portraitMediaAcceptanceWatchdogJob
        portraitMediaAcceptanceWatchdogJob = null
        activePortraitWatchdogJob?.cancel()

        val activeStartJob = startJob
        startJob = null
        activeStartJob?.cancel()

        val activeBrowserAddressMonitorJob = browserAddressMonitorJob
        browserAddressMonitorJob = null
        activeBrowserAddressMonitorJob?.cancel()

        val activeCloudBrowserPageProbe = cloudBrowserPageProbe
        cloudBrowserPageProbe = null
        activeCloudBrowserPageProbe?.close()

        val activeCloudRelayClient = cloudRelayClient
        cloudRelayClient = null
        activeCloudRelayClient?.close()

        server?.close()
        server = null

        val activeLanStunServer = lanStunServer
        lanStunServer = null
        activeLanStunServer?.close()

        browserAudioPipeline?.close()
        browserAudioPipeline = null
        browserMicrophoneSource?.close()
        browserMicrophoneSource = null
        latestNightModeDecision = null

        val activeWebRtcController = webRtcController
        webRtcController = null
        webRtcSignaling?.replace(BrowserWebRtcSignaling.Unavailable)
        webRtcSignaling = null
        activeWebRtcController?.close()

        browserFrameCapture?.close()
        browserFrameCapture = null

        browserFrameStore?.close()
        browserFrameStore = null
        activeProjectionConfig = ProjectionVideoProfile.FREE_800X480.toOpenAutoConfig()
    }

    override fun onDestroy() {
        val phase = SessionController.state.value.phase
        AppDiagnostics.record(
            DiagnosticEventCode.SERVICE_DESTROYED,
            fields = mapOf("session_phase" to phase.name),
        )
        releaseResources()
        ProjectionAutomationSessionOwnership.clear()
        synchronized(projectionSurfaceLock) {
            projectionSurface = null
            projectionSurfaceGeneration = NO_SURFACE_GENERATION
        }
        if (phase == SessionPhase.STARTING || phase == SessionPhase.READY) {
            SessionController.idle(getString(R.string.session_closed_after_service_stop))
        }
        noticeRefreshJob?.cancel()
        noticeRefreshJob = null
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun startDiagnosticHeartbeat(epoch: Long) {
        diagnosticHeartbeatJob?.cancel()
        diagnosticHeartbeatJob = serviceScope.launch {
            while (isActive && epoch == sessionEpoch) {
                kotlinx.coroutines.delay(DIAGNOSTIC_HEARTBEAT_MILLIS)
                if (epoch == sessionEpoch) recordRuntimeSnapshot()
            }
        }
    }

    /** Emits only bounded state, enums and aggregate counters; no URLs, coordinates or payloads. */
    private fun recordRuntimeSnapshot() {
        val session = SessionController.state.value
        val connection = session.androidAutoConnection
        val profile = profileControl?.snapshot()?.activeProfile
            ?: ProjectionVideoProfile.FREE_800X480
        val capabilities = webRtcController?.capabilities()
        val audioTracks = browserAudioPipeline?.streamHub?.snapshot().orEmpty()
        val audioFormats = audioTracks.entries
            .sortedBy { it.key.ordinal }
            .joinToString(",") { (track, snapshot) ->
                "${track.wireName}:${snapshot.format.sampleRateHz}x${snapshot.format.channelCount}"
            }
            .ifBlank { "none" }
        val audioPublished = audioTracks.values.sumOf { it.packetsPublished }
        val audioDropped = audioTracks.values.sumOf { it.packetsDropped }
        val audioSubscribers = audioTracks.values.sumOf { it.activeSubscribers }
        val frameAge = connection.lastFrameAgeMillis(SystemClock.elapsedRealtime())?.let { age ->
            when {
                age < 5_000L -> "recent"
                age < 30_000L -> "delayed"
                else -> "stale"
            }
        } ?: "none"
        AppDiagnostics.record(
            DiagnosticEventCode.RUNTIME_SNAPSHOT,
            fields = mapOf(
                "session_phase" to session.phase.name,
                "connection_state" to connection.state.name,
                "connection_reason" to connection.reason.name,
                "last_frame_age" to frameAge,
                "profile" to "${profile.name}:${profile.width}x${profile.height}",
                "webrtc" to if (capabilities == null) {
                    "unavailable"
                } else {
                    "available=${capabilities.available},codecs=" +
                        capabilities.codecs.joinToString(",") { it.wireName }
                },
                "night" to latestNightModeDecision?.isNight,
                "night_source" to latestNightModeDecision?.source?.wireName,
                "audio" to "tier=${browserAudioPipeline?.accessTier?.name ?: "none"}," +
                    "formats=$audioFormats,published=$audioPublished,dropped=$audioDropped," +
                    "subscribers=$audioSubscribers",
                "video_events" to videoEventsSinceDiagnostic.getAndSet(0L),
                "touch_events" to touchEventsSinceDiagnostic.getAndSet(0L),
            ),
        )
    }

    private fun buildNotification(text: String): Notification {
        createNotificationChannel()
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stopIntent = PendingIntent.getService(
            this,
            1,
            stopIntent(this),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(NavOnWebNotificationAssets.smallIcon)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setContentIntent(openIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(0, getString(R.string.notification_stop_action), stopIntent)
            .build()
    }

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification(text))
    }

    private fun publishNativeStatus(nativeStatus: String, message: String? = null) {
        if ("VIDEO_MEDIA_ACCEPTED" in nativeStatus) {
            videoEventsSinceDiagnostic.incrementAndGet()
        }
        val previousConnection = SessionController.state.value.androidAutoConnection
        SessionController.updateNativeStatus(nativeStatus, message)
        val connection = SessionController.state.value.androidAutoConnection
        if (
            previousConnection.state != connection.state ||
            previousConnection.reason != connection.reason
        ) {
            AppDiagnostics.record(
                DiagnosticEventCode.CONNECTION_CHANGED,
                level = if (connection.state == AndroidAutoConnectionState.DISCONNECTED) {
                    DiagnosticLevel.WARN
                } else {
                    DiagnosticLevel.INFO
                },
                fields = mapOf(
                    "state" to connection.state.name,
                    "reason" to connection.reason.name,
                ),
            )
        }
        updateConnectionNotifications(connection)
    }

    private fun updateConnectionNotifications(connection: AndroidAutoConnectionStatus) {
        val stateLabel = when (connection.state) {
            AndroidAutoConnectionState.CONNECTED -> getString(R.string.android_auto_state_connected)
            AndroidAutoConnectionState.RECONNECTING ->
                getString(R.string.android_auto_state_reconnecting)
            AndroidAutoConnectionState.DISCONNECTED ->
                getString(R.string.android_auto_state_disconnected)
        }
        val foregroundText = "$stateLabel • ${getString(connection.reason.messageRes)}"
        if (foregroundText != lastForegroundConnectionText) {
            lastForegroundConnectionText = foregroundText
            updateNotification(foregroundText)
        }

        if (connection.state == AndroidAutoConnectionState.CONNECTED) {
            if (
                !wasAndroidAutoConnected &&
                !projectionStartupStore.recordSuccessfulProjection()
            ) {
                Log.w(LOG_TAG, "PROJECTION_STARTUP_HISTORY_PERSIST_FAILED")
            }
            wasAndroidAutoConnected = true
            if (connectionAlertPosted) {
                getSystemService(NotificationManager::class.java).cancel(CONNECTION_ALERT_NOTIFICATION_ID)
                connectionAlertPosted = false
            }
            return
        }

        // Initial phone discovery is visible in the ongoing notification but is
        // not an interruption. Alert once only after a previously healthy link.
        if (wasAndroidAutoConnected && !connectionAlertPosted) {
            createNotificationChannels()
            getSystemService(NotificationManager::class.java).notify(
                CONNECTION_ALERT_NOTIFICATION_ID,
                buildConnectionAlert(connection),
            )
            connectionAlertPosted = true
        }
    }

    private fun buildConnectionAlert(connection: AndroidAutoConnectionStatus): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            2,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val title = when (connection.state) {
            AndroidAutoConnectionState.RECONNECTING ->
                getString(R.string.android_auto_state_reconnecting)
            AndroidAutoConnectionState.DISCONNECTED ->
                getString(R.string.android_auto_state_disconnected)
            AndroidAutoConnectionState.CONNECTED -> getString(R.string.android_auto_state_connected)
        }
        return NotificationCompat.Builder(this, CONNECTION_ALERT_CHANNEL_ID)
            .setSmallIcon(NavOnWebNotificationAssets.smallIcon)
            .setContentTitle(title)
            .setContentText(getString(connection.reason.messageRes))
            .setContentIntent(openIntent)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(NotificationCompat.CATEGORY_ERROR)
            .build()
    }

    private fun clearConnectionNotifications() {
        getSystemService(NotificationManager::class.java).cancel(CONNECTION_ALERT_NOTIFICATION_ID)
        wasAndroidAutoConnected = false
        connectionAlertPosted = false
        lastForegroundConnectionText = null
    }

    private fun createNotificationChannel() {
        createNotificationChannels()
    }

    private fun createNotificationChannels() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.projection_notification_channel),
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
        manager.createNotificationChannel(
            NotificationChannel(
                CONNECTION_ALERT_CHANNEL_ID,
                getString(R.string.connection_notification_channel),
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = getString(R.string.connection_notification_channel_description)
            },
        )
    }

    companion object {
        private const val ACTION_START = "com.eigenkodex.navonweb.action.START"
        private const val ACTION_STOP = "com.eigenkodex.navonweb.action.STOP"
        private const val ACTION_STOP_IF_AUTOMATION_OWNER =
            "com.eigenkodex.navonweb.action.STOP_IF_AUTOMATION_OWNER"
        private const val EXTRA_BENCH_CONFIRMED = "bench_confirmed"
        private const val EXTRA_START_CANCELLATION_GENERATION = "start_cancellation_generation"
        private const val EXTRA_AUTOMATION_SOURCE = "automation_source"
        private const val EXTRA_AUTOMATION_GENERATION = "automation_generation"
        private const val CHANNEL_ID = "projection_session"
        private const val CONNECTION_ALERT_CHANNEL_ID = "android_auto_connection_alert"
        private const val NOTIFICATION_ID = 5277
        private const val CONNECTION_ALERT_NOTIFICATION_ID = 5278
        private const val LOG_TAG = "NavOnWebAasdk"
        private const val WEBRTC_LOG_TAG = "NavOnWebWebRtc"
        private const val CLOUD_RELAY_STATE_LOG_TAG = "NavOnWebCloudState"
        private const val NO_SURFACE_GENERATION = 0L
        private const val VIEWPORT_APPLY_SETTLE_MILLIS = 850L
        private const val MIN_PROJECTION_RECONFIGURATION_INTERVAL_MILLIS = 10_000L
        private const val PORTRAIT_MEDIA_ACCEPTANCE_TIMEOUT_MILLIS = 5_000L
        private const val CONNECTING_STATUS = "CONNECTING"
        private const val VIDEO_MEDIA_ACCEPTED_STATUS = "VIDEO_MEDIA_ACCEPTED"
        private const val SERVICE_DISCOVERY_RESPONSE_SENT_STATUS =
            "SERVICE_DISCOVERY_RESPONSE_SENT"
        private const val DIAGNOSTIC_HEARTBEAT_MILLIS = 60_000L
        private const val NOTICE_REFRESH_INTERVAL_MILLIS = 15L * 60L * 1_000L
        private const val CLOUD_BROWSER_INITIAL_RETRY_MILLIS = 1_500L
        private const val CLOUD_BROWSER_REACHABLE_CHECK_MILLIS = 5_000L
        private const val CLOUD_BROWSER_FALLBACK_CHECK_MILLIS = 5_000L

        internal fun cancelUpgradeStaleConnectionAlert(context: Context) {
            context.applicationContext.getSystemService(NotificationManager::class.java)
                ?.cancel(CONNECTION_ALERT_NOTIFICATION_ID)
        }

        fun start(
            context: Context,
            benchConfirmed: Boolean,
            startCancellationGeneration: Long? = null,
        ) {
            val intent = Intent(context, ProjectionService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_BENCH_CONFIRMED, benchConfirmed)
            startCancellationGeneration?.let { generation ->
                intent.putExtra(EXTRA_START_CANCELLATION_GENERATION, generation)
            }
            ContextCompat.startForegroundService(context, intent)
        }

        internal fun startForAutomation(
            context: Context,
            benchConfirmed: Boolean,
            startCancellationGeneration: Long,
            owner: AutomationProjectionOwner,
        ) {
            val intent = Intent(context, ProjectionService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_BENCH_CONFIRMED, benchConfirmed)
                .putExtra(EXTRA_START_CANCELLATION_GENERATION, startCancellationGeneration)
                .putExtra(EXTRA_AUTOMATION_SOURCE, owner.source.name)
                .putExtra(EXTRA_AUTOMATION_GENERATION, owner.generation)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.startService(stopIntent(context))
        }

        internal fun stopIfStartedByAutomation(
            context: Context,
            owner: AutomationProjectionOwner,
        ): Boolean {
            if (!ProjectionAutomationSessionOwnership.matches(owner)) return false
            return runCatching {
                context.startService(
                    Intent(context, ProjectionService::class.java)
                        .setAction(ACTION_STOP_IF_AUTOMATION_OWNER)
                        .putExtra(EXTRA_AUTOMATION_SOURCE, owner.source.name)
                        .putExtra(EXTRA_AUTOMATION_GENERATION, owner.generation),
                )
                true
            }.getOrDefault(false)
        }

        private fun stopIntent(context: Context): Intent =
            Intent(context, ProjectionService::class.java).setAction(ACTION_STOP)
    }
}

private fun WebRtcCodecPreferenceOption.toBrowserWebRtcCodec(): BrowserWebRtcCodec = when (this) {
    WebRtcCodecPreferenceOption.AUTO -> BrowserWebRtcCodec.AUTO
    WebRtcCodecPreferenceOption.H264 -> BrowserWebRtcCodec.H264
    WebRtcCodecPreferenceOption.VP8 -> BrowserWebRtcCodec.VP8
    WebRtcCodecPreferenceOption.VP9 -> BrowserWebRtcCodec.VP9
    WebRtcCodecPreferenceOption.AV1 -> BrowserWebRtcCodec.AV1
}

private fun createBrowserFrameCapture(
    frameStore: BrowserFrameStore,
    profile: ProjectionVideoProfile,
    viewport: VideoViewport = VideoViewport(profile.width, profile.height),
): BrowserSurfaceFrameCapture {
    val config = BrowserJpegFallbackPolicy.forProjectionViewport(profile, viewport)
    return BrowserSurfaceFrameCapture(
        frameStore = frameStore,
        width = config.width,
        height = config.height,
        targetFramesPerSecond = config.targetFramesPerSecond,
        jpegQuality = config.jpegQuality,
    )
}
