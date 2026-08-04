/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.pebble.tecomheadunit.service

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
import com.pebble.tecomheadunit.MainActivity
import com.pebble.tecomheadunit.R
import com.pebble.tecomheadunit.automation.ProjectionStartCancellationSignal
import com.pebble.tecomheadunit.browser.BrowserFrameStore
import com.pebble.tecomheadunit.browser.BrowserProbeServer
import com.pebble.tecomheadunit.browser.BrowserSurfaceFrameCapture
import com.pebble.tecomheadunit.browser.BrowserWebRtcCodec
import com.pebble.tecomheadunit.browser.BrowserWebRtcIceServer
import com.pebble.tecomheadunit.browser.BrowserWebRtcSignaling
import com.pebble.tecomheadunit.browser.LocalAddressResolver
import com.pebble.tecomheadunit.browser.SwitchableBrowserWebRtcSignaling
import com.pebble.tecomheadunit.browser.audio.BrowserAudioPcmPipeline
import com.pebble.tecomheadunit.browser.audio.BrowserAudioTrack
import com.pebble.tecomheadunit.browser.audio.BrowserAudioTrackSnapshot
import com.pebble.tecomheadunit.browser.audio.BrowserMicrophonePcmSource
import com.pebble.tecomheadunit.browser.cloud.CloudBrowserRelayClient
import com.pebble.tecomheadunit.browser.cloud.CloudBrowserRelayConfig
import com.pebble.tecomheadunit.browser.cloud.CloudRelayIdentityStore
import com.pebble.tecomheadunit.browser.stun.LanStunServer
import com.pebble.tecomheadunit.browser.webrtc.WebRtcProjectionController
import com.pebble.tecomheadunit.browser.webrtc.WebRtcProjectionConfig
import com.pebble.tecomheadunit.audio.AudioStreamAccessTier
import com.pebble.tecomheadunit.core.OpenAutoCoordinator
import com.pebble.tecomheadunit.core.OpenAutoTouchEvent
import com.pebble.tecomheadunit.core.SafetyGate
import com.pebble.tecomheadunit.core.VehicleState
import com.pebble.tecomheadunit.diagnostics.AppDiagnostics
import com.pebble.tecomheadunit.diagnostics.DiagnosticEventCode
import com.pebble.tecomheadunit.diagnostics.DiagnosticLevel
import com.pebble.tecomheadunit.remote.SupabaseNoticeRepository
import com.pebble.tecomheadunit.openauto.AasdkDecoderUnavailableException
import com.pebble.tecomheadunit.openauto.AasdkPeerShutdownException
import com.pebble.tecomheadunit.openauto.AasdkProjectionRuntime
import com.pebble.tecomheadunit.openauto.BenchOpenAutoEngine
import com.pebble.tecomheadunit.openauto.MediaCodecVideoSink
import com.pebble.tecomheadunit.openauto.NativeOpenAutoBridge
import com.pebble.tecomheadunit.openauto.AndroidProjectionProfilePreferenceStore
import com.pebble.tecomheadunit.openauto.OpenAutoConfig
import com.pebble.tecomheadunit.openauto.OpenAutoInputSink
import com.pebble.tecomheadunit.openauto.OpenAutoPreflight
import com.pebble.tecomheadunit.openauto.ProjectionEntitlementProviderFactory
import com.pebble.tecomheadunit.openauto.ProjectionEndpointMode
import com.pebble.tecomheadunit.openauto.ProjectionAccessTier
import com.pebble.tecomheadunit.openauto.ProjectionProfileApplyScheduler
import com.pebble.tecomheadunit.openauto.ProjectionProfileManager
import com.pebble.tecomheadunit.openauto.ProjectionProfileRequestResult
import com.pebble.tecomheadunit.openauto.ProjectionProfileSnapshot
import com.pebble.tecomheadunit.openauto.ProjectionVideoProfile
import com.pebble.tecomheadunit.openauto.ProjectionViewportApplyScheduler
import com.pebble.tecomheadunit.openauto.ProjectionViewportLayout
import com.pebble.tecomheadunit.openauto.ProjectionViewportManager
import com.pebble.tecomheadunit.openauto.protocol.AasdkProtocolException
import com.pebble.tecomheadunit.openauto.protocol.AasdkTlsEngineException
import com.pebble.tecomheadunit.openauto.protocol.AasdkTlsPolicyException
import com.pebble.tecomheadunit.openauto.protocol.AasdkVersionMismatchException
import com.pebble.tecomheadunit.openauto.sensor.AndroidNightModeStateProvider
import com.pebble.tecomheadunit.openauto.sensor.AndroidNightModeLocationSource
import com.pebble.tecomheadunit.openauto.sensor.NightModeDecision
import com.pebble.tecomheadunit.openauto.sensor.ProjectionNightModeLocation
import com.pebble.tecomheadunit.session.BrowserTrustStore
import com.pebble.tecomheadunit.session.AndroidAutoConnectionState
import com.pebble.tecomheadunit.session.AndroidAutoConnectionStatus
import com.pebble.tecomheadunit.session.PairingToken
import com.pebble.tecomheadunit.session.SessionController
import com.pebble.tecomheadunit.session.SessionPhase
import com.pebble.tecomheadunit.ui.WebRtcCodecPreferenceOption
import com.pebble.tecomheadunit.ui.WebRtcCodecPreferenceStore
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

