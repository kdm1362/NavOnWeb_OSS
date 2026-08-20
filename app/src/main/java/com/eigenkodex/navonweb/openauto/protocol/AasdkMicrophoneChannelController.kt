/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.eigenkodex.navonweb.openauto.protocol

import com.eigenkodex.navonweb.openauto.OpenAutoMicrophoneFrame
import com.eigenkodex.navonweb.openauto.OpenAutoMicrophoneSource
import java.io.Closeable

internal enum class AasdkMicrophoneOpenStatus {
    STREAMING,
    CLOSED,
    UNAVAILABLE,
}

internal data class AasdkMicrophoneOpenTransition(
    val generation: Long,
    val status: AasdkMicrophoneOpenStatus,
    val response: ByteArray,
    val ancEnabled: Boolean,
    val echoCancellationEnabled: Boolean,
    val maxUnacked: Int,
)

internal data class AasdkMicrophoneTransmission(
    val generation: Long,
    val frame: OpenAutoMicrophoneFrame,
)

internal data class AasdkMicrophoneAckEvent(
    val accepted: Int,
    val outstanding: Int,
    val stale: Boolean,
)

/**
 * Strict state machine for OpenAuto's AV-input channel 7.
 *
 * The owner must serialize [prepareOpenRequest], its response write, and
 * [completeOpenResponse] with microphone media writes. This prevents a PCM
 * packet from overtaking the successful open response or following a close
 * response. The browser source remains service-owned and reusable across AA
 * reconnects; [close] therefore calls only [OpenAutoMicrophoneSource.stop].
 */
