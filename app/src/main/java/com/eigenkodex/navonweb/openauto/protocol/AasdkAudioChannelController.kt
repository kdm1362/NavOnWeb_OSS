/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.eigenkodex.navonweb.openauto.protocol

import com.eigenkodex.navonweb.openauto.AasdkAudioSinkException
import com.eigenkodex.navonweb.openauto.AasdkAudioSinkFailure
import com.eigenkodex.navonweb.openauto.OpenAutoAudioChannel
import com.eigenkodex.navonweb.openauto.OpenAutoAudioFocus
import com.eigenkodex.navonweb.openauto.OpenAutoPcmSink
import java.io.Closeable
import java.util.EnumMap

internal enum class AasdkAudioWireStatus {
    CHANNEL_OPENED,
    CHANNEL_OPEN_REJECTED,
    SETUP_READY,
    SETUP_REJECTED_UNSUPPORTED_CODEC,
    STREAM_STARTED,
    STREAM_SUSPENDED,
    PCM_ACCEPTED,
    FOCUS_RESPONDED,
}

internal data class AasdkAudioWireReply(
    val payload: ByteArray,
    val status: AasdkAudioWireStatus,
)

internal data class AasdkAudioWireEvent(
    val status: AasdkAudioWireStatus,
    val channel: OpenAutoAudioChannel?,
    val session: Int? = null,
)

/**
 * Stateful wire adapter for pinned OpenAuto MEDIA/SPEECH/SYSTEM audio services.
 *
 * It deliberately owns no transport. The runtime sends returned replies on the incoming channel,
 * making this class independently testable and keeping WebRTC/UI ownership outside the protocol.
 */