class ProjectionService : Service() {
    private var server: BrowserProbeServer? = null
    private var cloudRelayClient: CloudBrowserRelayClient? = null
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
    private val projectionSurfaceLock = Any()
    private val localBinder = LocalBinder()
    private var projectionSurface: Surface? = null
    private var projectionSurfaceGeneration = NO_SURFACE_GENERATION
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val projectionReconfigurationMutex = Mutex()
    private var startJob: Job? = null
    private var projectionJob: Job? = null
    private var profileApplyJob: Job? = null
    private var viewportApplyJob: Job? = null
    private var diagnosticHeartbeatJob: Job? = null
    private var noticeRefreshJob: Job? = null
    private var pendingProjectionProfile: ProjectionVideoProfile? = null
    private var pendingProjectionViewport: ProjectionViewportLayout? = null
    private val videoEventsSinceDiagnostic = AtomicLong(0L)
    private val touchEventsSinceDiagnostic = AtomicLong(0L)
    private val webRtcCodecPreferenceStore by lazy { WebRtcCodecPreferenceStore(applicationContext) }
    private val nightModeStateProvider by lazy { AndroidNightModeStateProvider(applicationContext) }
    private val nightModeLocationSource by lazy { AndroidNightModeLocationSource(applicationContext) }
    private val projectionStartupStore by lazy { ProjectionStartupStore(applicationContext) }
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

