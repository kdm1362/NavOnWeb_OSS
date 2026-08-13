/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.pebble.tecomheadunit.browser.webrtc

import android.content.Context
import android.util.Log
import android.view.Surface
import com.pebble.tecomheadunit.browser.BrowserWebRtcCapabilities
import com.pebble.tecomheadunit.browser.BrowserWebRtcCodec
import com.pebble.tecomheadunit.browser.BrowserWebRtcIceServer
import com.pebble.tecomheadunit.browser.BrowserWebRtcOffer
import com.pebble.tecomheadunit.browser.BrowserWebRtcOpenResult
import com.pebble.tecomheadunit.browser.BrowserWebRtcSessionSnapshot
import com.pebble.tecomheadunit.browser.BrowserWebRtcSessionState
import com.pebble.tecomheadunit.browser.BrowserWebRtcSessionTombstones
import com.pebble.tecomheadunit.browser.BrowserWebRtcSignaling
import com.pebble.tecomheadunit.browser.BrowserWebRtcSessionAdmission
import com.pebble.tecomheadunit.browser.BrowserRelayRequest
import com.pebble.tecomheadunit.browser.BrowserRelayResponse
import com.pebble.tecomheadunit.browser.BrowserSessionAccessMode
import com.pebble.tecomheadunit.browser.BrowserTouchPresenceEvent
import com.pebble.tecomheadunit.browser.browserWebRtcSessionAdmission
import com.pebble.tecomheadunit.browser.browserWebRtcEffectiveColorSlot
import com.pebble.tecomheadunit.browser.browserWebRtcSessionLimit
import com.pebble.tecomheadunit.browser.browserWebRtcSessionsToCloseForLimit
import com.pebble.tecomheadunit.browser.withDeviceDisplayName
import com.pebble.tecomheadunit.billing.PremiumAccessGate
import com.pebble.tecomheadunit.browser.audio.BrowserAudioStreamHub
import com.pebble.tecomheadunit.browser.audio.BrowserAudioTrack
import com.pebble.tecomheadunit.browser.audio.BrowserMicrophonePcmSource
import com.pebble.tecomheadunit.browser.webrtc.WebRtcCodecBridge.toBrowserCodec
import com.pebble.tecomheadunit.browser.webrtc.WebRtcCodecBridge.toPreference
import com.pebble.tecomheadunit.browser.webrtc.codec.AndroidMediaCodecCapabilityProbe
import com.pebble.tecomheadunit.browser.webrtc.codec.CodecSelectionWarning
import com.pebble.tecomheadunit.browser.webrtc.codec.EncoderAcceleration
import com.pebble.tecomheadunit.browser.webrtc.codec.MediaCodecCapabilitySnapshot
import com.pebble.tecomheadunit.browser.webrtc.codec.RemoteVideoCodecCapabilities
import com.pebble.tecomheadunit.browser.webrtc.codec.WebRtcCodecSelectionPolicy
import com.pebble.tecomheadunit.browser.webrtc.codec.WebRtcCodecSelectionResult
import com.pebble.tecomheadunit.browser.webrtc.codec.WebRtcOutboundCodecPolicy
import com.pebble.tecomheadunit.browser.webrtc.codec.WebRtcVideoCodec
import java.io.Closeable
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import org.webrtc.CandidatePairChangeEvent
import org.webrtc.DataChannel
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.MediaStreamTrack
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RTCStatsCollectorCallback
import org.webrtc.RtpParameters
import org.webrtc.RtpReceiver
import org.webrtc.RtpTransceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.VideoSource
import org.webrtc.VideoSink
import org.webrtc.VideoTrack

/**
 * Owns the native WebRTC sender while leaving HTTP signaling and ProjectionService wiring outside.
 *
 * Lifecycle:
 * 1. call [start];
 * 2. pass [decoderSurface] to MediaCodec and optionally [attachPreviewSurface] to retain the app UI;
 * 3. inject this object as [BrowserWebRtcSignaling];
 * 4. call [close] before releasing the service.
 */