internal class AasdkAudioChannelController(
    private val sink: OpenAutoPcmSink,
) : Closeable {
    private val states = EnumMap<OpenAutoAudioChannel, ChannelState>(OpenAutoAudioChannel::class.java)
    private var closed = false

    @Synchronized
    fun onChannelOpen(channelId: Int): AasdkAudioWireReply {
        ensureOpen()
        val channel = requireAudioChannel(channelId)
        if (states.containsKey(channel)) {
            throw AasdkProtocolException("duplicate audio channel open")
        }
        val accepted = runCatching { sink.onOpen(channel, channel.format) }.getOrDefault(false)
        if (!accepted) {
            runCatching { sink.onClose(channel) }
            return AasdkAudioWireReply(
                payload = channelOpenResponse(success = false),
                status = AasdkAudioWireStatus.CHANNEL_OPEN_REJECTED,
            )
        }
        states[channel] = ChannelState()
        return AasdkAudioWireReply(
            payload = channelOpenResponse(success = true),
            status = AasdkAudioWireStatus.CHANNEL_OPENED,
        )
    }

    @Synchronized
    fun onSetupRequest(
        channelId: Int,
        applicationPayload: ByteArray,
    ): AasdkAudioWireReply {
        ensureOpen()
        requireMessageId(applicationPayload, AasdkOpenAutoProtocol.AV_CHANNEL_SETUP_REQUEST)
        val channel = requireAudioChannel(channelId)
        val state = requireState(channel)
        if (state.setupConfig != null) throw AasdkProtocolException("duplicate audio setup")

        val requestedConfig = AasdkOpenAutoProtocol.readFirstVarintField(
            AasdkOpenAutoProtocol.protobuf(applicationPayload),
            fieldNumber = 1,
        ) ?: PINNED_CONFIG_INDEX
        if (requestedConfig != PINNED_CONFIG_INDEX && requestedConfig != MEDIA_CODEC_AUDIO_PCM) {
            return AasdkAudioWireReply(
                payload = AasdkOpenAutoProtocol.avChannelSetupFailure(),
                status = AasdkAudioWireStatus.SETUP_REJECTED_UNSUPPORTED_CODEC,
            )
        }
        state.setupConfig = requestedConfig.toInt()
        return AasdkAudioWireReply(
            payload = AasdkOpenAutoProtocol.avChannelSetupSuccess(),
            status = AasdkAudioWireStatus.SETUP_READY,
        )
    }

    @Synchronized
    fun onStartIndication(
        channelId: Int,
        applicationPayload: ByteArray,
    ): AasdkAudioWireEvent {
        ensureOpen()
        requireMessageId(applicationPayload, AasdkOpenAutoProtocol.AV_CHANNEL_START_INDICATION)
        val channel = requireAudioChannel(channelId)
        val state = requireState(channel)
        val setupConfig = state.setupConfig
            ?: throw AasdkProtocolException("audio start arrived before setup")
        if (state.session != null) throw AasdkProtocolException("duplicate audio start")

        val protobuf = AasdkOpenAutoProtocol.protobuf(applicationPayload)
        val sessionValue = AasdkOpenAutoProtocol.readFirstVarintField(protobuf, fieldNumber = 1) ?: 0L
        if (sessionValue !in 0L..Int.MAX_VALUE.toLong()) {
            throw AasdkProtocolException("invalid audio session")
        }
        val startConfig = AasdkOpenAutoProtocol.readFirstVarintField(protobuf, fieldNumber = 2) ?: 0L
        if (startConfig != PINNED_CONFIG_INDEX && startConfig != setupConfig.toLong()) {
            throw AasdkProtocolException("audio start config was not negotiated")
        }

        val started = try {
            sink.onStart(channel)
        } catch (error: Throwable) {
            throw AasdkAudioSinkException(AasdkAudioSinkFailure.CALLBACK_FAILED, error)
        }
        if (!started) {
            throw AasdkAudioSinkException(AasdkAudioSinkFailure.START_REJECTED)
        }
        state.session = sessionValue.toInt()
        return AasdkAudioWireEvent(
            status = AasdkAudioWireStatus.STREAM_STARTED,
            channel = channel,
            session = state.session,
        )
    }

    @Synchronized
    fun onStopIndication(
        channelId: Int,
        applicationPayload: ByteArray,
    ): AasdkAudioWireEvent {
        ensureOpen()
        requireMessageId(applicationPayload, AV_CHANNEL_STOP_INDICATION)
        val channel = requireAudioChannel(channelId)
        val state = requireState(channel)
        val oldSession = state.session
            ?: throw AasdkProtocolException("audio stop arrived before start")
        try {
            sink.onSuspend(channel)
        } catch (error: Throwable) {
            throw AasdkAudioSinkException(AasdkAudioSinkFailure.CALLBACK_FAILED, error)
        }
        state.session = null
        return AasdkAudioWireEvent(
            status = AasdkAudioWireStatus.STREAM_SUSPENDED,
            channel = channel,
            session = oldSession,
        )
    }

    /** Returns the ACK only after the callback synchronously accepts the owned PCM packet. */
    @Synchronized
    fun onMedia(
        channelId: Int,
        applicationPayload: ByteArray,
    ): AasdkAudioWireReply {
        ensureOpen()
        val channel = requireAudioChannel(channelId)
        val state = requireState(channel)
        val session = state.session
            ?: throw AasdkProtocolException("audio media arrived before start")
        val frame = AasdkAudioPacketCodec.decode(channel, applicationPayload)
        val accepted = try {
            sink.onPcmFrame(frame)
        } catch (error: Throwable) {
            throw AasdkAudioSinkException(AasdkAudioSinkFailure.CALLBACK_FAILED, error)
        }
        if (!accepted) {
            throw AasdkAudioSinkException(AasdkAudioSinkFailure.PCM_REJECTED)
        }
        return AasdkAudioWireReply(
            payload = AasdkOpenAutoProtocol.avMediaAck(session),
            status = AasdkAudioWireStatus.PCM_ACCEPTED,
        )
    }

    @Synchronized
    fun onAudioFocusRequest(applicationPayload: ByteArray): AasdkAudioWireReply {
        ensureOpen()
        requireMessageId(applicationPayload, AasdkOpenAutoProtocol.AUDIO_FOCUS_REQUEST)
        val wireFocus = AasdkOpenAutoProtocol.readFirstVarintField(
            AasdkOpenAutoProtocol.protobuf(applicationPayload),
            fieldNumber = 1,
        ) ?: 0L
        val focus = when (wireFocus) {
            0L -> OpenAutoAudioFocus.NONE
            1L -> OpenAutoAudioFocus.GAIN
            2L -> OpenAutoAudioFocus.GAIN_TRANSIENT
            3L -> OpenAutoAudioFocus.GAIN_NAVIGATION
            4L -> OpenAutoAudioFocus.RELEASE
            else -> throw AasdkProtocolException("unsupported audio focus type")
        }
        try {
            sink.onAudioFocusChanged(focus)
        } catch (error: Throwable) {
            throw AasdkAudioSinkException(AasdkAudioSinkFailure.CALLBACK_FAILED, error)
        }
        return AasdkAudioWireReply(
            payload = AasdkOpenAutoProtocol.audioFocusResponse(applicationPayload),
            status = AasdkAudioWireStatus.FOCUS_RESPONDED,
        )
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        val openedChannels = states.keys.toList()
        states.clear()
        openedChannels.forEach { channel -> runCatching { sink.onClose(channel) } }
    }

    private fun requireState(channel: OpenAutoAudioChannel): ChannelState =
        states[channel] ?: throw AasdkProtocolException("audio channel is not open")

    private fun requireAudioChannel(channelId: Int): OpenAutoAudioChannel =
        OpenAutoAudioChannel.fromAasdkChannelId(channelId)
            ?: throw AasdkProtocolException("not an advertised audio channel")

    private fun ensureOpen() {
        if (closed) throw IllegalStateException("audio channel controller is closed")
    }

    private fun requireMessageId(applicationPayload: ByteArray, expected: Int) {
        if (AasdkOpenAutoProtocol.messageId(applicationPayload) != expected) {
            throw AasdkProtocolException("unexpected audio message id")
        }
    }

    private data class ChannelState(
        var setupConfig: Int? = null,
        var session: Int? = null,
    )

    private companion object {
        const val AV_CHANNEL_STOP_INDICATION = 0x8002
        const val PINNED_CONFIG_INDEX = 0L
        const val MEDIA_CODEC_AUDIO_PCM = 1L

        /** ChannelOpenResponse{status=OK/FAIL}; status is a required proto2 field. */
        fun channelOpenResponse(success: Boolean): ByteArray = byteArrayOf(
            0x00,
            0x08,
            0x08,
            (if (success) 0x00 else 0x01).toByte(),
        )
    }
}
