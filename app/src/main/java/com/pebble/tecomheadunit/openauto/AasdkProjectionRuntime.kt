/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.pebble.tecomheadunit.openauto

import android.util.Log
import com.pebble.tecomheadunit.core.OpenAutoTouchEvent
import com.pebble.tecomheadunit.core.TouchPhase
import com.pebble.tecomheadunit.openauto.credential.HeadUnitCredential
import com.pebble.tecomheadunit.openauto.protocol.AasdkByteTransport
import com.pebble.tecomheadunit.openauto.protocol.AasdkAudioChannelController
import com.pebble.tecomheadunit.openauto.protocol.AasdkMicrophoneChannelController
import com.pebble.tecomheadunit.openauto.protocol.AasdkMicrophoneOpenStatus
import com.pebble.tecomheadunit.openauto.protocol.AasdkOpenAutoProtocol
import com.pebble.tecomheadunit.openauto.protocol.AasdkProjectionSession
import com.pebble.tecomheadunit.openauto.protocol.AasdkProjectionSessionConnector
import com.pebble.tecomheadunit.openauto.protocol.AasdkProtocolException
import com.pebble.tecomheadunit.openauto.protocol.AasdkSessionMessage
import com.pebble.tecomheadunit.openauto.protocol.AasdkTcpTransport
import com.pebble.tecomheadunit.openauto.protocol.AasdkTlsBuildBoundary
import com.pebble.tecomheadunit.openauto.protocol.AasdkTlsPeerTrustPolicy
import com.pebble.tecomheadunit.openauto.protocol.AasdkVideoPacketCodec
import com.pebble.tecomheadunit.openauto.protocol.AasdkVoiceSessionStatus
import com.pebble.tecomheadunit.openauto.sensor.FailDarkNightModeStateProvider
import com.pebble.tecomheadunit.openauto.sensor.NightModeDecision
import com.pebble.tecomheadunit.openauto.sensor.NightModeStateProvider
import java.io.Closeable
import java.io.IOException
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal data class AasdkProjectionRuntimeConfig(
    val host: String,
    val port: Int,
    val credential: HeadUnitCredential,
    val buildBoundary: AasdkTlsBuildBoundary,
    val peerTrustPolicy: AasdkTlsPeerTrustPolicy,
    val benchSafetyConfirmed: Boolean,
    val projectionConfig: OpenAutoConfig = ProjectionVideoProfile.FREE_800X480.toOpenAutoConfig(),
)

internal class AasdkPeerShutdownException : IllegalStateException("Android Auto requested shutdown")

/** No valid pong arrived within the bounded application-level watchdog. */
internal class AasdkLivenessTimeoutException : IOException("Android Auto ping watchdog expired")

internal const val AASDK_PING_INTERVAL_MILLIS = 5_000L
internal const val AASDK_MAX_PENDING_PINGS = 7
internal const val AASDK_MIN_PING_TOLERANCE_MILLIS =
    AASDK_PING_INTERVAL_MILLIS * AASDK_MAX_PENDING_PINGS

private sealed class QueuedAasdkInput {
    data class Touch(val event: OpenAutoTouchEvent) : QueuedAasdkInput()
    data class Key(
        val event: OpenAutoKeyEvent,
        val acceptedAtNanos: Long,
    ) : QueuedAasdkInput()
}

/**
 * Long-lived, parked-bench AASDK dispatcher.
 *
 * This stage validates projection transport, queues H.264 access units into the
 * supplied video sink, and acknowledges them only after decoder input accepts
 * the frame. Audio traffic is acknowledged but not played yet.
 */
