/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.eigenkodex.navonweb.browser.webrtc

import android.util.Log
import com.eigenkodex.navonweb.browser.audio.BrowserAudioStreamHub
import com.eigenkodex.navonweb.browser.audio.BrowserAudioTrack
import java.io.Closeable
import java.nio.ByteBuffer
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.webrtc.DataChannel

/**
 * Pulls recent PCM from one browser-audio hub track without blocking libwebrtc callbacks.
 *
 * The hub owns a bounded drop-oldest queue. This sender additionally refuses to grow
 * libwebrtc's SCTP queue beyond [WebRtcAudioBackpressurePolicy.MAX_BUFFERED_AMOUNT_BYTES].
 * A hub reset closes the current subscription, after which the loop subscribes again so an
 * Android Auto reconnect does not permanently silence an otherwise-live browser session.
 */
internal class WebRtcAudioDataChannelSender(
    private val track: BrowserAudioTrack,
    private val streamHub: BrowserAudioStreamHub,
    private val channel: DataChannel,
    private val isCurrent: () -> Boolean,
) : Closeable {
    private val started = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)
    private val firstSentFrameLogged = AtomicBoolean(false)
    private val firstDroppedFrameLogged = AtomicBoolean(false)
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "NavOnWebWebRtcAudio-${track.wireName}").apply { isDaemon = true }
    }

    fun start() {
        if (closed.get() || !started.compareAndSet(false, true)) return
        runCatching { executor.execute(::sendLoop) }
            .onFailure { close() }
    }

    private fun sendLoop() {
        while (isReadyToSend()) {
            val subscription = streamHub.subscribe(track) ?: return
            try {
                while (isReadyToSend()) {
                    val pcm = try {
                        subscription.poll(POLL_TIMEOUT_MILLIS)
                    } catch (_: InterruptedException) {
                        return
                    }
                    if (pcm == null) {
                        if (subscription.isClosed) break
                        continue
                    }
                    sendChunk(pcm)
                }
            } finally {
                subscription.close()
            }
        }
    }

    private fun sendChunk(pcm16LittleEndian: ByteArray) {
        val format = streamHub.format(track)
        val encodedFrames = runCatching {
            WebRtcAudioFrameCodec.encodeChunk(format, pcm16LittleEndian)
        }.getOrElse {
            logFirstDrop("invalid_pcm")
            return
        }
        for (encoded in encodedFrames) {
            if (!isReadyToSend()) return
            val bufferedAmount = runCatching(channel::bufferedAmount).getOrElse { return }
            if (!WebRtcAudioBackpressurePolicy.canSend(bufferedAmount, encoded.size)) {
                logFirstDrop("backpressure")
                return
            }
            val sent = runCatching {
                channel.send(DataChannel.Buffer(ByteBuffer.wrap(encoded), true))
            }.getOrDefault(false)
            if (!sent) {
                logFirstDrop("send_rejected")
                return
            }
            if (firstSentFrameLogged.compareAndSet(false, true)) {
                Log.i(
                    LOG_TAG,
                    "WEBRTC_AUDIO_FRAME_SENT track=${track.wireName} " +
                        "rate=${format.sampleRateHz} channels=${format.channelCount}",
                )
            }
        }
    }

    private fun isReadyToSend(): Boolean =
        !closed.get() &&
            isCurrent() &&
            runCatching(channel::state).getOrNull() == DataChannel.State.OPEN

    private fun logFirstDrop(reason: String) {
        if (firstDroppedFrameLogged.compareAndSet(false, true)) {
            Log.w(LOG_TAG, "WEBRTC_AUDIO_FRAME_DROPPED track=${track.wireName} reason=$reason")
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        executor.shutdownNow()
        runCatching { executor.awaitTermination(RELEASE_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS) }
    }

    private companion object {
        const val LOG_TAG = "NavOnWebWebRtc"
        const val POLL_TIMEOUT_MILLIS = 500L
        const val RELEASE_TIMEOUT_MILLIS = 500L
    }
}