class WebRtcProjectionController(
    context: Context,
    private val config: WebRtcProjectionConfig = WebRtcProjectionConfig(),
    microphoneSource: BrowserMicrophonePcmSource? = null,
    private val audioStreams: BrowserAudioStreamHub? = null,
    private val controlRequestHandler: ((String, BrowserRelayRequest) -> BrowserRelayResponse)? = null,
    sessionLimitProvider: (() -> Int)? = null,
    private val preferredMainDeviceId: () -> String? = { null },
    private val claimMainDeviceIfUnset: (String) -> Boolean = { false },
    private val sessionAdmissionLock: Any = Any(),
) : BrowserWebRtcSignaling, Closeable {
    private val appContext = context.applicationContext
    private val lifecycleLock = Any()
    private val worker: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "TecomWebRtcController").apply { isDaemon = true }
    }
    private val statsScheduler = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "TecomWebRtcStats").apply { isDaemon = true }
    }
    private val secureRandom = SecureRandom()
    private val codecProbe = AndroidMediaCodecCapabilityProbe()
    private val codecPolicy = WebRtcCodecSelectionPolicy()
    private val microphoneFrameDispatcher = microphoneSource?.let { source ->
        MicrophoneFrameDispatcher(source, sessionAdmissionLock)
    }
    private val sessionLimitProvider: () -> Int = sessionLimitProvider ?: {
        browserWebRtcSessionLimit(PremiumAccessGate.isPremium(appContext))
    }
    private val connectionSequence = AtomicLong(0L)

    private var resources: CoreResources? = null
    private val sessions = linkedMapOf<String, SessionRuntime>()
    private val terminalSessions = BrowserWebRtcSessionTombstones()
    private var closed = false

    /** Initializes libwebrtc, the shared EGL context, texture source and encoder/decoder factories. */
    fun start(): Result<Unit> = runCatching {
        synchronized(lifecycleLock) {
            check(!closed) { "WebRTC projection controller is closed" }
            if (resources != null) return@runCatching

            WebRtcLibraryInitializer.ensureInitialized(appContext)
            val localCapabilities = codecProbe.probe(
                width = config.width,
                height = config.height,
                framesPerSecond = config.framesPerSecond,
            )
            val createdEgl = EglBase.create()
            var createdFactory: PeerConnectionFactory? = null
            var createdSource: VideoSource? = null
            var createdTrack: VideoTrack? = null
            var createdBridge: WebRtcTextureVideoBridge? = null
            try {
                val defaultEncoderFactory = DefaultVideoEncoderFactory(
                    createdEgl.eglBaseContext,
                    true,
                    true,
                )
                val encoderFactory = CodecOrderedVideoEncoderFactory(
                    delegate = defaultEncoderFactory,
                    codecOrder = FACTORY_CODEC_ORDER,
                    enabledCodecs = WebRtcOutboundCodecPolicy.enabledCodecs,
                )
                createdFactory = PeerConnectionFactory.builder()
                    .setOptions(WebRtcLocalNetworkPolicy.peerConnectionFactoryOptions())
                    .setVideoEncoderFactory(encoderFactory)
                    .setVideoDecoderFactory(DefaultVideoDecoderFactory(createdEgl.eglBaseContext))
                    .createPeerConnectionFactory()
                createdSource = createdFactory.createVideoSource(true).apply {
                    setIsScreencast(true)
                    adaptOutputFormat(config.width, config.height, config.framesPerSecond)
                }
                createdTrack = createdFactory.createVideoTrack(VIDEO_TRACK_ID, createdSource).apply {
                    setEnabled(true)
                }
                createdBridge = WebRtcTextureVideoBridge(
                    sharedContext = createdEgl.eglBaseContext,
                    capturerObserver = createdSource.capturerObserver,
                    width = config.width,
                    height = config.height,
                    framesPerSecond = config.framesPerSecond,
                )

                val factoryCodecs = encoderFactory.supportedCodecs
                    .mapNotNull { WebRtcCodecBridge.fromSdpName(it.name) }
                    .toSet()
                val orderedAvailableCodecs = orderedAvailableCodecs(
                    local = localCapabilities,
                    factoryCodecs = factoryCodecs,
                )
                resources = CoreResources(
                    eglBase = createdEgl,
                    factory = createdFactory,
                    source = createdSource,
                    track = createdTrack,
                    bridge = createdBridge,
                    localCapabilities = localCapabilities,
                    availableCodecs = orderedAvailableCodecs,
                )
                Log.i(
                    LOG_TAG,
                    "WEBRTC_CORE_STARTED size=${config.width}x${config.height} " +
                        "fps=${config.framesPerSecond} codecs=" +
                        orderedAvailableCodecs.joinToString().ifEmpty { "JPEG_ONLY" },
                )
            } catch (error: Throwable) {
                createdBridge?.close()
                createdTrack?.dispose()
                createdSource?.dispose()
                createdFactory?.dispose()
                createdEgl.release()
                throw error
            }
        }
    }

    /** MediaCodec must render Android Auto frames into this Surface, not the UI Surface directly. */
    fun decoderSurface(): Surface? = synchronized(lifecycleLock) {
        resources?.bridge?.decoderSurface?.takeIf(Surface::isValid)
    }

    /** Fan-outs the same texture frame to the existing app preview Surface. */
    fun attachPreviewSurface(surface: Surface, generation: Long): Result<Unit> = runCatching {
        val current = synchronized(lifecycleLock) {
            check(!closed) { "WebRTC projection controller is closed" }
            checkNotNull(resources) { "WebRTC projection controller is not started" }
        }
        current.bridge.attachPreviewSurface(
            surface = surface,
            generation = generation,
            sharedContext = current.eglBase.eglBaseContext,
        )
    }

    fun detachPreviewSurface(generation: Long? = null) {
        synchronized(lifecycleLock) { resources?.bridge }?.detachPreviewSurface(generation)
    }

    /** Fan-outs decoder frames to the subscriber-gated service JPEG encoder. */
    fun setBrowserFrameSink(sink: VideoSink?) {
        synchronized(lifecycleLock) { resources?.bridge }?.setAuxiliaryFrameSink(sink)
    }

    override fun capabilities(): BrowserWebRtcCapabilities {
        reconcileSessionLimit(currentSessionLimit(), preferredMainDeviceId())
        val current = synchronized(lifecycleLock) { resources }
            ?: return BrowserWebRtcCapabilities(
                available = false,
                detail = if (closed) "Native WebRTC sender is closed" else "Native WebRTC sender is not started",
            )
        return BrowserWebRtcCapabilities(
            available = current.availableCodecs.isNotEmpty(),
            codecs = current.availableCodecs.map { it.toBrowserCodec() },
            iceServers = config.iceServers.map { server ->
                BrowserWebRtcIceServer(
                    urls = server.urls,
                    username = server.username,
                    credential = server.credential,
                )
            },
            outputAudioDataChannelsV1 = if (audioStreams == null) {
                emptyList()
            } else {
                BrowserAudioTrack.entries.map { it.wireName }
            },
            controlDataChannelV1 = controlRequestHandler != null,
            detail = capabilityDetail(current),
        )
    }

    override fun openSession(offer: BrowserWebRtcOffer): BrowserWebRtcOpenResult {
        reconcileSessionLimit(currentSessionLimit(), preferredMainDeviceId())
        val current = synchronized(lifecycleLock) {
            if (closed) return BrowserWebRtcOpenResult.Rejected("Native WebRTC sender is closed")
            val readyResources = resources
                ?: return BrowserWebRtcOpenResult.Rejected("Native WebRTC sender is not started")
            if (readyResources.availableCodecs.isEmpty()) {
                return BrowserWebRtcOpenResult.Rejected("Native WebRTC video encoder is unavailable")
            }
            val active = activeSessionsLocked()
            if (
                browserWebRtcSessionAdmission(
                    activeOwnersBySessionId = active.associate { it.snapshot.sessionId to it.ownerKey },
                    requestedOwnerKey = offer.ownerKey,
                    maximumSessions = currentSessionLimit(),
                ) == BrowserWebRtcSessionAdmission.Busy
            ) {
                return BrowserWebRtcOpenResult.Busy
            }
            readyResources
        }
        if (offer.sdp.length > MAX_SDP_CHARS || '\u0000' in offer.sdp) {
            return BrowserWebRtcOpenResult.Rejected("Invalid WebRTC offer")
        }

        Log.i(LOG_TAG, "WEBRTC_REMOTE_ICE ${candidateSummary(offer.sdp)}")
        Log.i(LOG_TAG, "WEBRTC_REMOTE_MEDIA ${videoMediaSummary(offer.sdp)}")

        val offeredCodecs = WebRtcCodecBridge.parseRemoteVideoCodecs(offer.sdp)
            .intersect(current.availableCodecs.toSet())
        val remote = RemoteVideoCodecCapabilities(offeredCodecs)
        val selection = codecPolicy.select(
            preference = offer.preferredCodec.toPreference(),
            local = current.localCapabilities,
            remote = remote,
            allowSoftwareForExplicitChoice = config.allowSoftwareForExplicitCodec,
        )
        val selected = when (selection) {
            is WebRtcCodecSelectionResult.Selected -> selection.value
            is WebRtcCodecSelectionResult.Unavailable -> {
                return BrowserWebRtcOpenResult.Rejected("No mutually supported WebRTC video codec")
            }
        }
        if (selected.codec !in current.availableCodecs) {
            return BrowserWebRtcOpenResult.Rejected("Selected WebRTC encoder is unavailable")
        }
        val preferredCodecs = codecPolicy.rankedCandidates(
            preference = offer.preferredCodec.toPreference(),
            local = current.localCapabilities,
            remote = remote,
            allowSoftwareForExplicitChoice = config.allowSoftwareForExplicitCodec,
        ).map { it.codec }.distinct()
        val initialSnapshot = BrowserWebRtcSessionSnapshot(
            sessionId = newSessionId(),
            state = BrowserWebRtcSessionState.PENDING,
            selectedCodec = selected.codec.toBrowserCodec(),
            detail = selectionDetail(selected.usedFallback, selected.warnings),
        )
        val connectedSequence = connectionSequence.incrementAndGet()
        val publicMetadata = com.pebble.tecomheadunit.browser.BrowserWebRtcSessionPublicMetadata(
            sessionId = initialSnapshot.sessionId,
            deviceId = offer.deviceId,
            accessMode = offer.accessMode,
            colorSlot = offer.colorSlot,
            displayName = offer.displayName,
            isMain = preferredMainDeviceId() == offer.deviceId,
            connectedSequence = connectedSequence,
        )
        val runtime = SessionRuntime(
            snapshot = initialSnapshot.copy(publicMetadata = publicMetadata),
            ownerKey = offer.ownerKey,
            selectedCodec = selected.codec,
            preferredCodecs = preferredCodecs,
            connectedSequence = connectedSequence,
        )
        var replacedRuntime: SessionRuntime? = null
        synchronized(lifecycleLock) {
            if (closed || resources !== current) {
                return BrowserWebRtcOpenResult.Rejected("Native WebRTC sender stopped")
            }
            val active = activeSessionsLocked()
            when (
                val admission = browserWebRtcSessionAdmission(
                    activeOwnersBySessionId = active.associate { it.snapshot.sessionId to it.ownerKey },
                    requestedOwnerKey = offer.ownerKey,
                    maximumSessions = currentSessionLimit(),
                )
            ) {
                BrowserWebRtcSessionAdmission.Open -> Unit
                BrowserWebRtcSessionAdmission.Busy -> return BrowserWebRtcOpenResult.Busy
                is BrowserWebRtcSessionAdmission.Replace -> {
                    val replaced = sessions.remove(admission.sessionId)
                        ?: return BrowserWebRtcOpenResult.Rejected("WebRTC session changed")
                    replaced.snapshot = replaced.snapshot.copy(
                        state = BrowserWebRtcSessionState.CLOSED,
                        answerSdp = null,
                        detail = "Replaced by the same browser credential",
                    )
                    replacedRuntime = replaced
                }
            }
            val usedColorSlots = activeSessionsLocked()
                .asSequence()
                .filter { it.ownerKey != offer.ownerKey }
                .mapNotNull { it.snapshot.publicMetadata?.colorSlot }
                .toSet()
            val effectiveColorSlot = checkNotNull(
                browserWebRtcEffectiveColorSlot(offer.colorSlot, usedColorSlots),
            ) { "No live session color slot remains" }
            runtime.snapshot = runtime.snapshot.copy(
                publicMetadata = runtime.snapshot.publicMetadata?.copy(colorSlot = effectiveColorSlot),
            )
            sessions[runtime.snapshot.sessionId] = runtime
        }

        if (
            offer.accessMode == BrowserSessionAccessMode.CONTROL &&
            preferredMainDeviceId() == null &&
            runCatching { claimMainDeviceIfUnset(offer.deviceId) }.getOrDefault(false)
        ) {
            synchronized(lifecycleLock) {
                if (isCurrentLocked(runtime)) {
                    runtime.snapshot = runtime.snapshot.copy(
                        publicMetadata = runtime.snapshot.publicMetadata?.copy(isMain = true),
                    )
                }
            }
        }

        val replaced = replacedRuntime
        if (!post {
                replaced?.let(::disposePeer)
                if (isActive(runtime)) createPeerConnection(runtime, current, offer.sdp)
            }
        ) {
            failSession(runtime, "WebRTC worker is closed")
            return BrowserWebRtcOpenResult.Rejected("Native WebRTC sender stopped")
        }
        replaced?.let {
            Log.i(
                LOG_TAG,
                "WEBRTC_SESSION_REPLACED old=${it.snapshot.sessionId} new=${runtime.snapshot.sessionId}",
            )
        }
        return BrowserWebRtcOpenResult.Accepted(runtime.snapshot)
    }

    override fun session(sessionId: String, ownerKey: String): BrowserWebRtcSessionSnapshot? =
        synchronized(lifecycleLock) {
            val active = sessions[sessionId]
                ?.takeIf { it.ownerKey == ownerKey }
                ?.let { runtime ->
                    runtime.snapshot.copy(
                        publicMetadata = runtime.snapshot.publicMetadata?.copy(
                            isMain = preferredMainDeviceId() == runtime.snapshot.publicMetadata?.deviceId,
                        ),
                    )
                }
            active ?: terminalSessions.get(sessionId, ownerKey)
        }

    override fun closeSession(sessionId: String, ownerKey: String): Boolean {
        val runtime = synchronized(lifecycleLock) {
            sessions[sessionId]?.takeIf { it.ownerKey == ownerKey }
        } ?: return false
        return closeRuntime(runtime, "Closed by browser")
    }

    override fun closeSessionsForOwner(ownerKey: String): Int {
        val owned = synchronized(lifecycleLock) {
            sessions.values.filter { it.ownerKey == ownerKey }
        }
        return owned.count { runtime ->
            closeRuntime(runtime, "Paired device revoked")
        }
    }

    override fun closeSessionsForDevice(deviceId: String): Int {
        val owned = synchronized(lifecycleLock) {
            sessions.values.filter { it.snapshot.publicMetadata?.deviceId == deviceId }
        }
        return owned.count { runtime -> closeRuntime(runtime, "Paired-device permission changed") }
    }

    override fun updateDeviceDisplayName(deviceId: String, displayName: String): Int =
        synchronized(lifecycleLock) {
            var updatedSessions = 0
            activeSessionsLocked().forEach { runtime ->
                val renamed = runtime.snapshot.withDeviceDisplayName(deviceId, displayName)
                if (renamed !== runtime.snapshot) {
                    runtime.snapshot = renamed
                    updatedSessions += 1
                }
            }
            updatedSessions
        }

    override fun activeSessionMetadata(ownerKey: String) = synchronized(lifecycleLock) {
        val metadata = activeSessionsLocked()
            .firstOrNull { it.ownerKey == ownerKey }
            ?.snapshot
            ?.publicMetadata
            ?: return@synchronized null
        metadata.copy(isMain = preferredMainDeviceId() == metadata.deviceId)
    }

    override fun oldestInteractiveDeviceId(): String? = synchronized(lifecycleLock) {
        activeSessionsLocked()
            .asSequence()
            .filter { it.accessMode == BrowserSessionAccessMode.CONTROL }
            .sortedBy { it.connectedSequence }
            .mapNotNull { it.snapshot.publicMetadata?.deviceId }
            .firstOrNull()
    }

    override fun connectedDeviceIds(): Set<String> = synchronized(lifecycleLock) {
        activeSessionsLocked().mapNotNullTo(linkedSetOf()) { it.snapshot.publicMetadata?.deviceId }
    }

    override fun connectedSessionMetadata() = synchronized(lifecycleLock) {
        activeSessionsLocked()
            .sortedBy { it.connectedSequence }
            .mapNotNull { runtime ->
                runtime.snapshot.publicMetadata?.let { metadata ->
                    metadata.copy(isMain = preferredMainDeviceId() == metadata.deviceId)
                }
            }
    }

    override fun publishTouchPresence(
        sourceOwnerKey: String,
        event: BrowserTouchPresenceEvent,
    ): Int {
        val encoded = event.toJsonString()
        val targets = synchronized(lifecycleLock) {
            activeSessionsLocked().filter { runtime ->
                runtime.ownerKey != sourceOwnerKey && runtime.controlDataChannel != null
            }
        }
        if (targets.isEmpty()) return 0
        if (!post {
                targets.forEach { runtime ->
                    val attachment = synchronized(lifecycleLock) { runtime.controlDataChannel }
                        ?: return@forEach
                    if (!sendControlText(runtime, attachment, encoded)) {
                        releaseControlDataChannel(runtime, expectedChannel = attachment.channel)
                    }
                }
            }
        ) return 0
        return targets.size
    }

    override fun reconcileSessionLimit(
        maximumSessions: Int,
        preferredMainDeviceId: String?,
    ): Int {
        val boundedLimit = maximumSessions.coerceIn(
            com.pebble.tecomheadunit.browser.MAX_FREE_BROWSER_SESSIONS,
            com.pebble.tecomheadunit.browser.MAX_PREMIUM_BROWSER_SESSIONS,
        )
        val toClose = synchronized(lifecycleLock) {
            val active = activeSessionsLocked()
            val metadata = active.mapNotNull { runtime ->
                runtime.snapshot.publicMetadata?.copy(connectedSequence = runtime.connectedSequence)
            }
            val ids = browserWebRtcSessionsToCloseForLimit(
                sessions = metadata,
                maximumSessions = boundedLimit,
                preferredMainDeviceId = preferredMainDeviceId,
            ).toSet()
            active.filter { it.snapshot.sessionId in ids }
        }
        return toClose.count { runtime ->
            closeRuntime(runtime, "Concurrent session entitlement changed")
        }
    }

    /**
     * Ends only the active browser sender so its existing recovery loop can negotiate again with
     * the codec preference stored by the phone app. The AA decoder and HTTP pairing stay alive.
     */
    fun closeActiveSessionForReconfiguration(): Boolean {
        val active = synchronized(lifecycleLock) { activeSessionsLocked() }
        val closedCount = active.count { runtime ->
            closeRuntime(runtime, "Projection media reconfiguring").also { closed ->
                if (closed) Log.i(
                    LOG_TAG,
                    "WEBRTC_SESSION_RECONFIGURE id=${runtime.snapshot.sessionId}",
                )
            }
        }
        return closedCount > 0
    }

    private fun closeRuntime(runtime: SessionRuntime, detail: String): Boolean {
        val accepted = synchronized(lifecycleLock) {
            if (sessions[runtime.snapshot.sessionId] !== runtime ||
                runtime.snapshot.state == BrowserWebRtcSessionState.CLOSED
            ) {
                false
            } else {
                sessions.remove(runtime.snapshot.sessionId)
                runtime.snapshot = runtime.snapshot.copy(
                    state = BrowserWebRtcSessionState.CLOSED,
                    answerSdp = null,
                    detail = detail,
                )
                true
            }
        }
        if (!accepted) return false
        if (!post { disposePeer(runtime) }) {
            // The worker can reject only while controller shutdown is already releasing the peer.
            return false
        }
        return true
    }

    fun collectStats(callback: RTCStatsCollectorCallback): Boolean {
        val runtime = synchronized(lifecycleLock) {
            sessions.values
                .asSequence()
                .filter { it.snapshot.state == BrowserWebRtcSessionState.READY }
                .sortedBy { it.connectedSequence }
                .firstOrNull()
        }
            ?: return false
        return post { runtime.peer?.getStats(callback) }
    }

    private fun startVideoStatsSampling(runtime: SessionRuntime) {
        synchronized(lifecycleLock) {
            if (!isCurrentLocked(runtime) || runtime.videoStatsTask != null) return
            val requestStats = Runnable {
                if (!isActive(runtime)) return@Runnable
                post {
                    if (!isActive(runtime)) return@post
                    runtime.peer?.getStats { report -> publishVideoStats(runtime, report) }
                }
            }
            runtime.videoStatsTask = statsScheduler.scheduleAtFixedRate(
                requestStats,
                0L,
                VIDEO_STATS_INTERVAL_SECONDS,
                TimeUnit.SECONDS,
            )
        }
    }

    private fun stopVideoStatsSampling(runtime: SessionRuntime) {
        val task = synchronized(lifecycleLock) {
            runtime.videoStatsTask.also {
                runtime.videoStatsTask = null
                runtime.lastVideoStats = null
            }
        }
        task?.cancel(false)
    }

    private fun publishVideoStats(runtime: SessionRuntime, report: org.webrtc.RTCStatsReport) {
        val current = WebRtcOutboundVideoStatsParser.parse(report) ?: return
        var previous: WebRtcOutboundVideoStats? = null
        val accepted = synchronized(lifecycleLock) {
            if (!isCurrentLocked(runtime) || runtime.snapshot.state !in ACTIVE_SESSION_STATES) {
                false
            } else {
                previous = runtime.lastVideoStats
                runtime.lastVideoStats = current
                true
            }
        }
        if (!accepted) return
        Log.i(
            LOG_TAG,
            "WEBRTC_VIDEO_STATS id=${runtime.snapshot.sessionId} ${current.logSummary(previous)}",
        )
    }

    override fun close() {
        val detached = synchronized(lifecycleLock) {
            if (closed) return
            closed = true
            val runtimes = sessions.values.toList()
            runtimes.forEach { runtime ->
                runtime.snapshot = runtime.snapshot.copy(
                    state = BrowserWebRtcSessionState.CLOSED,
                    answerSdp = null,
                    detail = "Native WebRTC sender closed",
                )
            }
            resources to runtimes
        }
        microphoneFrameDispatcher?.close()
        statsScheduler.shutdownNow()
        val releaseFuture = runCatching {
            worker.submit { detached.second.forEach(::disposePeer) }
        }.getOrNull()
        runCatching { releaseFuture?.get(RELEASE_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS) }

        detached.first?.let { current ->
            current.bridge.close()
            current.track.dispose()
            current.source.dispose()
            current.factory.dispose()
            current.eglBase.release()
        }
        synchronized(lifecycleLock) {
            resources = null
            sessions.clear()
            terminalSessions.clear()
        }
        worker.shutdownNow()
        runCatching { worker.awaitTermination(RELEASE_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS) }
        Log.i(LOG_TAG, "WEBRTC_CORE_CLOSED")
    }

    private fun createPeerConnection(
        runtime: SessionRuntime,
        current: CoreResources,
        offerSdp: String,
    ) {
        if (!isActive(runtime)) return
        try {
            val rtcConfiguration = PeerConnection.RTCConfiguration(config.iceServers.map(::toIceServer)).apply {
                sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
                bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
                continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_ONCE
                keyType = PeerConnection.KeyType.ECDSA
                tcpCandidatePolicy = PeerConnection.TcpCandidatePolicy.ENABLED
                enableDscp = true
            }
            val peer = checkNotNull(
                current.factory.createPeerConnection(rtcConfiguration, SessionPeerObserver(runtime)),
            ) { "Unable to create PeerConnection" }
            runtime.peer = peer
            peer.setRemoteDescription(
                sdpObserver(
                    onSetSuccess = { postFor(runtime) { attachProjectionTrack(runtime, current) } },
                    onFailure = { detail -> failSessionAsync(runtime, "Remote SDP rejected: $detail") },
                ),
                SessionDescription(SessionDescription.Type.OFFER, offerSdp),
            )
        } catch (error: Throwable) {
            failSession(runtime, "PeerConnection setup failed: ${safeErrorName(error)}")
        }
    }

    /**
     * setRemoteDescription creates the Unified Plan transceiver for the browser's
     * recvonly video m-section. Attach to that transceiver rather than creating an
     * unrelated local m-section before the offer is known.
     */
    private fun attachProjectionTrack(runtime: SessionRuntime, current: CoreResources) {
        val peer = runtime.peer ?: return failSession(runtime, "PeerConnection disappeared")
        try {
            val remoteDescription = checkNotNull(peer.remoteDescription) {
                "Remote SDP disappeared"
            }
            val receivingVideoMids = WebRtcRemoteOffer.receivingVideoMids(remoteDescription.description)
            val transceiver = receivingVideoMids.asSequence()
                .mapNotNull { offeredMid ->
                    peer.transceivers.firstOrNull { candidate ->
                        candidate.mid == offeredMid &&
                            candidate.mediaType == MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO &&
                            !candidate.isStopped &&
                            candidate.sender.track() == null
                    }
                }
                .firstOrNull()
            checkNotNull(transceiver) {
                "Remote offer has no attachable recvonly video transceiver"
            }

            val previousDirection = transceiver.direction
            check(transceiver.sender.setTrack(current.track, false)) {
                "Unable to attach projection video track"
            }
            transceiver.sender.setStreams(listOf(MEDIA_STREAM_ID))
            check(transceiver.setDirection(RtpTransceiver.RtpTransceiverDirection.SEND_ONLY)) {
                "Unable to set projection transceiver direction"
            }
            runtime.videoTransceiver = transceiver
            applyCodecPreferences(current.factory, transceiver, runtime.preferredCodecs)
            applyBitrateLimits(transceiver)
            check(peer.setBitrate(config.minBitrateBps, config.startBitrateBps, config.maxBitrateBps)) {
                "Unable to apply WebRTC bitrate range"
            }
            Log.i(
                LOG_TAG,
                "WEBRTC_SENDER_ATTACHED id=${runtime.snapshot.sessionId} mid=${transceiver.mid} " +
                    "direction=$previousDirection->${transceiver.direction} " +
                    "track=${current.track.id()} streams=${transceiver.sender.streams.joinToString()}",
            )
            createAnswer(runtime)
        } catch (error: Throwable) {
            failSession(runtime, "Projection track attachment failed: ${safeErrorName(error)}")
        }
    }

    private fun createAnswer(runtime: SessionRuntime) {
        val peer = runtime.peer ?: return failSession(runtime, "PeerConnection disappeared")
        peer.createAnswer(
            sdpObserver(
                onCreateSuccess = { answer -> postFor(runtime) { setLocalAnswer(runtime, answer) } },
                onFailure = { detail -> failSessionAsync(runtime, "Answer creation failed: $detail") },
            ),
            MediaConstraints(),
        )
    }

    private fun setLocalAnswer(runtime: SessionRuntime, answer: SessionDescription) {
        val peer = runtime.peer ?: return failSession(runtime, "PeerConnection disappeared")
        peer.setLocalDescription(
            sdpObserver(
                onSetSuccess = { postFor(runtime) { publishAnswerIfGathered(runtime) } },
                onFailure = { detail -> failSessionAsync(runtime, "Local SDP rejected: $detail") },
            ),
            answer,
        )
    }

    private fun publishAnswerIfGathered(runtime: SessionRuntime) {
        if (!isActive(runtime) || runtime.snapshot.state != BrowserWebRtcSessionState.PENDING) return
        val peer = runtime.peer ?: return
        if (peer.iceGatheringState() != PeerConnection.IceGatheringState.COMPLETE) return
        val answer = peer.localDescription ?: return
        val transceiver = runtime.videoTransceiver
        runtime.snapshot = runtime.snapshot.copy(
            state = BrowserWebRtcSessionState.READY,
            answerSdp = answer.description,
            detail = "ICE gathering complete",
        )
        Log.i(
            LOG_TAG,
            "WEBRTC_SESSION_READY id=${runtime.snapshot.sessionId} codec=${runtime.selectedCodec} " +
                "direction=${transceiver?.direction} currentDirection=${transceiver?.currentDirection} " +
                videoMediaSummary(answer.description),
        )
    }

    private fun applyCodecPreferences(
        factory: PeerConnectionFactory,
        transceiver: RtpTransceiver,
        preferredCodecs: List<WebRtcVideoCodec>,
    ) {
        val capabilities = factory
            .getRtpSenderCapabilities(MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO)
            .codecs
        val ordered = WebRtcCodecBridge.orderedCapabilities(capabilities, preferredCodecs)
        transceiver.setCodecPreferences(ordered).throwError()
    }

    private fun applyBitrateLimits(transceiver: RtpTransceiver) {
        val parameters = transceiver.sender.parameters
        parameters.degradationPreference = RtpParameters.DegradationPreference.MAINTAIN_RESOLUTION
        parameters.encodings.forEach { encoding ->
            encoding.minBitrateBps = config.minBitrateBps
            encoding.maxBitrateBps = config.maxBitrateBps
            encoding.maxFramerate = config.framesPerSecond
        }
        check(transceiver.sender.setParameters(parameters)) { "Unable to apply sender parameters" }
    }

    private fun orderedAvailableCodecs(
        local: MediaCodecCapabilitySnapshot,
        factoryCodecs: Set<WebRtcVideoCodec>,
    ): List<WebRtcVideoCodec> {
        val bestAcceleration = local.encoders
            .filter {
                it.isUsable &&
                    it.codec in factoryCodecs &&
                    WebRtcOutboundCodecPolicy.isEncoderEnabled(it.codec)
            }
            .groupBy { it.codec }
            .mapValues { (_, encoders) ->
                encoders.minOf { ENCODER_ACCELERATION_ORDER.indexOf(it.acceleration) }
            }
        return bestAcceleration.keys.sortedWith(
            compareBy<WebRtcVideoCodec> { bestAcceleration.getValue(it) }
                .thenBy { AUTO_EFFICIENCY_ORDER.indexOf(it) },
        )
    }

    private fun capabilityDetail(current: CoreResources): String {
        if (current.availableCodecs.isEmpty()) {
            return "GPU decoder bridge ${config.width}x${config.height}@" +
                "${config.framesPerSecond}; WebRTC encoder unavailable; JPEG fallback active"
        }
        val accelerationByCodec = current.localCapabilities.encoders
            .filter { it.isUsable && it.codec in current.availableCodecs }
            .groupBy { it.codec }
            .mapValues { (_, encoders) ->
                encoders.minBy { ENCODER_ACCELERATION_ORDER.indexOf(it.acceleration) }.acceleration
            }
        val codecs = current.availableCodecs.joinToString(separator = ",") { codec ->
            "${codec.name}:${accelerationByCodec[codec]?.name ?: EncoderAcceleration.UNKNOWN.name}"
        }
        return "GPU texture sender ${config.width}x${config.height}@${config.framesPerSecond}; codecs=$codecs"
    }

    private fun selectionDetail(
        usedFallback: Boolean,
        warnings: Set<CodecSelectionWarning>,
    ): String = buildList {
        add("Creating answer")
        if (usedFallback || CodecSelectionWarning.REQUESTED_CODEC_UNAVAILABLE in warnings) {
            add("requested codec unavailable; using fallback")
        }
        if (CodecSelectionWarning.SOFTWARE_ENCODER_SELECTED in warnings) {
            add("software encoder selected; monitor latency and thermal load")
        }
        if (CodecSelectionWarning.ENCODER_ACCELERATION_UNKNOWN in warnings) {
            add("encoder acceleration is unknown")
        }
    }.joinToString(separator = "; ")

    private fun failSessionAsync(runtime: SessionRuntime, detail: String) {
        postFor(runtime) { failSession(runtime, detail) }
    }

    private fun failSession(runtime: SessionRuntime, detail: String) {
        if (!markSessionTerminal(runtime, BrowserWebRtcSessionState.FAILED, detail)) return
        disposePeer(runtime)
        Log.w(LOG_TAG, "WEBRTC_SESSION_FAILED id=${runtime.snapshot.sessionId} ${runtime.snapshot.detail}")
    }

    private fun markSessionTerminal(
        runtime: SessionRuntime,
        state: BrowserWebRtcSessionState,
        detail: String,
    ): Boolean = synchronized(lifecycleLock) {
        if (!isCurrentLocked(runtime)) return@synchronized false
        runtime.snapshot = runtime.snapshot.copy(
            state = state,
            answerSdp = null,
            detail = sanitizeDetail(detail),
        )
        sessions.remove(runtime.snapshot.sessionId)
        terminalSessions.put(runtime.ownerKey, runtime.snapshot)
        true
    }

    private fun disposePeer(runtime: SessionRuntime) {
        stopVideoStatsSampling(runtime)
        releaseAudioDataChannels(runtime)
        releaseMicrophoneDataChannel(runtime)
        releaseControlDataChannel(runtime)
        val peer = runtime.peer
        runtime.peer = null
        if (peer != null) {
            runCatching { peer.close() }
            runCatching { peer.dispose() }
        }
    }

    private fun acceptControlDataChannel(runtime: SessionRuntime, channel: DataChannel) {
        if (runCatching(channel::label).getOrNull() != WebRtcControlDataChannelCodec.DATA_CHANNEL_LABEL) {
            return
        }
        val handler = controlRequestHandler
        if (handler == null) {
            releaseDataChannel(channel, observer = null)
            return
        }

        val codec = WebRtcControlDataChannelCodec()
        val observer = ControlDataChannelObserver(runtime, channel, codec, handler)
        val attachment = ControlDataChannelAttachment(channel, observer, codec)
        val accepted = synchronized(lifecycleLock) {
            if (
                !isCurrentLocked(runtime) ||
                runtime.snapshot.state !in ACTIVE_SESSION_STATES ||
                runtime.controlDataChannel != null
            ) {
                false
            } else {
                runtime.controlDataChannel = attachment
                true
            }
        }
        if (!accepted) {
            releaseDataChannel(channel, observer = null)
            return
        }

        runCatching { channel.registerObserver(observer) }
            .onSuccess {
                Log.i(
                    LOG_TAG,
                    "WEBRTC_CONTROL_DATA_CHANNEL id=${runtime.snapshot.sessionId} state=ATTACHED",
                )
            }
            .onFailure {
                releaseControlDataChannel(runtime, expectedChannel = channel)
            }
    }

    private fun dispatchControlRequest(
        runtime: SessionRuntime,
        attachment: ControlDataChannelAttachment,
        handler: (String, BrowserRelayRequest) -> BrowserRelayResponse,
        accepted: WebRtcControlDecodeResult.Accepted,
    ) {
        if (!isCurrentControlDataChannel(runtime, attachment.channel, attachment.observer)) {
            attachment.codec.abandonRequest(accepted.requestId)
            return
        }
        val routeAllowed = isAllowedControlRoute(accepted.request)
        val accessDenied = runtime.accessMode == BrowserSessionAccessMode.READ_ONLY &&
            accepted.request.method == "POST"
        val response = if (routeAllowed && !accessDenied) {
            runCatching { handler(runtime.ownerKey, accepted.request) }.getOrElse { error ->
                Log.w(LOG_TAG, "WEBRTC_CONTROL_REQUEST_FAILED ${safeErrorName(error)}")
                controlErrorResponse(status = 500, error = "relay_request_failed")
            }
        } else if (accessDenied) {
            controlErrorResponse(status = 403, error = "read_only_session")
        } else {
            controlErrorResponse(status = 403, error = "control_route_forbidden")
        }
        val encoded = runCatching {
            attachment.codec.encodeResponse(accepted.requestId, response)
        }.getOrNull() ?: return
        if (!sendControlText(runtime, attachment, encoded)) {
            releaseControlDataChannel(runtime, expectedChannel = attachment.channel)
        }
    }

    private fun sendControlText(
        runtime: SessionRuntime,
        attachment: ControlDataChannelAttachment,
        text: String,
    ): Boolean {
        if (!isCurrentControlDataChannel(runtime, attachment.channel, attachment.observer)) return false
        val bytes = text.toByteArray(StandardCharsets.UTF_8)
        val bufferedAmount = runCatching(attachment.channel::bufferedAmount).getOrNull() ?: return false
        if (!WebRtcControlDataChannelSendPolicy.canSend(bufferedAmount, bytes.size)) {
            Log.w(
                LOG_TAG,
                "WEBRTC_CONTROL_EGRESS_OVERFLOW id=${runtime.snapshot.sessionId} " +
                    "buffered=$bufferedAmount message=${bytes.size}",
            )
            return false
        }
        return runCatching {
            attachment.channel.send(DataChannel.Buffer(ByteBuffer.wrap(bytes), false))
        }.getOrDefault(false)
    }

    private fun releaseControlDataChannel(
        runtime: SessionRuntime,
        expectedChannel: DataChannel? = null,
    ) {
        val detached = synchronized(lifecycleLock) {
            val current = runtime.controlDataChannel ?: return@synchronized null
            if (expectedChannel != null && current.channel !== expectedChannel) return@synchronized null
            runtime.controlDataChannel = null
            current
        } ?: return
        detached.codec.clearInFlightRequests()
        releaseDataChannel(detached.channel, detached.observer)
        Log.i(
            LOG_TAG,
            "WEBRTC_CONTROL_DATA_CHANNEL id=${runtime.snapshot.sessionId} state=DETACHED",
        )
    }

    private fun isCurrentControlDataChannel(
        runtime: SessionRuntime,
        channel: DataChannel,
        observer: DataChannel.Observer,
    ): Boolean = synchronized(lifecycleLock) {
        isCurrentLocked(runtime) &&
            runtime.snapshot.state in ACTIVE_SESSION_STATES &&
            runtime.controlDataChannel?.channel === channel &&
            runtime.controlDataChannel?.observer === observer
    }

    private fun isAllowedControlRoute(request: BrowserRelayRequest): Boolean {
        val path = request.target.substringBefore('?')
        return when (request.method) {
            "GET" -> path in CONTROL_GET_TARGETS
            "POST" -> path in CONTROL_POST_TARGETS
            else -> false
        }
    }

    private fun controlErrorResponse(status: Int, error: String): BrowserRelayResponse =
        BrowserRelayResponse(
            status = status,
            contentType = CONTROL_JSON_CONTENT_TYPE,
            body = "{\"error\":\"$error\"}".toByteArray(StandardCharsets.UTF_8),
        )

    private fun acceptMicrophoneDataChannel(runtime: SessionRuntime, channel: DataChannel) {
        if (runCatching(channel::label).getOrNull() != WebRtcMicrophoneFrameCodec.DATA_CHANNEL_LABEL) {
            return
        }
        if (runtime.accessMode == BrowserSessionAccessMode.READ_ONLY) {
            releaseDataChannel(channel, observer = null)
            return
        }
        val dispatcher = microphoneFrameDispatcher
        if (dispatcher == null) {
            releaseDataChannel(channel, observer = null)
            return
        }

        val observer = MicrophoneDataChannelObserver(runtime, channel, dispatcher)
        val accepted = synchronized(lifecycleLock) {
            if (
                !isCurrentLocked(runtime) ||
                runtime.snapshot.state !in ACTIVE_SESSION_STATES ||
                runtime.microphoneDataChannel != null
            ) {
                false
            } else {
                runtime.microphoneDataChannel = channel
                runtime.microphoneDataChannelObserver = observer
                true
            }
        }
        if (!accepted) {
            releaseDataChannel(channel, observer = null)
            return
        }

        runCatching { channel.registerObserver(observer) }
            .onSuccess {
                Log.i(LOG_TAG, "WEBRTC_MICROPHONE_DATA_CHANNEL id=${runtime.snapshot.sessionId} state=ATTACHED")
            }
            .onFailure {
                releaseMicrophoneDataChannel(runtime, expectedChannel = channel)
            }
    }

    private fun acceptAudioDataChannel(
        runtime: SessionRuntime,
        track: BrowserAudioTrack,
        channel: DataChannel,
    ) {
        val streams = audioStreams
        if (streams == null) {
            releaseDataChannel(channel, observer = null)
            return
        }

        val sender = WebRtcAudioDataChannelSender(
            track = track,
            streamHub = streams,
            channel = channel,
            isCurrent = { isCurrentAudioDataChannel(runtime, track, channel) },
        )
        val observer = AudioDataChannelObserver(runtime, track, channel, sender)
        val attachment = AudioDataChannelAttachment(channel, observer, sender)
        val accepted = synchronized(lifecycleLock) {
            if (
                !isCurrentLocked(runtime) ||
                runtime.snapshot.state !in ACTIVE_SESSION_STATES ||
                runtime.audioDataChannels.containsKey(track)
            ) {
                false
            } else {
                runtime.audioDataChannels[track] = attachment
                true
            }
        }
        if (!accepted) {
            sender.close()
            releaseDataChannel(channel, observer = null)
            return
        }

        runCatching { channel.registerObserver(observer) }
            .onSuccess {
                Log.i(
                    LOG_TAG,
                    "WEBRTC_AUDIO_DATA_CHANNEL id=${runtime.snapshot.sessionId} " +
                        "track=${track.wireName} state=ATTACHED",
                )
                when (runCatching(channel::state).getOrNull()) {
                    DataChannel.State.OPEN -> sender.start()
                    DataChannel.State.CLOSING,
                    DataChannel.State.CLOSED,
                    -> releaseAudioDataChannel(runtime, track, expectedChannel = channel)
                    else -> Unit
                }
            }
            .onFailure {
                releaseAudioDataChannel(runtime, track, expectedChannel = channel)
            }
    }

    private fun releaseAudioDataChannel(
        runtime: SessionRuntime,
        track: BrowserAudioTrack,
        expectedChannel: DataChannel? = null,
    ) {
        val detached = synchronized(lifecycleLock) {
            val current = runtime.audioDataChannels[track] ?: return@synchronized null
            if (expectedChannel != null && current.channel !== expectedChannel) return@synchronized null
            runtime.audioDataChannels.remove(track)
            current
        } ?: return
        detached.sender.close()
        releaseDataChannel(detached.channel, detached.observer)
        Log.i(
            LOG_TAG,
            "WEBRTC_AUDIO_DATA_CHANNEL id=${runtime.snapshot.sessionId} " +
                "track=${track.wireName} state=DETACHED",
        )
    }

    private fun releaseAudioDataChannels(runtime: SessionRuntime) {
        val detached = synchronized(lifecycleLock) {
            runtime.audioDataChannels.values.toList().also { runtime.audioDataChannels.clear() }
        }
        detached.forEach { attachment ->
            attachment.sender.close()
            releaseDataChannel(attachment.channel, attachment.observer)
        }
    }

    private fun releaseMicrophoneDataChannel(
        runtime: SessionRuntime,
        expectedChannel: DataChannel? = null,
    ) {
        val detached = synchronized(lifecycleLock) {
            val current = runtime.microphoneDataChannel ?: return@synchronized null
            if (expectedChannel != null && current !== expectedChannel) return@synchronized null
            val observer = runtime.microphoneDataChannelObserver
            runtime.microphoneDataChannel = null
            runtime.microphoneDataChannelObserver = null
            current to observer
        } ?: return
        releaseDataChannel(detached.first, detached.second)
    }

    private fun releaseDataChannel(channel: DataChannel, observer: DataChannel.Observer?) {
        if (observer != null) runCatching(channel::unregisterObserver)
        runCatching(channel::close)
        runCatching(channel::dispose)
    }

    private fun isCurrentMicrophoneDataChannel(
        runtime: SessionRuntime,
        channel: DataChannel,
        observer: DataChannel.Observer,
    ): Boolean = synchronized(lifecycleLock) {
        isCurrentLocked(runtime) &&
            runtime.microphoneDataChannel === channel &&
            runtime.microphoneDataChannelObserver === observer
    }

    private fun isCurrentAudioDataChannel(
        runtime: SessionRuntime,
        track: BrowserAudioTrack,
        channel: DataChannel,
    ): Boolean = synchronized(lifecycleLock) {
        isCurrentLocked(runtime) &&
            runtime.snapshot.state in ACTIVE_SESSION_STATES &&
            runtime.audioDataChannels[track]?.channel === channel
    }

    private fun isActive(runtime: SessionRuntime): Boolean = synchronized(lifecycleLock) {
        isCurrentLocked(runtime) && runtime.snapshot.state in ACTIVE_SESSION_STATES
    }

    private fun isCurrent(runtime: SessionRuntime): Boolean = synchronized(lifecycleLock) {
        isCurrentLocked(runtime)
    }

    private fun activeSessionsLocked(): List<SessionRuntime> = sessions.values.filter {
        it.snapshot.state in ACTIVE_SESSION_STATES
    }

    private fun isCurrentLocked(runtime: SessionRuntime): Boolean =
        !closed && sessions[runtime.snapshot.sessionId] === runtime

    private fun currentSessionLimit(): Int = runCatching(sessionLimitProvider)
        .getOrDefault(com.pebble.tecomheadunit.browser.MAX_FREE_BROWSER_SESSIONS)
        .coerceIn(
            com.pebble.tecomheadunit.browser.MAX_FREE_BROWSER_SESSIONS,
            com.pebble.tecomheadunit.browser.MAX_PREMIUM_BROWSER_SESSIONS,
        )

    private fun postFor(runtime: SessionRuntime, action: () -> Unit) {
        if (!post { if (isActive(runtime)) action() }) {
            failSession(runtime, "WebRTC worker is closed")
        }
    }

    private fun post(action: () -> Unit): Boolean = try {
        worker.execute(action)
        true
    } catch (_: RejectedExecutionException) {
        false
    }

    private fun newSessionId(): String = ByteArray(SESSION_ID_BYTES).also(secureRandom::nextBytes).let {
        Base64.getUrlEncoder().withoutPadding().encodeToString(it)
    }

    private fun toIceServer(server: WebRtcIceServerConfig): PeerConnection.IceServer =
        PeerConnection.IceServer.builder(server.urls)
            .setUsername(server.username.orEmpty())
            .setPassword(server.credential.orEmpty())
            .createIceServer()

    private fun safeErrorName(error: Throwable): String =
        error.javaClass.simpleName.takeIf(String::isNotBlank) ?: "WebRtcError"

    private fun sanitizeDetail(value: String): String = value
        .replace(Regex("[\\r\\n\\u0000-\\u001f]+"), " ")
        .take(MAX_DETAIL_CHARS)

    /**
     * Compact ICE-only SDP summary for device diagnostics. It deliberately omits
     * ICE passwords, fingerprints and the rest of the session description.
     */
    private fun candidateSummary(sdp: String): String {
        val candidates = sdp.lineSequence()
            .map(String::trim)
            .filter { it.startsWith("a=candidate:") || it.startsWith("candidate:") }
            .map { line ->
                val fields = line.removePrefix("a=").split(' ')
                val typeIndex = fields.indexOf("typ")
                val protocol = fields.getOrNull(2)?.lowercase() ?: "?"
                val address = fields.getOrNull(4) ?: "?"
                val port = fields.getOrNull(5) ?: "?"
                val type = fields.getOrNull(typeIndex + 1).takeUnless { typeIndex < 0 } ?: "?"
                "$type/$protocol@$address:$port"
            }
            .take(MAX_LOGGED_ICE_CANDIDATES)
            .toList()
        return "count=${candidates.size} candidates=${candidates.joinToString()}"
    }

    private fun videoMediaSummary(sdp: String): String {
        val lines = sdp.lineSequence().map(String::trim).toList()
        val videoStart = lines.indexOfFirst { it.startsWith("m=video ") }
        if (videoStart < 0) return "video=missing"
        val nextMediaOffset = lines.drop(videoStart + 1).indexOfFirst { it.startsWith("m=") }
        val videoEnd = if (nextMediaOffset >= 0) videoStart + 1 + nextMediaOffset else lines.size
        val section = lines.subList(videoStart, videoEnd)
        val direction = section.firstOrNull { it in SDP_DIRECTION_LINES } ?: "direction=missing"
        val mid = section.firstOrNull { it.startsWith("a=mid:") } ?: "mid=missing"
        val msid = section.firstOrNull { it.startsWith("a=msid:") } ?: "msid=missing"
        return "video=${section.first()} $direction $mid $msid"
    }

    private fun sdpObserver(
        onCreateSuccess: (SessionDescription) -> Unit = {},
        onSetSuccess: () -> Unit = {},
        onFailure: (String) -> Unit,
    ): SdpObserver = object : SdpObserver {
        override fun onCreateSuccess(description: SessionDescription) = onCreateSuccess(description)
        override fun onSetSuccess() = onSetSuccess()
        override fun onCreateFailure(detail: String) = onFailure(detail)
        override fun onSetFailure(detail: String) = onFailure(detail)
    }

    private inner class SessionPeerObserver(
        private val runtime: SessionRuntime,
    ) : PeerConnection.Observer {
        override fun onSignalingChange(state: PeerConnection.SignalingState) {
            Log.i(LOG_TAG, "WEBRTC_SIGNALING_STATE id=${runtime.snapshot.sessionId} state=$state")
        }

        override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {
            Log.i(LOG_TAG, "WEBRTC_ICE_STATE id=${runtime.snapshot.sessionId} state=$state")
        }

        override fun onIceConnectionReceivingChange(receiving: Boolean) {
            Log.i(LOG_TAG, "WEBRTC_ICE_RECEIVING id=${runtime.snapshot.sessionId} receiving=$receiving")
        }

        override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) {
            if (state == PeerConnection.IceGatheringState.COMPLETE) {
                postFor(runtime) { publishAnswerIfGathered(runtime) }
            }
        }

        override fun onIceCandidate(candidate: IceCandidate) {
            Log.i(
                LOG_TAG,
                "WEBRTC_LOCAL_ICE id=${runtime.snapshot.sessionId} ${candidateSummary(candidate.sdp)}",
            )
        }
        override fun onIceCandidatesRemoved(candidates: Array<IceCandidate>) = Unit
        override fun onAddStream(stream: MediaStream) = Unit
        override fun onRemoveStream(stream: MediaStream) = Unit
        override fun onDataChannel(channel: DataChannel) {
            val label = runCatching(channel::label).getOrNull()
            val audioTrack = WebRtcAudioDataChannels.track(label)
            when {
                label == WebRtcControlDataChannelCodec.DATA_CHANNEL_LABEL ->
                    acceptControlDataChannel(runtime, channel)
                label == WebRtcMicrophoneFrameCodec.DATA_CHANNEL_LABEL ->
                    acceptMicrophoneDataChannel(runtime, channel)
                audioTrack != null -> acceptAudioDataChannel(runtime, audioTrack, channel)
                else -> releaseDataChannel(channel, observer = null)
            }
        }
        override fun onRenegotiationNeeded() = Unit
        override fun onAddTrack(receiver: RtpReceiver, mediaStreams: Array<MediaStream>) = Unit
        override fun onSelectedCandidatePairChanged(event: CandidatePairChangeEvent) {
            val localAdapter = event.local.adapterType
            Log.i(
                LOG_TAG,
                "WEBRTC_SELECTED_PAIR id=${runtime.snapshot.sessionId} " +
                    "localAdapter=$localAdapter " +
                    "local=${candidateSummary(event.local.sdp)} " +
                    "remote=${candidateSummary(event.remote.sdp)} " +
                    "reason=${sanitizeDetail(event.reason)}",
            )
            if (WebRtcLocalNetworkPolicy.isForbiddenSelectedAdapter(localAdapter)) {
                Log.e(
                    LOG_TAG,
                    "WEBRTC_SELECTED_PAIR_REJECTED id=${runtime.snapshot.sessionId} " +
                        "localAdapter=$localAdapter",
                )
                failSessionAsync(runtime, "Non-local ICE adapter rejected: $localAdapter")
            }
        }

        override fun onConnectionChange(newState: PeerConnection.PeerConnectionState) {
            Log.i(LOG_TAG, "WEBRTC_PEER_STATE id=${runtime.snapshot.sessionId} state=$newState")
            when (newState) {
                PeerConnection.PeerConnectionState.CONNECTED -> startVideoStatsSampling(runtime)
                PeerConnection.PeerConnectionState.FAILED ->
                    failSessionAsync(runtime, "PeerConnection failed")
                PeerConnection.PeerConnectionState.CLOSED -> {
                    stopVideoStatsSampling(runtime)
                    postFor(runtime) {
                        if (
                            markSessionTerminal(
                                runtime,
                                BrowserWebRtcSessionState.CLOSED,
                                "PeerConnection closed",
                            )
                        ) {
                            disposePeer(runtime)
                        }
                    }
                }
                else -> Unit
            }
        }
    }

    private inner class ControlDataChannelObserver(
        private val runtime: SessionRuntime,
        private val channel: DataChannel,
        private val codec: WebRtcControlDataChannelCodec,
        private val handler: (String, BrowserRelayRequest) -> BrowserRelayResponse,
    ) : DataChannel.Observer {
        override fun onBufferedAmountChange(previousAmount: Long) = Unit

        override fun onStateChange() {
            when (runCatching(channel::state).getOrNull()) {
                DataChannel.State.CLOSING,
                DataChannel.State.CLOSED,
                -> post { releaseControlDataChannel(runtime, expectedChannel = channel) }
                else -> Unit
            }
        }

        override fun onMessage(buffer: DataChannel.Buffer) {
            if (!isCurrentControlDataChannel(runtime, channel, this)) return
            val result = codec.decodeRequest(buffer.data, buffer.binary)
            when (result) {
                is WebRtcControlDecodeResult.Accepted -> {
                    val attachment = ControlDataChannelAttachment(channel, this, codec)
                    if (!post { dispatchControlRequest(runtime, attachment, handler, result) }) {
                        codec.abandonRequest(result.requestId)
                        releaseControlDataChannel(runtime, expectedChannel = channel)
                    }
                }
                is WebRtcControlDecodeResult.Rejected -> {
                    if (result.shouldCloseChannel) {
                        post { releaseControlDataChannel(runtime, expectedChannel = channel) }
                    } else {
                        val responseText = result.responseText ?: return
                        val attachment = ControlDataChannelAttachment(channel, this, codec)
                        if (!post {
                                if (!sendControlText(runtime, attachment, responseText)) {
                                    releaseControlDataChannel(runtime, expectedChannel = channel)
                                }
                            }
                        ) {
                            releaseControlDataChannel(runtime, expectedChannel = channel)
                        }
                    }
                }
            }
        }
    }

    private inner class MicrophoneDataChannelObserver(
        private val runtime: SessionRuntime,
        private val channel: DataChannel,
        private val dispatcher: MicrophoneFrameDispatcher,
    ) : DataChannel.Observer {
        override fun onBufferedAmountChange(previousAmount: Long) = Unit

        override fun onStateChange() {
            val state = runCatching(channel::state).getOrNull()
            if (state == DataChannel.State.CLOSED) {
                post { releaseMicrophoneDataChannel(runtime, expectedChannel = channel) }
            }
        }

        override fun onMessage(buffer: DataChannel.Buffer) {
            if (!buffer.binary || !isCurrentMicrophoneDataChannel(runtime, channel, this)) return
            val data = buffer.data
            val byteCount = runCatching(data::remaining).getOrNull() ?: return
            if (byteCount !in (WebRtcMicrophoneFrameCodec.HEADER_BYTES + 2)..
                WebRtcMicrophoneFrameCodec.MAX_FRAME_BYTES
            ) {
                return
            }
            val ownedBytes = ByteArray(byteCount)
            runCatching { data.slice().get(ownedBytes) }.getOrElse { return }
            val frame = WebRtcMicrophoneFrameCodec.decode(ownedBytes) ?: return
            if (!post {
                    if (
                        isCurrentMicrophoneDataChannel(runtime, channel, this) &&
                        runtime.accessMode == BrowserSessionAccessMode.CONTROL
                    ) {
                        dispatcher.dispatch(frame) {
                            isCurrentMicrophoneDataChannel(runtime, channel, this) &&
                                runtime.accessMode == BrowserSessionAccessMode.CONTROL
                        }
                    }
                }
            ) {
                releaseMicrophoneDataChannel(runtime, expectedChannel = channel)
            }
        }
    }

    private inner class AudioDataChannelObserver(
        private val runtime: SessionRuntime,
        private val track: BrowserAudioTrack,
        private val channel: DataChannel,
        private val sender: WebRtcAudioDataChannelSender,
    ) : DataChannel.Observer {
        override fun onBufferedAmountChange(previousAmount: Long) = Unit

        override fun onStateChange() {
            when (runCatching(channel::state).getOrNull()) {
                DataChannel.State.OPEN -> sender.start()
                DataChannel.State.CLOSING,
                DataChannel.State.CLOSED,
                -> post { releaseAudioDataChannel(runtime, track, expectedChannel = channel) }
                else -> Unit
            }
        }

        /** Output-audio channels are unidirectional at the application-protocol layer. */
        override fun onMessage(buffer: DataChannel.Buffer) = Unit
    }

    private data class CoreResources(
        val eglBase: EglBase,
        val factory: PeerConnectionFactory,
        val source: VideoSource,
        val track: VideoTrack,
        val bridge: WebRtcTextureVideoBridge,
        val localCapabilities: MediaCodecCapabilitySnapshot,
        val availableCodecs: List<WebRtcVideoCodec>,
    )

    private data class SessionRuntime(
        @Volatile var snapshot: BrowserWebRtcSessionSnapshot,
        val ownerKey: String,
        val selectedCodec: WebRtcVideoCodec,
        val preferredCodecs: List<WebRtcVideoCodec>,
        val connectedSequence: Long,
        var peer: PeerConnection? = null,
        var videoTransceiver: RtpTransceiver? = null,
        var controlDataChannel: ControlDataChannelAttachment? = null,
        var microphoneDataChannel: DataChannel? = null,
        var microphoneDataChannelObserver: DataChannel.Observer? = null,
        var videoStatsTask: ScheduledFuture<*>? = null,
        var lastVideoStats: WebRtcOutboundVideoStats? = null,
        val audioDataChannels: MutableMap<BrowserAudioTrack, AudioDataChannelAttachment> =
            mutableMapOf(),
    ) {
        val accessMode: BrowserSessionAccessMode
            get() = snapshot.publicMetadata?.accessMode ?: BrowserSessionAccessMode.CONTROL
    }

    private data class AudioDataChannelAttachment(
        val channel: DataChannel,
        val observer: DataChannel.Observer,
        val sender: WebRtcAudioDataChannelSender,
    )

    private data class ControlDataChannelAttachment(
        val channel: DataChannel,
        val observer: DataChannel.Observer,
        val codec: WebRtcControlDataChannelCodec,
    )

    private companion object {
        const val LOG_TAG = "TecomWebRtc"
        const val VIDEO_TRACK_ID = "tecom-projection-video"
        const val MEDIA_STREAM_ID = "tecom-projection"
        const val SESSION_ID_BYTES = 18
        const val MAX_SDP_CHARS = 128 * 1024
        const val MAX_DETAIL_CHARS = 240
        const val MAX_LOGGED_ICE_CANDIDATES = 8
        const val RELEASE_TIMEOUT_MILLIS = 2_000L
        const val VIDEO_STATS_INTERVAL_SECONDS = 5L
        const val CONTROL_JSON_CONTENT_TYPE = "application/json; charset=utf-8"

        val CONTROL_GET_TARGETS = setOf(
            "/api/status",
            "/api/notices",
            "/api/projection/profile",
            "/api/projection/viewport",
        )
        val CONTROL_POST_TARGETS = setOf(
            "/api/projection/viewport",
            "/api/touch",
            "/api/key",
        )

        val ACTIVE_SESSION_STATES = setOf(
            BrowserWebRtcSessionState.PENDING,
            BrowserWebRtcSessionState.READY,
        )
        val FACTORY_CODEC_ORDER = listOf(
            WebRtcVideoCodec.H264,
            WebRtcVideoCodec.VP8,
            WebRtcVideoCodec.VP9,
            WebRtcVideoCodec.AV1,
        )
        val AUTO_EFFICIENCY_ORDER = listOf(
            WebRtcVideoCodec.H264,
            WebRtcVideoCodec.VP8,
            WebRtcVideoCodec.VP9,
            WebRtcVideoCodec.AV1,
        )
        val ENCODER_ACCELERATION_ORDER = listOf(
            EncoderAcceleration.HARDWARE,
            EncoderAcceleration.UNKNOWN,
            EncoderAcceleration.SOFTWARE,
        )
        val SDP_DIRECTION_LINES = setOf(
            "a=sendrecv",
            "a=sendonly",
            "a=recvonly",
            "a=inactive",
        )
    }
}