internal class AasdkProjectionRuntime(
    private val videoSink: OpenAutoVideoSink,
    private val audioSink: OpenAutoPcmSink? = null,
    private val microphoneSource: OpenAutoMicrophoneSource? = null,
    private val nightModeStateProvider: NightModeStateProvider = FailDarkNightModeStateProvider,
    private val onNightModeDecision: (NightModeDecision) -> Unit = {},
) : Closeable {
    private val closeRequested = AtomicBoolean(false)
    private val pendingTransport = AtomicReference<AasdkByteTransport?>(null)
    private val activeSession = AtomicReference<AasdkProjectionSession?>(null)
    private val activeProjectionConfig = AtomicReference<OpenAutoConfig?>(null)
    private val inputChannelOpen = AtomicBoolean(false)
    private val inputReady = AtomicBoolean(false)
    private val activeInputQueue = AtomicReference<Channel<QueuedAasdkInput>?>(null)
    private val activeNightModeQueue = AtomicReference<Channel<Boolean>?>(null)
    private val nightModeSensorStarted = AtomicBoolean(false)
    private val latestNightModeDecision = AtomicReference<NightModeDecision?>(null)

    /** Re-evaluates time/location immediately and queues a changed NIGHT_DATA value when active. */
    fun refreshNightMode(): NightModeDecision {
        val decision = runCatching(nightModeStateProvider::currentDecision)
            .getOrElse { FailDarkNightModeStateProvider.currentDecision() }
        latestNightModeDecision.set(decision)
        runCatching { onNightModeDecision(decision) }
        if (nightModeSensorStarted.get()) {
            activeNightModeQueue.get()?.trySend(decision.isNight)
        }
        return decision
    }

    fun currentNightModeDecision(): NightModeDecision =
        latestNightModeDecision.get() ?: refreshNightMode()

    /**
     * Accepts one already-mapped touchscreen event without blocking its caller.
     *
     * A true result means the bounded, ordered AASDK send queue accepted the
     * event. Input indications have no peer ACK, so it does not claim that the
     * phone rendered or acted on the event.
     */
    fun sendTouch(event: OpenAutoTouchEvent): Boolean {
        val queue = activeInputQueue.get() ?: return false
        if (closeRequested.get() || !inputChannelOpen.get() || !inputReady.get()) return false
        if (activeSession.get()?.isOpen != true) return false
        val projectionConfig = activeProjectionConfig.get() ?: return false
        if (event.timestampNanos < 0L || event.x !in 0 until projectionConfig.viewport.width ||
            event.y !in 0 until projectionConfig.viewport.height || event.pointerId < 0
        ) {
            return false
        }
        if (activeInputQueue.get() !== queue) return false
        return queue.trySend(QueuedAasdkInput.Touch(event)).isSuccess
    }

    /** Accepts one validated key event into the same ordered queue used by touch. */
    fun sendKey(event: OpenAutoKeyEvent): Boolean {
        val queue = activeInputQueue.get() ?: return false
        if (!OpenAutoKeyCode.supports(event) || !isInputReady()) return false
        if (activeInputQueue.get() !== queue) return false
        return queue.trySend(
            QueuedAasdkInput.Key(
                event = event,
                acceptedAtNanos = System.nanoTime(),
            ),
        ).isSuccess
    }

    /** True only after the phone has opened and successfully bound the AA input channel. */
    private fun isInputReady(): Boolean =
        !closeRequested.get() &&
            inputChannelOpen.get() &&
            inputReady.get() &&
            activeSession.get()?.isOpen == true &&
            activeInputQueue.get() != null

    fun isTouchInputReady(): Boolean = isInputReady()

    /** Key and touch share the same AA channel binding and bounded ordered queue. */
    fun isKeyInputReady(): Boolean = isInputReady()

    /**
     * Keeps the projection available across recoverable transport loss.
     *
     * Every attempt calls [run], which closes the old transport and TLS state
     * before this method waits and opens a completely new AASDK session. A
     * healthy video stream resets the exponential backoff. Peer-requested
     * shutdown, trust, version and protocol failures remain terminal.
     */
    suspend fun runWithReconnect(
        config: AasdkProjectionRuntimeConfig,
        onStatus: suspend (String) -> Unit,
    ) {
        val policy = AasdkReconnectPolicy()
        var consecutiveFailures = 0

        while (true) {
            if (closeRequested.get()) throw CancellationException("AASDK runtime is closed")
            var videoBecameOperational = false
            try {
                run(config) { status ->
                    if (status.startsWith(VIDEO_OPERATIONAL_STATUS_PREFIX)) {
                        videoBecameOperational = true
                    }
                    onStatus(status)
                }
                throw IllegalStateException("AASDK session ended without a terminal signal")
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                if (closeRequested.get()) throw CancellationException("AASDK runtime is closed", error)
                consecutiveFailures = if (videoBecameOperational) 1 else consecutiveFailures + 1
                val decision = policy.decision(error, consecutiveFailures)
                if (!decision.retry) throw error

                // A new phone session starts with fresh codec configuration.
                // Releasing here prevents stale decoder input/output from the
                // failed session from crossing that protocol boundary.
                runCatching { videoSink.stop() }
                onStatus(
                    "DISCONNECTED reason=${decision.reason} " +
                        "retry=$consecutiveFailures delayMs=${decision.delayMillis}",
                )
                delay(decision.delayMillis)
                if (closeRequested.get()) throw CancellationException("AASDK runtime is closed")
                onStatus("RECONNECTING attempt=${consecutiveFailures + 1}")
            }
        }
    }

    suspend fun run(
        config: AasdkProjectionRuntimeConfig,
        onStatus: suspend (String) -> Unit,
    ) {
        if (!config.benchSafetyConfirmed) {
            throw SecurityException("AASDK session requires the parked bench safety gate")
        }
        if (closeRequested.get()) throw CancellationException("AASDK runtime is closed")

        inputReady.set(false)
        inputChannelOpen.set(false)
        nightModeSensorStarted.set(false)

        val connector = AasdkProjectionSessionConnector(
            openTransport = { host, port ->
                AasdkTcpTransport(host = host, port = port).also { transport ->
                    pendingTransport.set(transport)
                    if (closeRequested.get()) {
                        transport.close()
                        throw CancellationException("AASDK runtime is closed")
                    }
                    transport.connect()
                }
            },
        )

        if (!activeProjectionConfig.compareAndSet(null, config.projectionConfig)) {
            throw IllegalStateException("AASDK runtime already owns a projection profile")
        }
        var ownedSession: AasdkProjectionSession? = null
        try {
            onStatus("CONNECTING")
            val opened = connector.open(
                host = config.host,
                port = config.port,
                credential = config.credential,
                buildBoundary = config.buildBoundary,
                peerTrustPolicy = config.peerTrustPolicy,
            )
            pendingTransport.set(null)
            ownedSession = opened.session
            if (!activeSession.compareAndSet(null, ownedSession)) {
                throw IllegalStateException("AASDK runtime already owns a session")
            }
            if (closeRequested.get()) throw CancellationException("AASDK runtime is closed")

            onStatus("AUTHENTICATED peer=${opened.peerMajorVersion}.${opened.peerMinorVersion}")
            ownedSession.sendMessage(
                channelId = AasdkOpenAutoProtocol.CHANNEL_CONTROL,
                controlMessage = false,
                payload = AasdkOpenAutoProtocol.serviceDiscoveryResponse(config.projectionConfig),
            )
            onStatus(
                "SERVICE_DISCOVERY_RESPONSE_SENT channels=7 " +
                    "size=${config.projectionConfig.viewport.width}x${config.projectionConfig.viewport.height}",
            )
            runApplicationLoop(
                session = ownedSession,
                benchSafetyConfirmed = config.benchSafetyConfirmed,
                projectionConfig = config.projectionConfig,
                onStatus = onStatus,
            )
        } finally {
            activeInputQueue.getAndSet(null)?.close()
            inputReady.set(false)
            inputChannelOpen.set(false)
            nightModeSensorStarted.set(false)
            activeNightModeQueue.getAndSet(null)?.close()
            activeSession.compareAndSet(ownedSession, null)
            activeProjectionConfig.compareAndSet(config.projectionConfig, null)
            pendingTransport.getAndSet(null)?.let { runCatching { it.close() } }
            runCatching { ownedSession?.close() }
        }
    }

    override fun close() {
        if (!closeRequested.compareAndSet(false, true)) return
        activeInputQueue.getAndSet(null)?.close()
        inputReady.set(false)
        inputChannelOpen.set(false)
        nightModeSensorStarted.set(false)
        activeNightModeQueue.getAndSet(null)?.close()
        // Close the socket before cancelling the coroutine so a blocking read
        // is interrupted immediately rather than waiting for SO_TIMEOUT.
        pendingTransport.getAndSet(null)?.let { runCatching { it.close() } }
        activeSession.getAndSet(null)?.let { runCatching { it.close() } }
        runCatching { videoSink.stop() }
    }

    private suspend fun runApplicationLoop(
        session: AasdkProjectionSession,
        benchSafetyConfirmed: Boolean,
        projectionConfig: OpenAutoConfig,
        onStatus: suspend (String) -> Unit,
    ) = coroutineScope {
        val pendingPings = AasdkPendingPings(maxSize = AASDK_MAX_PENDING_PINGS)
        val avSessions = mutableMapOf<Int, Int>()
        val mediaFrames = mutableMapOf<Int, Long>()
        val audioController = audioSink?.let(::AasdkAudioChannelController)
        val microphoneController = microphoneSource?.let(::AasdkMicrophoneChannelController)
        val microphoneWireMutex = Mutex()
        val videoTimestamps = VideoTimestampNormalizer(
            fallbackFrameDurationUs = MICROS_PER_SECOND / projectionConfig.fps,
        )
        val inputQueue = Channel<QueuedAasdkInput>(capacity = INPUT_QUEUE_CAPACITY)
        val nightModeQueue = Channel<Boolean>(capacity = Channel.CONFLATED)
        if (!activeInputQueue.compareAndSet(null, inputQueue)) {
            inputQueue.close()
            throw IllegalStateException("AASDK runtime already owns an input queue")
        }

        val inputSender = launch {
            val timestamps = InputTimestampNormalizer()
            for (queued in inputQueue) {
                if (activeInputQueue.get() !== inputQueue) break
                if (!inputChannelOpen.get() || !inputReady.get()) {
                    if (
                        activeInputQueue.get() !== inputQueue ||
                        closeRequested.get() ||
                        !session.isOpen
                    ) {
                        break
                    }
                    throw AasdkProtocolException("input dequeued before input binding")
                }
                when (queued) {
                    is QueuedAasdkInput.Touch -> {
                        val event = queued.event
                        val timestampNanos = timestamps.normalize(event.timestampNanos)
                        session.sendMessage(
                            channelId = AasdkOpenAutoProtocol.CHANNEL_INPUT,
                            controlMessage = false,
                            payload = AasdkOpenAutoProtocol.inputEventIndication(
                                event = event,
                                timestampNanos = timestampNanos,
                            ),
                        )
                        if (event.phase == TouchPhase.MOVE) {
                            Log.d(
                                LOG_TAG,
                                "TOUCH_SENT phase=${event.phase} x=${event.x} y=${event.y} " +
                                    "pointer=${event.pointerId} timestampNs=$timestampNanos",
                            )
                        } else {
                            Log.i(
                                LOG_TAG,
                                "TOUCH_SENT phase=${event.phase} x=${event.x} y=${event.y} " +
                                    "pointer=${event.pointerId} timestampNs=$timestampNanos",
                            )
                        }
                    }

                    is QueuedAasdkInput.Key -> {
                        when (queued.event.action) {
                            OpenAutoKeyAction.CLICK -> {
                                val pressTimestampNanos = timestamps.normalize(queued.acceptedAtNanos)
                                val releaseTimestampNanos = timestamps.normalize(queued.acceptedAtNanos)
                                AasdkOpenAutoProtocol.keyInputEventIndications(
                                    event = queued.event,
                                    firstTimestampNanos = pressTimestampNanos,
                                    secondTimestampNanos = releaseTimestampNanos,
                                ).forEach { payload ->
                                    session.sendMessage(
                                        channelId = AasdkOpenAutoProtocol.CHANNEL_INPUT,
                                        controlMessage = false,
                                        payload = payload,
                                    )
                                }
                                Log.i(
                                    LOG_TAG,
                                    "KEY_CLICK_SENT code=${queued.event.scanCode} " +
                                        "pressTimestampNs=$pressTimestampNanos " +
                                        "releaseTimestampNs=$releaseTimestampNanos",
                                )
                            }

                            OpenAutoKeyAction.SCROLL_LEFT,
                            OpenAutoKeyAction.SCROLL_RIGHT,
                            -> {
                                val timestampNanos = timestamps.normalize(queued.acceptedAtNanos)
                                AasdkOpenAutoProtocol.keyInputEventIndications(
                                    event = queued.event,
                                    firstTimestampNanos = timestampNanos,
                                ).forEach { payload ->
                                    session.sendMessage(
                                        channelId = AasdkOpenAutoProtocol.CHANNEL_INPUT,
                                        controlMessage = false,
                                        payload = payload,
                                    )
                                }
                                Log.i(
                                    LOG_TAG,
                                    "KEY_SCROLL_SENT code=${queued.event.scanCode} " +
                                        "action=${queued.event.action} timestampNs=$timestampNanos",
                                )
                            }
                        }
                    }
                }
            }
        }

        val pinger = launch {
            while (true) {
                delay(AASDK_PING_INTERVAL_MILLIS)
                // Seven unanswered 5-second requests provide at least 35
                // seconds of tolerance. This is deliberately longer than the
                // 15-second pong gaps observed from Android Auto 17.2 while
                // keeping the outstanding request window strictly bounded.
                if (pendingPings.isFull) {
                    throw AasdkLivenessTimeoutException()
                }
                val timestampMicros = System.nanoTime() / NANOS_PER_MICROSECOND
                // Register before sending so an immediate response cannot race
                // ahead of the outstanding-request record.
                pendingPings.recordRequest(timestampMicros)
                session.sendMessage(
                    channelId = AasdkOpenAutoProtocol.CHANNEL_CONTROL,
                    controlMessage = false,
                    payload = AasdkOpenAutoProtocol.pingRequest(timestampMicros),
                    encrypted = false,
                )
            }
        }

        val nightModeSender = launch {
            var lastSent: Boolean? = null
            for (isNight in nightModeQueue) {
                if (!nightModeSensorStarted.get()) continue
                if (lastSent == isNight) continue
                session.sendMessage(
                    channelId = AasdkOpenAutoProtocol.CHANNEL_SENSOR,
                    controlMessage = false,
                    payload = AasdkOpenAutoProtocol.nightMode(isNight),
                )
                lastSent = isNight
                val decision = latestNightModeDecision.get()
                onStatus(
                    "NIGHT_MODE_UPDATED isNight=$isNight " +
                        "source=${decision?.source?.wireName ?: "unknown"}",
                )
            }
        }

        val nightModeEvaluator = launch {
            while (true) {
                delay(NIGHT_MODE_REEVALUATION_MILLIS)
                if (nightModeSensorStarted.get()) refreshNightMode()
            }
        }

        val microphoneSender = microphoneController?.let { controller ->
            launch {
                var framesSent = 0L
                while (true) {
                    val transmission = controller.nextTransmission(MICROPHONE_POLL_MILLIS)
                    if (transmission == null) {
                        delay(MICROPHONE_EMPTY_POLL_BACKOFF_MILLIS)
                        continue
                    }
                    var sent = false
                    microphoneWireMutex.withLock {
                        if (!controller.isCurrent(transmission)) return@withLock
                        session.sendMessage(
                            channelId = AasdkOpenAutoProtocol.CHANNEL_AV_INPUT,
                            controlMessage = false,
                            payload = AasdkOpenAutoProtocol.microphonePcmWithTimestamp(
                                timestampMicros = transmission.frame.capturedAtNanos /
                                    NANOS_PER_MICROSECOND,
                                pcm = transmission.frame.bytes,
                            ),
                        )
                        sent = true
                    }
                    if (!sent) continue
                    framesSent += 1L
                    if (framesSent == 1L || framesSent % MICROPHONE_STATUS_INTERVAL == 0L) {
                        onStatus(
                            "MICROPHONE_PCM_SENT frames=$framesSent " +
                                "bytes=${transmission.frame.bytes.size}",
                        )
                    }
                }
            }
        }

        try {
            while (true) {
                ensureActive()
                val message = session.receiveMessage()
                dispatch(
                    session = session,
                    message = message,
                    benchSafetyConfirmed = benchSafetyConfirmed,
                    avSessions = avSessions,
                    mediaFrames = mediaFrames,
                    videoTimestamps = videoTimestamps,
                    projectionConfig = projectionConfig,
                    pendingPings = pendingPings,
                    audioController = audioController,
                    microphoneController = microphoneController,
                    microphoneWireMutex = microphoneWireMutex,
                    nightModeQueue = nightModeQueue,
                    onStatus = onStatus,
                )
            }
        } finally {
            activeInputQueue.compareAndSet(inputQueue, null)
            inputReady.set(false)
            inputChannelOpen.set(false)
            inputQueue.close()
            nightModeSensorStarted.set(false)
            activeNightModeQueue.compareAndSet(nightModeQueue, null)
            nightModeQueue.close()
            inputSender.cancelAndJoin()
            nightModeSender.cancelAndJoin()
            nightModeEvaluator.cancelAndJoin()
            pinger.cancelAndJoin()
            microphoneSender?.cancelAndJoin()
            runCatching { microphoneController?.close() }
            runCatching { audioController?.close() }
        }
    }

    private suspend fun dispatch(
        session: AasdkProjectionSession,
        message: AasdkSessionMessage,
        benchSafetyConfirmed: Boolean,
        avSessions: MutableMap<Int, Int>,
        mediaFrames: MutableMap<Int, Long>,
        videoTimestamps: VideoTimestampNormalizer,
        projectionConfig: OpenAutoConfig,
        pendingPings: AasdkPendingPings,
        audioController: AasdkAudioChannelController?,
        microphoneController: AasdkMicrophoneChannelController?,
        microphoneWireMutex: Mutex,
        nightModeQueue: Channel<Boolean>,
        onStatus: suspend (String) -> Unit,
    ) {
        val id = AasdkOpenAutoProtocol.messageId(message.payload)
        val isAvMedia = message.channelId in AV_CHANNELS &&
            (id == AV_MEDIA_WITH_TIMESTAMP_INDICATION || id == AV_MEDIA_INDICATION)
        if (!isAvMedia) {
            Log.i(
                LOG_TAG,
                "RX channel=${message.channelId} control=${message.controlMessage} " +
                    "encrypted=${message.encrypted} id=0x${id.toString(16).padStart(4, '0')} " +
                    "size=${message.payload.size}",
            )
        }

        // Android Auto 17.2 returns this response as an encrypted specific
        // message, while older peers may return it in plain form. Accept both
        // wire protections. Responses can be delayed beyond the next 5-second
        // request, so validate against the bounded outstanding window instead
        // of a single last-request slot.
        if (
            message.channelId == AasdkOpenAutoProtocol.CHANNEL_CONTROL &&
            !message.controlMessage &&
            id == AasdkOpenAutoProtocol.PING_RESPONSE
        ) {
            val timestamp = AasdkOpenAutoProtocol.readFirstVarintField(
                AasdkOpenAutoProtocol.protobuf(message.payload),
                fieldNumber = 1,
            )
            pendingPings.acknowledge(timestamp)
            return
        }

        if (!message.encrypted) {
            throw AasdkProtocolException("unexpected plain post-authentication message")
        }

        if (message.controlMessage) {
            handleChannelControl(
                session,
                message,
                id,
                audioController,
                microphoneController,
                onStatus,
            )
            return
        }

        when (message.channelId) {
            AasdkOpenAutoProtocol.CHANNEL_CONTROL -> when (id) {
                AasdkOpenAutoProtocol.AUDIO_FOCUS_REQUEST -> {
                    val reply = audioController?.onAudioFocusRequest(message.payload)
                    session.sendMessage(
                        0,
                        false,
                        reply?.payload ?: AasdkOpenAutoProtocol.audioFocusResponse(message.payload),
                    )
                    onStatus("AUDIO_FOCUS_RESPONDED")
                }

                AasdkOpenAutoProtocol.SHUTDOWN_REQUEST -> {
                    session.sendMessage(0, false, AasdkOpenAutoProtocol.shutdownResponse())
                    onStatus("PEER_SHUTDOWN")
                    throw AasdkPeerShutdownException()
                }

                AasdkOpenAutoProtocol.VOICE_SESSION_REQUEST -> {
                    // This is a one-way notification. Pinned AASDK/OpenAuto
                    // deliberately sends no response and keeps receiving.
                    val status = AasdkOpenAutoProtocol.parseVoiceSessionStatus(message.payload)
                    onStatus(
                        when (status) {
                            AasdkVoiceSessionStatus.START -> "VOICE_SESSION_STARTED"
                            AasdkVoiceSessionStatus.END -> "VOICE_SESSION_ENDED"
                        },
                    )
                }

                NAVIGATION_FOCUS_REQUEST -> {
                    session.sendMessage(0, false, NAVIGATION_FOCUS_RESPONSE)
                    onStatus("NAVIGATION_FOCUS_RESPONDED")
                }

                else -> throw AasdkProtocolException("unsupported control message")
            }

            AasdkOpenAutoProtocol.CHANNEL_SENSOR -> when (id) {
                AasdkOpenAutoProtocol.SENSOR_START_REQUEST -> {
                    val sensorType = AasdkOpenAutoProtocol.readFirstVarintField(
                        AasdkOpenAutoProtocol.protobuf(message.payload),
                        fieldNumber = 1,
                    )
                    val event = when (sensorType) {
                        AasdkOpenAutoProtocol.SENSOR_TYPE_DRIVING_STATUS ->
                            AasdkOpenAutoProtocol.drivingStatusUnrestricted(benchSafetyConfirmed)
                        AasdkOpenAutoProtocol.SENSOR_TYPE_NIGHT_DATA -> byteArrayOf()
                        else -> null
                    }
                    session.sendMessage(
                        message.channelId,
                        false,
                        if (event == null) {
                            AasdkOpenAutoProtocol.sensorStartFailure()
                        } else {
                            AasdkOpenAutoProtocol.sensorStartSuccess()
                        },
                    )
                    if (event != null) {
                        if (sensorType == AasdkOpenAutoProtocol.SENSOR_TYPE_NIGHT_DATA) {
                            if (!nightModeSensorStarted.compareAndSet(false, true)) {
                                throw AasdkProtocolException("duplicate night mode sensor start")
                            }
                            if (!activeNightModeQueue.compareAndSet(null, nightModeQueue)) {
                                throw AasdkProtocolException("night mode queue is already active")
                            }
                            refreshNightMode()
                        } else {
                            session.sendMessage(message.channelId, false, event)
                        }
                        onStatus("SENSOR_STARTED type=$sensorType")
                    }
                }

                else -> throw AasdkProtocolException("unsupported sensor message")
            }

            AasdkOpenAutoProtocol.CHANNEL_INPUT -> when (id) {
                AasdkOpenAutoProtocol.BINDING_REQUEST -> {
                    if (!inputChannelOpen.get()) {
                        throw AasdkProtocolException("input binding arrived before channel open")
                    }
                    if (inputReady.get()) {
                        throw AasdkProtocolException("duplicate input binding request")
                    }
                    val requestedScanCodes =
                        AasdkOpenAutoProtocol.parseBindingRequestScanCodes(message.payload)
                    val accepted = requestedScanCodes.all(OpenAutoKeyCode.SUPPORTED::contains)
                    session.sendMessage(
                        message.channelId,
                        false,
                        if (accepted) {
                            AasdkOpenAutoProtocol.bindingSuccess()
                        } else {
                            AasdkOpenAutoProtocol.bindingFailure()
                        },
                    )
                    // sendMessage returns only after the encrypted response has
                    // reached the transport, so browser input cannot overtake
                    // the successful binding response on the wire.
                    inputReady.set(accepted)
                    onStatus(
                        if (accepted) {
                            "INPUT_BINDING_READY keys=${requestedScanCodes.size}"
                        } else {
                            "INPUT_BINDING_REJECTED keys=${requestedScanCodes.size}"
                        },
                    )
                }

                else -> throw AasdkProtocolException("unsupported input message")
            }

            in AV_CHANNELS -> handleAvMessage(
                session,
                message,
                id,
                avSessions,
                mediaFrames,
                videoTimestamps,
                projectionConfig,
                audioController,
                microphoneController,
                microphoneWireMutex,
                onStatus,
            )

            else -> throw AasdkProtocolException("message received on an unadvertised channel")
        }
    }

    private suspend fun handleChannelControl(
        session: AasdkProjectionSession,
        message: AasdkSessionMessage,
        id: Int,
        audioController: AasdkAudioChannelController?,
        microphoneController: AasdkMicrophoneChannelController?,
        onStatus: suspend (String) -> Unit,
    ) {
        if (id != AasdkOpenAutoProtocol.CHANNEL_OPEN_REQUEST || message.channelId !in ADVERTISED_CHANNELS) {
            throw AasdkProtocolException("unsupported channel control message")
        }
        val declaredChannel = AasdkOpenAutoProtocol.readFirstVarintField(
            AasdkOpenAutoProtocol.protobuf(message.payload),
            fieldNumber = 2,
        ) ?: throw AasdkProtocolException("channel open request has no channel id")
        if (declaredChannel != message.channelId.toLong()) {
            throw AasdkProtocolException("channel open request id mismatch")
        }
        if (message.channelId == AasdkOpenAutoProtocol.CHANNEL_INPUT && inputChannelOpen.get()) {
            throw AasdkProtocolException("duplicate input channel open request")
        }
        val audioChannel = OpenAutoAudioChannel.fromAasdkChannelId(message.channelId)
        val audioReply = if (audioController != null && audioChannel != null) {
            audioController.onChannelOpen(message.channelId)
        } else {
            null
        }
        val microphoneReply = if (
            message.channelId == AasdkOpenAutoProtocol.CHANNEL_AV_INPUT &&
            microphoneController != null
        ) {
            microphoneController.onChannelOpen()
        } else {
            null
        }
        session.sendMessage(
            channelId = message.channelId,
            controlMessage = true,
            payload = audioReply?.payload ?: microphoneReply ?: AasdkOpenAutoProtocol.channelOpenSuccess(),
        )
        if (message.channelId == AasdkOpenAutoProtocol.CHANNEL_INPUT) {
            // This transition is deliberately after the response write.
            inputChannelOpen.set(true)
        }
        if (audioReply != null) {
            onStatus("AUDIO_CHANNEL_${audioReply.status.name} channel=${message.channelId}")
        } else if (microphoneReply != null) {
            onStatus("MICROPHONE_CHANNEL_OPEN")
        } else {
            onStatus("CHANNEL_OPEN channel=${message.channelId}")
        }
    }

    private suspend fun handleAvMessage(
        session: AasdkProjectionSession,
        message: AasdkSessionMessage,
        id: Int,
        avSessions: MutableMap<Int, Int>,
        mediaFrames: MutableMap<Int, Long>,
        videoTimestamps: VideoTimestampNormalizer,
        projectionConfig: OpenAutoConfig,
        audioController: AasdkAudioChannelController?,
        microphoneController: AasdkMicrophoneChannelController?,
        microphoneWireMutex: Mutex,
        onStatus: suspend (String) -> Unit,
    ) {
        if (message.channelId == AasdkOpenAutoProtocol.CHANNEL_AV_INPUT) {
            when (id) {
                AasdkOpenAutoProtocol.AV_CHANNEL_SETUP_REQUEST -> {
                    val reply = microphoneController?.onSetupRequest(message.payload)
                        ?: AasdkOpenAutoProtocol.avChannelSetupSuccess()
                    session.sendMessage(message.channelId, false, reply)
                    onStatus("MICROPHONE_SETUP_READY")
                }

                AasdkOpenAutoProtocol.AV_INPUT_OPEN_REQUEST -> {
                    if (microphoneController == null) {
                        val request = AasdkOpenAutoProtocol.parseAvInputOpenRequest(message.payload)
                        session.sendMessage(
                            message.channelId,
                            false,
                            if (request.open) {
                                AasdkOpenAutoProtocol.avInputOpenUnavailable()
                            } else {
                                AasdkOpenAutoProtocol.avInputOpenSuccess()
                            },
                        )
                        onStatus(if (request.open) "MICROPHONE_UNAVAILABLE" else "MICROPHONE_CLOSED")
                    } else {
                        val transition = microphoneWireMutex.withLock {
                            microphoneController.prepareOpenRequest(message.payload).also { prepared ->
                                session.sendMessage(message.channelId, false, prepared.response)
                                microphoneController.completeOpenResponse(prepared)
                            }
                        }
                        onStatus(
                            when (transition.status) {
                                AasdkMicrophoneOpenStatus.STREAMING ->
                                    "MICROPHONE_STREAMING maxUnacked=${transition.maxUnacked} " +
                                        "anc=${transition.ancEnabled} ec=${transition.echoCancellationEnabled}"
                                AasdkMicrophoneOpenStatus.CLOSED -> "MICROPHONE_CLOSED"
                                AasdkMicrophoneOpenStatus.UNAVAILABLE -> "MICROPHONE_UNAVAILABLE"
                            },
                        )
                    }
                }

                AasdkOpenAutoProtocol.AV_MEDIA_ACK_INDICATION -> {
                    if (microphoneController == null) {
                        AasdkOpenAutoProtocol.parseAvMediaAck(message.payload)
                        Log.d(LOG_TAG, "MICROPHONE_ACK_IGNORED source=unavailable")
                    } else {
                        val event = microphoneController.onMediaAck(message.payload)
                        if (event.stale) {
                            Log.d(LOG_TAG, "MICROPHONE_ACK_STALE outstanding=${event.outstanding}")
                        }
                    }
                }

                else -> throw AasdkProtocolException("unsupported microphone AV message")
            }
            return
        }

        val audioChannel = OpenAutoAudioChannel.fromAasdkChannelId(message.channelId)
        if (audioController != null && audioChannel != null) {
            when (id) {
                AasdkOpenAutoProtocol.AV_CHANNEL_SETUP_REQUEST -> {
                    val reply = audioController.onSetupRequest(message.channelId, message.payload)
                    session.sendMessage(message.channelId, false, reply.payload)
                    onStatus("AUDIO_${reply.status.name} channel=${message.channelId}")
                }

                AasdkOpenAutoProtocol.AV_CHANNEL_START_INDICATION -> {
                    val event = audioController.onStartIndication(message.channelId, message.payload)
                    onStatus(
                        "AUDIO_${event.status.name} channel=${message.channelId} " +
                            "session=${event.session}",
                    )
                }

                AV_CHANNEL_STOP_INDICATION -> {
                    val event = audioController.onStopIndication(message.channelId, message.payload)
                    onStatus(
                        "AUDIO_${event.status.name} channel=${message.channelId} " +
                            "session=${event.session}",
                    )
                }

                AV_MEDIA_WITH_TIMESTAMP_INDICATION,
                AV_MEDIA_INDICATION,
                -> {
                    val reply = audioController.onMedia(message.channelId, message.payload)
                    session.sendMessage(message.channelId, false, reply.payload)
                    val frameCount = (mediaFrames[message.channelId] ?: 0L) + 1L
                    mediaFrames[message.channelId] = frameCount
                    if (frameCount == 1L || frameCount % AUDIO_STATUS_INTERVAL == 0L) {
                        onStatus(
                            "AUDIO_PCM_ACCEPTED channel=${message.channelId} " +
                                "frames=$frameCount bytes=${message.payload.size - 2}",
                        )
                    }
                }

                else -> throw AasdkProtocolException("unsupported audio AV message")
            }
            return
        }
        when (id) {
            AasdkOpenAutoProtocol.AV_CHANNEL_SETUP_REQUEST -> {
                val configIndex = AasdkOpenAutoProtocol.readFirstVarintField(
                    AasdkOpenAutoProtocol.protobuf(message.payload),
                    fieldNumber = 1,
                ) ?: 0
                // Current Android Auto uses this legacy field as its media
                // codec selector: PCM=1 on audio and H.264 BP=3 on video.
                // Keep zero only for old peers that omit the proto3 field.
                val expectedCodec = if (message.channelId == AasdkOpenAutoProtocol.CHANNEL_VIDEO) {
                    MEDIA_CODEC_H264_BASELINE
                } else {
                    MEDIA_CODEC_PCM
                }
                if (configIndex != LEGACY_DEFAULT_MEDIA_CODEC && configIndex != expectedCodec) {
                    throw AasdkProtocolException("unsupported AV config index")
                }
                if (message.channelId == AasdkOpenAutoProtocol.CHANNEL_VIDEO) {
                    if (!videoSink.open(projectionConfig)) {
                        session.sendMessage(
                            message.channelId,
                            false,
                            AasdkOpenAutoProtocol.avChannelSetupFailure(),
                        )
                        onStatus("VIDEO_DECODER_UNAVAILABLE")
                        throw AasdkSurfaceUnavailableException()
                    }
                    session.sendMessage(message.channelId, false, AasdkOpenAutoProtocol.avChannelSetupSuccess())
                    session.sendMessage(message.channelId, false, AasdkOpenAutoProtocol.videoFocusFocused())
                    onStatus(
                        "VIDEO_DECODER_READY ${projectionConfig.viewport.width}x" +
                            "${projectionConfig.viewport.height}@${projectionConfig.fps}",
                    )
                } else {
                    session.sendMessage(message.channelId, false, AasdkOpenAutoProtocol.avChannelSetupSuccess())
                    onStatus("AV_SETUP_READY channel=${message.channelId}")
                }
            }

            AasdkOpenAutoProtocol.AV_CHANNEL_START_INDICATION -> {
                val avSession = AasdkOpenAutoProtocol.readFirstVarintField(
                    AasdkOpenAutoProtocol.protobuf(message.payload),
                    fieldNumber = 1,
                )?.toInt() ?: 0
                if (avSession < 0) throw AasdkProtocolException("invalid AV session")
                avSessions[message.channelId] = avSession
                if (message.channelId == AasdkOpenAutoProtocol.CHANNEL_VIDEO) {
                    videoTimestamps.reset()
                }
                onStatus("AV_STREAM_STARTED channel=${message.channelId}")
            }

            AV_CHANNEL_STOP_INDICATION -> {
                avSessions.remove(message.channelId)
                if (message.channelId == AasdkOpenAutoProtocol.CHANNEL_VIDEO) {
                    videoTimestamps.reset()
                    videoSink.stop()
                }
                onStatus("AV_STREAM_STOPPED channel=${message.channelId}")
            }

            AV_MEDIA_WITH_TIMESTAMP_INDICATION,
            AV_MEDIA_INDICATION,
            -> {
                val avSession = avSessions[message.channelId]
                    ?: throw AasdkProtocolException("AV media arrived before start")
                var mediaBytes = message.payload.size - 2
                if (message.channelId == AasdkOpenAutoProtocol.CHANNEL_VIDEO) {
                    val packet = AasdkVideoPacketCodec.decode(message.payload)
                    val normalizedTimestamp = if (packet.codecConfigOnly) {
                        NormalizedVideoTimestamp(0L, VideoTimestampUnit.UNKNOWN)
                    } else {
                        videoTimestamps.normalize(packet.rawTimestamp)
                    }
                    videoSink.onEncodedFrame(
                        EncodedVideoFrame(
                            timestampMicros = normalizedTimestamp.presentationTimeUs,
                            bytes = packet.bytes,
                            offset = packet.dataOffset,
                            length = packet.dataLength,
                            codecConfigOnly = packet.codecConfigOnly,
                        ),
                    )
                    mediaBytes = packet.dataLength
                    if (normalizedTimestamp.unit != VideoTimestampUnit.UNKNOWN &&
                        (mediaFrames[message.channelId] ?: 0L) < 3L
                    ) {
                        Log.i(LOG_TAG, "VIDEO_TIMESTAMP_UNIT ${normalizedTimestamp.unit}")
                    }
                }
                val frameCount = (mediaFrames[message.channelId] ?: 0L) + 1L
                mediaFrames[message.channelId] = frameCount
                session.sendMessage(
                    message.channelId,
                    false,
                    AasdkOpenAutoProtocol.avMediaAck(avSession),
                )
                if (message.channelId == AasdkOpenAutoProtocol.CHANNEL_VIDEO &&
                    (frameCount == 1L || frameCount % VIDEO_STATUS_INTERVAL == 0L)
                ) {
                    Log.i(
                        LOG_TAG,
                        "RX video frames=$frameCount encrypted=${message.encrypted} " +
                            "id=0x${id.toString(16).padStart(4, '0')} bytes=$mediaBytes",
                    )
                    onStatus("VIDEO_MEDIA_ACCEPTED frames=$frameCount bytes=$mediaBytes")
                }
            }

            VIDEO_FOCUS_REQUEST -> {
                if (message.channelId != AasdkOpenAutoProtocol.CHANNEL_VIDEO) {
                    throw AasdkProtocolException("video focus request on non-video channel")
                }
                session.sendMessage(message.channelId, false, AasdkOpenAutoProtocol.videoFocusFocused())
                onStatus("VIDEO_FOCUSED")
            }

            else -> throw AasdkProtocolException("unsupported AV message")
        }
    }

    private companion object {
        const val VIDEO_STATUS_INTERVAL = 60L
        const val AUDIO_STATUS_INTERVAL = 100L
        const val NIGHT_MODE_REEVALUATION_MILLIS = 60_000L
        const val MICROPHONE_POLL_MILLIS = 100L
        const val MICROPHONE_EMPTY_POLL_BACKOFF_MILLIS = 5L
        const val MICROPHONE_STATUS_INTERVAL = 100L
        const val INPUT_QUEUE_CAPACITY = 64
        const val NANOS_PER_MICROSECOND = 1_000L
        const val MICROS_PER_SECOND = 1_000_000L
        const val LOG_TAG = "TecomAasdk"
        const val VIDEO_OPERATIONAL_STATUS_PREFIX = "VIDEO_MEDIA_ACCEPTED"
        const val NAVIGATION_FOCUS_REQUEST = 0x000D
        const val AV_MEDIA_WITH_TIMESTAMP_INDICATION = 0x0000
        const val AV_MEDIA_INDICATION = 0x0001
        const val AV_CHANNEL_STOP_INDICATION = 0x8002
        const val VIDEO_FOCUS_REQUEST = 0x8007
        const val LEGACY_DEFAULT_MEDIA_CODEC = 0L
        const val MEDIA_CODEC_PCM = 1L
        const val MEDIA_CODEC_H264_BASELINE = 3L

        val NAVIGATION_FOCUS_RESPONSE = byteArrayOf(0x00, 0x0E, 0x08, 0x02)
        val ADVERTISED_CHANNELS = 1..7
        val AV_CHANNELS = 3..7
    }
}