internal class AasdkMicrophoneChannelController(
    private val source: OpenAutoMicrophoneSource,
) : Closeable {
    private val lock = Any()
    private var channelOpen = false
    private var generation = 0L
    private var pendingGeneration: Long? = null
    private var streaming = false
    private var sendWindow = DEFAULT_SEND_WINDOW
    private var outstanding = 0
    private var closed = false

    fun onChannelOpen(): ByteArray = synchronized(lock) {
        ensureNotClosed()
        if (channelOpen) throw AasdkProtocolException("duplicate microphone channel open")
        channelOpen = true
        AasdkOpenAutoProtocol.channelOpenSuccess()
    }

    fun onSetupRequest(applicationPayload: ByteArray): ByteArray = synchronized(lock) {
        ensureNotClosed()
        if (!channelOpen) throw AasdkProtocolException("microphone setup before channel open")
        if (AasdkOpenAutoProtocol.messageId(applicationPayload) !=
            AasdkOpenAutoProtocol.AV_CHANNEL_SETUP_REQUEST
        ) {
            throw AasdkProtocolException("not a microphone setup request")
        }
        val configIndex = AasdkOpenAutoProtocol.readFirstVarintField(
            AasdkOpenAutoProtocol.protobuf(applicationPayload),
            fieldNumber = 1,
        ) ?: 0L
        if (configIndex != LEGACY_CONFIG_INDEX && configIndex != PCM_CONFIG_INDEX) {
            throw AasdkProtocolException("unsupported microphone config index")
        }
        AasdkOpenAutoProtocol.avChannelSetupSuccess()
    }

    /** Prepares a transition; the caller must write [AasdkMicrophoneOpenTransition.response]. */
    fun prepareOpenRequest(applicationPayload: ByteArray): AasdkMicrophoneOpenTransition =
        synchronized(lock) {
            ensureNotClosed()
            if (!channelOpen) {
                throw AasdkProtocolException("microphone open before channel open")
            }
            val request = AasdkOpenAutoProtocol.parseAvInputOpenRequest(applicationPayload)
            generation = Math.addExact(generation, 1L)
            pendingGeneration = null
            streaming = false
            outstanding = 0
            val sourceStopped = stopSourceSafely()

            if (!request.open) {
                return@synchronized AasdkMicrophoneOpenTransition(
                    generation = generation,
                    status = AasdkMicrophoneOpenStatus.CLOSED,
                    response = AasdkOpenAutoProtocol.avInputOpenSuccess(),
                    ancEnabled = request.ancEnabled,
                    echoCancellationEnabled = request.echoCancellationEnabled,
                    maxUnacked = request.maxUnacked,
                )
            }

            sendWindow = request.maxUnacked
                .takeIf { it > 0 }
                ?.coerceAtMost(MAX_SEND_WINDOW)
                ?: DEFAULT_SEND_WINDOW
            val available = sourceStopped && try {
                source.format == MICROPHONE_FORMAT && source.isAvailable() && source.start()
            } catch (_: Exception) {
                false
            }
            if (!available) stopSourceSafely()
            if (available) pendingGeneration = generation
            AasdkMicrophoneOpenTransition(
                generation = generation,
                status = if (available) {
                    AasdkMicrophoneOpenStatus.STREAMING
                } else {
                    AasdkMicrophoneOpenStatus.UNAVAILABLE
                },
                response = if (available) {
                    AasdkOpenAutoProtocol.avInputOpenSuccess()
                } else {
                    AasdkOpenAutoProtocol.avInputOpenUnavailable()
                },
                ancEnabled = request.ancEnabled,
                echoCancellationEnabled = request.echoCancellationEnabled,
                maxUnacked = sendWindow,
            )
        }

    /** Called only after the open/close response has reached the encrypted transport. */
    fun completeOpenResponse(transition: AasdkMicrophoneOpenTransition) = synchronized(lock) {
        if (closed || transition.generation != generation) return@synchronized
        streaming = transition.status == AasdkMicrophoneOpenStatus.STREAMING &&
            pendingGeneration == transition.generation
        pendingGeneration = null
    }

    /** Pulls and reserves one send-window slot. A close race makes the returned lease stale. */
    fun nextTransmission(timeoutMillis: Long): AasdkMicrophoneTransmission? {
        val expectedGeneration = synchronized(lock) {
            if (closed || !streaming || outstanding >= sendWindow) return null
            generation
        }
        val frame = try {
            source.poll(timeoutMillis)
        } catch (_: Exception) {
            synchronized(lock) {
                if (!closed && generation == expectedGeneration) {
                    streaming = false
                    pendingGeneration = null
                    outstanding = 0
                }
            }
            stopSourceSafely()
            return null
        } ?: return null
        return synchronized(lock) {
            if (closed || !streaming || generation != expectedGeneration || outstanding >= sendWindow) {
                null
            } else {
                outstanding += 1
                AasdkMicrophoneTransmission(generation = generation, frame = frame)
            }
        }
    }

    fun isCurrent(transmission: AasdkMicrophoneTransmission): Boolean = synchronized(lock) {
        !closed && streaming && transmission.generation == generation
    }

    /**
     * Racy snapshot of whether the channel is currently streaming.
     *
     * The value may be stale the moment this method returns, so callers must
     * use it only to pick a poll cadence. Correctness never depends on it:
     * [nextTransmission] re-validates the real state under the lock.
     */
    fun isStreaming(): Boolean = synchronized(lock) { streaming && !closed }

    /** Malformed ACKs fail closed; clearly stale valid ACKs do not terminate the AA session. */
    fun onMediaAck(applicationPayload: ByteArray): AasdkMicrophoneAckEvent {
        val ack = AasdkOpenAutoProtocol.parseAvMediaAck(applicationPayload)
        return synchronized(lock) {
            if (closed || ack.session != MICROPHONE_SESSION || !streaming || ack.value == 0L || outstanding == 0) {
                return@synchronized AasdkMicrophoneAckEvent(
                    accepted = 0,
                    outstanding = outstanding,
                    stale = true,
                )
            }
            val accepted = minOf(ack.value, outstanding.toLong()).toInt()
            outstanding -= accepted
            AasdkMicrophoneAckEvent(
                accepted = accepted,
                outstanding = outstanding,
                stale = false,
            )
        }
    }

    override fun close() = synchronized(lock) {
        if (closed) return@synchronized
        closed = true
        streaming = false
        pendingGeneration = null
        outstanding = 0
        stopSourceSafely()
        Unit
    }

    private fun ensureNotClosed() {
        if (closed) throw IllegalStateException("microphone channel is closed")
    }

    private fun stopSourceSafely(): Boolean = try {
        source.stop()
        true
    } catch (_: Exception) {
        false
    }

    private companion object {
        const val MICROPHONE_SESSION = 0
        const val LEGACY_CONFIG_INDEX = 0L
        const val PCM_CONFIG_INDEX = 1L
        const val DEFAULT_SEND_WINDOW = 1
        const val MAX_SEND_WINDOW = 8
        val MICROPHONE_FORMAT = com.eigenkodex.navonweb.openauto.OpenAutoPcmFormat(
            sampleRateHz = 16_000,
            channelCount = 1,
        )
    }
}