    inner class LocalBinder : Binder() {
        fun attachProjectionSurface(surface: Surface, generation: Long): Boolean =
            this@ProjectionService.attachProjectionSurface(surface, generation)

        fun detachProjectionSurface(generation: Long) {
            this@ProjectionService.detachProjectionSurface(generation)
        }

        fun projectionProfileSnapshot(): ProjectionProfileSnapshot =
            profileControl?.snapshot()
                ?: com.pebble.tecomheadunit.openauto.FreeProjectionProfileControl.snapshot()

        fun requestProjectionProfile(profileId: String?): ProjectionProfileRequestResult =
            profileControl?.requestProfile(profileId)
                ?: com.pebble.tecomheadunit.openauto.FreeProjectionProfileControl.requestProfile(profileId)

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

        /** Opens (or returns) one explicit ten-minute code without touching trusted credentials. */
        fun requestNewBrowserPairing(): Boolean =
            server?.let {
                it.requestNewBrowserPairing()
                true
            } == true
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
            ACTION_STOP -> stopSession("사용자가 서비스를 중지했습니다.")
            ACTION_START -> {
                val begin = {
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
        }
        return START_NOT_STICKY
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
            SessionController.error("주차 상태 확인이 없어 시작을 거부했습니다.", "SAFETY_GATE_DENIED")
            stopSelf()
            return
        }

        val epoch = ++sessionEpoch
        SessionController.starting("LOCAL_PREFLIGHT")
        startForeground(
            NOTIFICATION_ID,
            buildNotification("Android Auto 다시 연결 중 • 로컬 사전검사"),
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
                    SessionController.error("로컬 사전검사에 실패했습니다.", "PREFLIGHT_FAILED")
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
        )
        val newAudioPipeline = BrowserAudioPcmPipeline(
            accessTier = when (initialProfileSnapshot.entitlementTier) {
                ProjectionAccessTier.FREE -> AudioStreamAccessTier.FREE
                ProjectionAccessTier.PREMIUM -> AudioStreamAccessTier.PREMIUM
            },
        )
        val newMicrophoneSource = BrowserMicrophonePcmSource()
        val projectionConfig = activeProfile.toOpenAutoConfig()
        val engine = BenchOpenAutoEngine()
        val runtimeInput = object : OpenAutoInputSink {
            override fun sendTouch(event: OpenAutoTouchEvent): Boolean =
                projectionRuntime?.sendTouch(event) == true
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
            SessionController.error("Android Auto 서비스를 시작하지 못했습니다.", preflight.summary)
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }

        val pairingCode = PairingToken.create()
        val newFrameStore = BrowserFrameStore()
        // JPEG is a low-rate debug fallback copied from the fixed 800x480 app preview Surface.
        // Keeping its bitmap bounded avoids 8 MiB 1080p allocations and JPEG work competing
        // with the real high-resolution WebRTC texture path.
        val newFrameCapture = BrowserSurfaceFrameCapture(
            frameStore = newFrameStore,
        )
        lateinit var newServer: BrowserProbeServer
        val webRtcConfig = WebRtcProjectionConfig.forProjectionProfile(activeProfile)
        val newWebRtcController = WebRtcProjectionController(
            context = applicationContext,
            config = webRtcConfig,
            microphoneSource = newMicrophoneSource,
            audioStreams = newAudioPipeline.streamHub,
            controlRequestHandler = { request -> newServer.dispatchRelay(request) },
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
        val browserTrustStore = BrowserTrustStore(applicationContext)
        var newLanStunServer: LanStunServer? = null
        newServer = BrowserProbeServer(
            context = applicationContext,
            pairingCode = pairingCode,
            browserTrustStore = browserTrustStore,
            frameStore = newFrameStore,
            snapshot = { coordinator?.snapshot() ?: newCoordinator.snapshot() },
            androidAutoConnection = { SessionController.state.value.androidAutoConnection },
            touchInputReady = { projectionRuntime?.isTouchInputReady() == true },
            onTouch = { touch ->
                coordinator?.submitTouch(touch)?.onSuccess {
                    SessionController.touch(it)
                    touchEventsSinceDiagnostic.incrementAndGet()
                }?.isSuccess == true
            },
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
                        SessionController.updatePairingCode(publication?.code)
                        cloudRelayClient?.let { relay ->
                            if (publication == null) {
                                relay.stopPublishingPairingCode()
                            } else {
                                relay.publishPairingCode(publication)
                            }
                        }
                    }
                }
            },
        )

        var newCloudRelayClient: CloudBrowserRelayClient? = null
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
            newCloudRelayClient = cloudRelayConfig?.let { cloudConfig ->
                val identityStore = CloudRelayIdentityStore(applicationContext)
                val identity = identityStore.getOrCreate()
                lateinit var relay: CloudBrowserRelayClient
                relay = CloudBrowserRelayClient(
                    config = cloudConfig,
                    identity = identity,
                    initialPairingCode = newServer.publishedPairingCodeLease(),
                    nextPairingPublicationEpoch = identityStore::nextPairingPublicationEpoch,
                    gateway = newServer,
                    onConnectionChanged = { connected ->
                        serviceScope.launch {
                            if (epoch == sessionEpoch && cloudRelayClient === relay) {
                                val displayedUrl = if (connected) relay.browserUrl else localUrl
                                SessionController.updateBrowserUrl(displayedUrl)
                                updateNotification("브라우저 준비됨 • $displayedUrl")
                            }
                        }
                    },
                    onPairingRegistrationConflict = {
                        serviceScope.launch {
                            if (
                                epoch == sessionEpoch &&
                                server === newServer &&
                                cloudRelayClient === relay
                            ) {
                                newServer.rotatePairingCodeAfterBootstrapConflict()
                            }
                        }
                    },
                )
                relay
            }

            coordinator = newCoordinator
            server = newServer
            cloudRelayClient = newCloudRelayClient
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
            activeProjectionConfig = projectionConfig
            attachWebRtcPreviewToCurrentSurface()
            SessionController.ready(localUrl, newServer.publishedPairingCode(), preflight.summary)
            updateNotification("브라우저 준비됨 • $localUrl")
            newCloudRelayClient?.start()
            AppDiagnostics.record(
                DiagnosticEventCode.SESSION_READY,
                fields = mapOf(
                    "endpoint_mode" to preflight.endpointMode.name,
                    "profile" to activeProfile.name,
                    "webrtc_available" to (newWebRtcController != null),
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
            newCloudRelayClient?.close()
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
            SessionController.error("브라우저 서버를 시작하지 못했습니다.", preflight.summary)
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
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

    private suspend fun applyProjectionProfile(
        profile: ProjectionVideoProfile,
        epoch: Long,
    ): Boolean = projectionReconfigurationMutex.withLock {
        applyProjectionProfileLocked(profile, epoch)
    }

    private suspend fun applyProjectionProfileLocked(
        profile: ProjectionVideoProfile,
        epoch: Long,
    ): Boolean {
        val manager = profileControl ?: return false
        val preflight = sessionPreflight ?: return false
        val signaling = webRtcSignaling ?: return false
        if (epoch != sessionEpoch) return false
        if (manager.snapshot().activeProfile == profile) {
            manager.confirmActive(profile)
            return true
        }

        publishNativeStatus(
            nativeStatus = "${preflight.summary} • AASDK RECONNECTING reason=PROFILE_CHANGE " +
                "target=${profile.profileId}",
            message = "화면 프로필을 바로 적용하는 중입니다.",
        )

        var candidateForCancellation: WebRtcProjectionController? = null
        val replacementController = try {
            withContext(Dispatchers.IO) {
                val candidate = WebRtcProjectionController(
                    context = applicationContext,
                    config = WebRtcProjectionConfig.forProjectionProfile(profile),
                    microphoneSource = browserMicrophoneSource,
                    audioStreams = browserAudioPipeline?.streamHub,
                    controlRequestHandler = { request ->
                        checkNotNull(server) { "Browser relay gateway is unavailable" }
                            .dispatchRelay(request)
                    },
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
        }
        if (epoch != sessionEpoch) {
            replacementController?.close()
            return false
        }

        val replacementConfig = profile.toOpenAutoConfig()
        val replacementCoordinator = OpenAutoCoordinator(
            engine = BenchOpenAutoEngine(),
            safetyGate = SafetyGate(),
            config = replacementConfig,
            inputSink = object : OpenAutoInputSink {
                override fun sendTouch(event: OpenAutoTouchEvent): Boolean =
                    projectionRuntime?.sendTouch(event) == true
            },
        )
        if (replacementCoordinator.start(VehicleState.BENCH_STATIONARY).isFailure) {
            replacementController?.close()
            publishNativeStatus(
                nativeStatus = "${preflight.summary} • AASDK PROFILE_APPLY_FAILED reason=SAFETY_GATE",
                message = "안전 상태를 확인하지 못해 화면 프로필 변경을 취소했습니다.",
            )
            return false
        }

        // Commit order: stop AA input/decoder first, install the new geometry and optional WebRTC
        // source, then perform a fresh ServiceDiscovery exchange. The HTTP server and pairing
        // survive. If no non-H.264 encoder exists, AA continues on the app Surface/JPEG fallback.
        val previousRuntime = projectionRuntime
        projectionRuntime = null
        projectionDecoderController = null
        val previousProjectionJob = projectionJob
        projectionJob = null
        previousRuntime?.close()
        previousProjectionJob?.cancelAndJoin()

        val previousController = webRtcController
        previousController?.detachPreviewSurface()
        signaling.replace(replacementController ?: BrowserWebRtcSignaling.Unavailable)
        webRtcController = replacementController
        activeProjectionConfig = replacementConfig

        coordinator?.stop()
        coordinator = replacementCoordinator
        attachWebRtcPreviewToCurrentSurface()
        previousController?.close()

        manager.confirmActive(profile)
        viewportControl?.activateProfile(profile)
        launchProjection(
            preflight = preflight,
            benchConfirmed = sessionBenchConfirmed,
            projectionConfig = replacementConfig,
            epoch = epoch,
        )
        publishNativeStatus(
            nativeStatus = "${preflight.summary} • AASDK PROFILE_APPLIED ${profile.profileId} " +
                "RECONNECTING WEBRTC=${if (replacementController == null) "UNAVAILABLE" else "READY"}",
            message = if (replacementController == null) {
                "${profile.width}×${profile.height} 프로필을 적용했습니다. WebRTC는 지원 코덱이 없어 JPEG 대체 화면을 사용합니다."
            } else {
                "${profile.width}×${profile.height} 프로필을 적용해 Android Auto를 다시 연결하고 있습니다."
            },
        )
        return true
    }

    /**
     * Browser resize events are last-write-wins. A short settle window prevents browser chrome
     * animation from causing an Android Auto ServiceDiscovery reconnect for every intermediate
     * width while still applying a stable Tesla driving viewport automatically.
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
    ): Boolean = projectionReconfigurationMutex.withLock {
        val manager = viewportControl ?: return@withLock false
        val preflight = sessionPreflight ?: return@withLock false
        if (epoch != sessionEpoch) return@withLock false
        val viewportSnapshot = manager.snapshot()
        if (viewportSnapshot.requestedLayout != layout) return@withLock true
        if (viewportSnapshot.activeLayout == layout) {
            manager.confirmActive(layout)
            return@withLock true
        }

        val profileSnapshot = profileControl?.snapshot() ?: return@withLock false
        if (profileSnapshot.activationState !=
            com.pebble.tecomheadunit.openauto.ProjectionProfileActivationState.ACTIVE
        ) {
            return@withLock false
        }
        val activeProfile = profileSnapshot.activeProfile
        if (
            layout.encodedViewport.width != activeProfile.width ||
            layout.encodedViewport.height != activeProfile.height
        ) {
            return@withLock false
        }
        val replacementConfig = layout.applyTo(activeProfile.toOpenAutoConfig())
        val replacementCoordinator = OpenAutoCoordinator(
            engine = BenchOpenAutoEngine(),
            safetyGate = SafetyGate(),
            config = replacementConfig,
            inputSink = object : OpenAutoInputSink {
                override fun sendTouch(event: OpenAutoTouchEvent): Boolean =
                    projectionRuntime?.sendTouch(event) == true
            },
        )
        if (replacementCoordinator.start(VehicleState.BENCH_STATIONARY).isFailure) {
            publishNativeStatus(
                nativeStatus = "${preflight.summary} — AASDK VIEWPORT_APPLY_FAILED reason=SAFETY_GATE",
                message = "안전 상태를 확인하지 못해 동적 화면 비율 적용을 취소했습니다.",
            )
            return@withLock false
        }

        publishNativeStatus(
            nativeStatus = "${preflight.summary} — AASDK RECONNECTING reason=VIEWPORT_CHANGE " +
                "marginWidth=${layout.totalMarginWidth} marginHeight=${layout.totalMarginHeight} " +
                "densityDpi=${layout.densityDpi}",
            message = "웹 화면 크기에 Android Auto 화면을 맞추는 중입니다.",
        )

        val previousRuntime = projectionRuntime
        projectionRuntime = null
        projectionDecoderController = null
        val previousProjectionJob = projectionJob
        projectionJob = null
        previousRuntime?.close()
        previousProjectionJob?.cancelAndJoin()
        if (epoch != sessionEpoch || manager.snapshot().requestedLayout != layout) {
            replacementCoordinator.stop()
            return@withLock epoch == sessionEpoch
        }

        coordinator?.stop()
        coordinator = replacementCoordinator
        activeProjectionConfig = replacementConfig
        manager.confirmActive(layout)
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
                "WEBRTC_SESSION_PRESERVED",
        )
        publishNativeStatus(
            nativeStatus = "${preflight.summary} — AASDK VIEWPORT_APPLIED " +
                "marginWidth=${layout.totalMarginWidth} marginHeight=${layout.totalMarginHeight} " +
                "densityDpi=${layout.densityDpi} " +
                "RECONNECTING WEBRTC_SESSION=PRESERVED",
            message = "웹 화면 비율을 적용했습니다. Android Auto를 다시 연결하고 있습니다.",
        )
        true
    }

    private fun launchProjection(
        preflight: OpenAutoPreflight,
        benchConfirmed: Boolean,
        projectionConfig: OpenAutoConfig,
        epoch: Long,
    ) {
        val config = preflight.runtimeConfig(benchConfirmed, projectionConfig)
        if (config == null) {
            val reason = when {
                preflight.endpointHost.isBlank() -> "DISABLED"
                preflight.credential == null -> "CREDENTIAL_UNAVAILABLE"
                preflight.peerTrustPolicy ==
                    com.pebble.tecomheadunit.openauto.protocol.AasdkTlsPeerTrustPolicy.NotConfigured ->
                    "PEER_TRUST_NOT_CONFIGURED"
                else -> "PREFLIGHT_UNAVAILABLE"
            }
            publishNativeStatus("${preflight.summary} • AASDK $reason")
            return
        }

        val decoderController = webRtcController
        val videoSurfaceProvider: () -> Surface? = decoderController
            ?.let { controller -> { controller.decoderSurface() } }
            ?: ::currentProjectionSurface
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
        projectionDecoderController = decoderController
        projectionRuntime = runtime
        attachBrowserCaptureToCurrentSurface()
        projectionJob = serviceScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    runtime.runWithReconnect(config) { status ->
                        withContext(Dispatchers.Main.immediate) {
                            if (epoch == sessionEpoch && projectionRuntime === runtime) {
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
                    projectionRuntime = null
                    projectionDecoderController = null
                    projectionJob = null
                    webRtcController?.detachPreviewSurface()
                    detachBrowserCaptureFromCurrentSurface()
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
            webRtcController?.attachPreviewSurface(surface)?.onFailure { error ->
                Log.w(
                    WEBRTC_LOG_TAG,
                    "WEBRTC_PREVIEW_ATTACH_FAILED ${error.javaClass.simpleName}: ${error.message}",
                )
            }
            if (projectionRuntime != null) {
                browserFrameCapture?.attach(surface, generation)
            }
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

        webRtcController?.detachPreviewSurface()
        browserFrameCapture?.detach(generation)
        browserFrameStore?.clear()
        AppDiagnostics.record(
            DiagnosticEventCode.PROJECTION_SURFACE_CHANGED,
            fields = mapOf("state" to "detached"),
        )
        Log.i(LOG_TAG, "VIDEO_SURFACE_DETACHED generation=$generation")

        val controller = projectionDecoderController
        val detachAction = ProjectionSurfaceLossPolicy.decide(
            runtimeUsesNativeWebRtcDecoder = controller != null,
            nativeWebRtcDecoderSurfaceValid = controller?.decoderSurface()?.isValid == true,
        )
        if (detachAction == ProjectionSurfaceDetachAction.KEEP_RUNTIME) {
            // MediaCodec renders into the controller-owned SurfaceTexture. Only
            // the Activity preview and its PixelCopy fallback disappeared.
            Log.i(LOG_TAG, "VIDEO_SURFACE_BACKGROUND_CONTINUE decoder=WEBRTC generation=$generation")
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

    private fun currentProjectionSurface(): Surface? = synchronized(projectionSurfaceLock) {
        projectionSurface?.takeIf(Surface::isValid)
    }

    private fun attachBrowserCaptureToCurrentSurface() {
        val target = synchronized(projectionSurfaceLock) {
            projectionSurface
                ?.takeIf(Surface::isValid)
                ?.let { it to projectionSurfaceGeneration }
        } ?: return
        browserFrameCapture?.attach(target.first, target.second)
    }

    private fun attachWebRtcPreviewToCurrentSurface() {
        val target = synchronized(projectionSurfaceLock) {
            projectionSurface?.takeIf(Surface::isValid)
        } ?: return
        webRtcController?.attachPreviewSurface(target)?.onFailure { error ->
            Log.w(
                WEBRTC_LOG_TAG,
                "WEBRTC_PREVIEW_ATTACH_FAILED ${error.javaClass.simpleName}: ${error.message}",
            )
        }
    }

    private fun detachBrowserCaptureFromCurrentSurface() {
        val generation = synchronized(projectionSurfaceLock) {
            if (projectionSurface == null) null else projectionSurfaceGeneration
        }
        if (generation != null) browserFrameCapture?.detach(generation)
        browserFrameStore?.clear()
    }

    private fun stopProjectionForSurfaceLoss() {
        val runtime = projectionRuntime ?: return
        projectionRuntime = null
        projectionDecoderController = null
        val activeJob = projectionJob
        projectionJob = null

        // MediaCodecVideoSink.stop() completes before close() returns, so a
        // UI-owned or otherwise invalid decoder Surface is no longer used when
        // surfaceDestroyed completes.
        runtime.close()
        activeJob?.cancel()
        publishNativeStatus(
            nativeStatus = "AASDK SURFACE_LOST",
            message = "프로젝션 화면이 사라져 연결을 안전하게 종료했습니다.",
        )
    }

    private fun stopSession(message: String) {
        AppDiagnostics.record(
            DiagnosticEventCode.SESSION_STOPPED,
            fields = mapOf("reason" to "user_or_service_request"),
        )
        releaseResources()
        SessionController.idle(message)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
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

        coordinator?.stop()
        coordinator = null

        val runtime = projectionRuntime
        projectionRuntime = null
        projectionDecoderController = null
        runtime?.close()

        val activeProjectionJob = projectionJob
        projectionJob = null
        activeProjectionJob?.cancel()

        val activeStartJob = startJob
        startJob = null
        activeStartJob?.cancel()

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
        synchronized(projectionSurfaceLock) {
            projectionSurface = null
            projectionSurfaceGeneration = NO_SURFACE_GENERATION
        }
        if (phase == SessionPhase.STARTING || phase == SessionPhase.READY) {
            SessionController.idle("서비스가 종료되어 연결을 닫았습니다.")
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
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setContentIntent(openIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(0, "중지", stopIntent)
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
            AndroidAutoConnectionState.CONNECTED -> "Android Auto 연결됨"
            AndroidAutoConnectionState.RECONNECTING -> "Android Auto 다시 연결 중"
            AndroidAutoConnectionState.DISCONNECTED -> "Android Auto 연결 끊김"
        }
        val foregroundText = "$stateLabel • ${connection.reason.userMessage}"
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
            AndroidAutoConnectionState.RECONNECTING -> "Android Auto 다시 연결 중"
            AndroidAutoConnectionState.DISCONNECTED -> "Android Auto 연결 끊김"
            AndroidAutoConnectionState.CONNECTED -> "Android Auto 연결됨"
        }
        return NotificationCompat.Builder(this, CONNECTION_ALERT_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle(title)
            .setContentText(connection.reason.userMessage)
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
                "Android Auto 웹 서비스",
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
        manager.createNotificationChannel(
            NotificationChannel(
                CONNECTION_ALERT_CHANNEL_ID,
                "Android Auto 연결 알림",
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = "연결된 Android Auto 세션이 끊어졌을 때 한 번 알립니다."
            },
        )
    }

    companion object {
        private const val ACTION_START = "com.pebble.tecomheadunit.action.START"
        private const val ACTION_STOP = "com.pebble.tecomheadunit.action.STOP"
        private const val EXTRA_BENCH_CONFIRMED = "bench_confirmed"
        private const val EXTRA_START_CANCELLATION_GENERATION = "start_cancellation_generation"
        private const val CHANNEL_ID = "projection_session"
        private const val CONNECTION_ALERT_CHANNEL_ID = "android_auto_connection_alert"
        private const val NOTIFICATION_ID = 5277
        private const val CONNECTION_ALERT_NOTIFICATION_ID = 5278
        private const val LOG_TAG = "TecomAasdk"
        private const val WEBRTC_LOG_TAG = "TecomWebRtc"
        private const val NO_SURFACE_GENERATION = 0L
        private const val VIEWPORT_APPLY_SETTLE_MILLIS = 850L
        private const val DIAGNOSTIC_HEARTBEAT_MILLIS = 60_000L
        private const val NOTICE_REFRESH_INTERVAL_MILLIS = 15L * 60L * 1_000L

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

        fun stop(context: Context) {
            context.startService(stopIntent(context))
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