/**
 * Tracks the small window of ping requests that can be in flight concurrently.
 *
 * The runtime allows a fixed number of unanswered requests, so a delayed
 * response can arrive after newer requests have already been sent. A
 * timestamped response may therefore match any outstanding request. Legacy
 * timestamp-less responses consume the oldest request because the TCP stream
 * preserves response order.
 */
internal class AasdkPendingPings(
    private val maxSize: Int,
) {
    private val timestampsMicros = ArrayDeque<Long>()

    init {
        require(maxSize > 0) { "maxSize must be positive" }
    }

    val size: Int
        @Synchronized get() = timestampsMicros.size

    val isFull: Boolean
        @Synchronized get() = timestampsMicros.size >= maxSize

    @Synchronized
    fun recordRequest(timestampMicros: Long) {
        if (timestampMicros < 0L) throw AasdkProtocolException("invalid ping timestamp")
        if (timestampsMicros.contains(timestampMicros)) {
            throw AasdkProtocolException("duplicate ping request timestamp")
        }
        if (timestampsMicros.size >= maxSize) {
            throw AasdkProtocolException("ping request window exhausted")
        }
        timestampsMicros.addLast(timestampMicros)
    }

    @Synchronized
    fun acknowledge(responseTimestampMicros: Long?): Long {
        if (timestampsMicros.isEmpty()) {
            throw AasdkProtocolException("unsolicited ping response")
        }
        if (responseTimestampMicros == null) {
            return timestampsMicros.removeFirst()
        }
        if (!timestampsMicros.removeFirstOccurrence(responseTimestampMicros)) {
            throw AasdkProtocolException("ping response timestamp mismatch")
        }
        return responseTimestampMicros
    }
}

/** Preserves touch and key event time as strictly increasing AASDK nanoseconds. */
internal class InputTimestampNormalizer {
    private var previousNanos = -1L

    fun normalize(timestampNanos: Long): Long {
        if (timestampNanos < 0L) throw AasdkProtocolException("invalid touch timestamp")
        if (previousNanos == Long.MAX_VALUE) {
            throw AasdkProtocolException("touch timestamp exhausted")
        }
        return if (timestampNanos > previousNanos) {
            timestampNanos
        } else {
            previousNanos + 1L
        }.also { previousNanos = it }
    }
}