/**
 * Keeps browser projection off carrier and VPN adapters.
 *
 * Cloudflare is signaling-only. Browser media may use Wi-Fi, a phone hotspot, Ethernet, or the
 * on-device loopback path, but a mobile-network handover must never become a media route.
 */
internal object WebRtcLocalNetworkPolicy {
    fun peerConnectionFactoryOptions(): PeerConnectionFactory.Options =
        PeerConnectionFactory.Options().apply {
            networkIgnoreMask = PeerConnectionFactory.Options.ADAPTER_TYPE_CELLULAR or
                PeerConnectionFactory.Options.ADAPTER_TYPE_VPN
        }

    fun isForbiddenSelectedAdapter(adapterType: PeerConnection.AdapterType): Boolean =
        adapterType in FORBIDDEN_SELECTED_ADAPTERS

    private val FORBIDDEN_SELECTED_ADAPTERS = setOf(
        PeerConnection.AdapterType.CELLULAR,
        PeerConnection.AdapterType.CELLULAR_2G,
        PeerConnection.AdapterType.CELLULAR_3G,
        PeerConnection.AdapterType.CELLULAR_4G,
        PeerConnection.AdapterType.CELLULAR_5G,
        PeerConnection.AdapterType.VPN,
    )
}

/** Keeps libwebrtc callbacks non-blocking and favors recent speech under receiver pressure. */
private class MicrophoneFrameDispatcher(
    private val source: BrowserMicrophonePcmSource,
    private val sessionAdmissionLock: Any,
) : Closeable {
    private val closed = AtomicBoolean(false)
    private val firstAcceptedFrameLogged = AtomicBoolean(false)
    private val executor = ThreadPoolExecutor(
        1,
        1,
        0L,
        TimeUnit.MILLISECONDS,
        ArrayBlockingQueue(MICROPHONE_DISPATCH_QUEUE_CAPACITY),
        { runnable -> Thread(runnable, THREAD_NAME).apply { isDaemon = true } },
        ThreadPoolExecutor.DiscardOldestPolicy(),
    )

    fun dispatch(frame: WebRtcMicrophoneFrame, stillOwned: () -> Boolean) {
        if (closed.get()) return
        runCatching {
            executor.execute {
                val accepted = dispatchOwnedMicrophoneFrameWithAdmissionLock(
                    lock = sessionAdmissionLock,
                    stillOwned = stillOwned,
                ) {
                    !closed.get() &&
                        source.acceptPcm16LittleEndian(
                            frame.format,
                            frame.pcm16LittleEndian,
                        )
                }
                if (accepted && firstAcceptedFrameLogged.compareAndSet(false, true)) {
                    Log.i(LOG_TAG, "WEBRTC_MICROPHONE_FRAME_RECEIVED")
                }
            }
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        executor.shutdownNow()
    }

    private companion object {
        const val LOG_TAG = "TecomWebRtc"
        const val MICROPHONE_DISPATCH_QUEUE_CAPACITY = 8
        const val THREAD_NAME = "TecomWebRtcMicrophone"
    }
}

internal fun dispatchOwnedMicrophoneFrameIfCurrent(
    stillOwned: () -> Boolean,
    dispatch: () -> Boolean,
): Boolean {
    if (!runCatching(stillOwned).getOrDefault(false)) return false
    return runCatching(dispatch).getOrDefault(false)
}

internal fun dispatchOwnedMicrophoneFrameWithAdmissionLock(
    lock: Any,
    stillOwned: () -> Boolean,
    dispatch: () -> Boolean,
): Boolean = synchronized(lock) {
    dispatchOwnedMicrophoneFrameIfCurrent(stillOwned, dispatch)
}

private object WebRtcLibraryInitializer {
    private val lock = Any()
    private var initialized = false

    fun ensureInitialized(context: Context) {
        synchronized(lock) {
            if (initialized) return
            PeerConnectionFactory.initialize(
                PeerConnectionFactory.InitializationOptions.builder(context.applicationContext)
                    .setEnableInternalTracer(false)
                    .createInitializationOptions(),
            )
            initialized = true
        }
    }
}
