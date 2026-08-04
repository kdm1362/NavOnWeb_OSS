/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.pebble.tecomheadunit.browser.audio

import com.pebble.tecomheadunit.audio.AudioStreamAccessTier
import com.pebble.tecomheadunit.audio.Pcm16AudioPolicy
import com.pebble.tecomheadunit.audio.Pcm16Format
import com.pebble.tecomheadunit.audio.StreamingPcm16Processor
import com.pebble.tecomheadunit.openauto.OpenAutoAudioChannel
import com.pebble.tecomheadunit.openauto.OpenAutoAudioFocus
import com.pebble.tecomheadunit.openauto.OpenAutoPcmFormat
import com.pebble.tecomheadunit.openauto.OpenAutoPcmFrame
import com.pebble.tecomheadunit.openauto.OpenAutoPcmSink
import java.io.Closeable

/** AA PCM16 -> server-enforced entitlement DSP -> authenticated browser stream hub. */
class BrowserAudioPcmPipeline(
    val accessTier: AudioStreamAccessTier,
) : OpenAutoPcmSink, Closeable {
    private val lock = Any()
    private val processors = mutableMapOf<BrowserAudioTrack, StreamingPcm16Processor>()
    val streamHub = BrowserAudioStreamHub(
        SOURCE_FORMATS.mapValues { (_, input) ->
            Pcm16AudioPolicy.resolve(accessTier, input).outputFormat
        },
    )

    fun sourceFormat(track: BrowserAudioTrack): Pcm16Format = SOURCE_FORMATS.getValue(track)

    @Volatile
    var lastFocus: OpenAutoAudioFocus = OpenAutoAudioFocus.NONE
        private set

    override fun onOpen(channel: OpenAutoAudioChannel, format: OpenAutoPcmFormat): Boolean =
        open(
            track = channel.toBrowserTrack(),
            inputFormat = Pcm16Format(
                sampleRateHz = format.sampleRateHz,
                channelCount = format.channelCount,
            ),
        )

    override fun onStart(channel: OpenAutoAudioChannel): Boolean = synchronized(lock) {
        val track = channel.toBrowserTrack()
        if (!processors.containsKey(track)) {
            processors[track] = StreamingPcm16Processor(accessTier, sourceFormat(track))
        }
        true
    }

    override fun onPcmFrame(frame: OpenAutoPcmFrame): Boolean = onPcm16LittleEndian(
        track = frame.channel.toBrowserTrack(),
        bytes = frame.bytes,
        offset = 0,
        length = frame.bytes.size,
    )

    override fun onSuspend(channel: OpenAutoAudioChannel) {
        stop(channel.toBrowserTrack())
    }

    override fun onClose(channel: OpenAutoAudioChannel) {
        stop(channel.toBrowserTrack())
    }

    override fun onAudioFocusChanged(focus: OpenAutoAudioFocus) {
        lastFocus = focus
    }

    fun open(track: BrowserAudioTrack, inputFormat: Pcm16Format): Boolean = synchronized(lock) {
        if (inputFormat != sourceFormat(track)) return false
        processors[track] = StreamingPcm16Processor(accessTier, inputFormat)
        true
    }

    fun onPcm16LittleEndian(
        track: BrowserAudioTrack,
        bytes: ByteArray,
        offset: Int,
        length: Int,
    ): Boolean = synchronized(lock) {
        if (offset < 0 || length <= 0 || offset > bytes.size - length || length % 2 != 0) return false
        val processor = processors[track] ?: return false
        val frameBytes = processor.inputFormat.channelCount * 2
        if (length % frameBytes != 0) return false
        val samples = ShortArray(length / 2)
        var sourceOffset = offset
        for (index in samples.indices) {
            val low = bytes[sourceOffset].toInt() and 0xFF
            val high = bytes[sourceOffset + 1].toInt()
            samples[index] = ((high shl 8) or low).toShort()
            sourceOffset += 2
        }
        publish(track, processor.process(samples))
        true
    }

    fun stop(track: BrowserAudioTrack) = synchronized(lock) {
        val processor = processors.remove(track) ?: return@synchronized
        publish(track, runCatching(processor::finish).getOrDefault(ShortArray(0)))
    }

    /** Drops partial DSP state and resets browser stream subscriptions before a fresh AA session. */
    fun reset() = synchronized(lock) {
        processors.clear()
        streamHub.reset()
    }

    override fun close() = synchronized(lock) {
        processors.clear()
        streamHub.close()
    }

    private fun publish(track: BrowserAudioTrack, samples: ShortArray) {
        if (samples.isEmpty()) return
        val bytes = ByteArray(samples.size * 2)
        samples.forEachIndexed { index, sample ->
            val value = sample.toInt()
            bytes[index * 2] = value.toByte()
            bytes[index * 2 + 1] = (value shr 8).toByte()
        }
        streamHub.publish(track, bytes)
    }

    companion object {
        val SOURCE_FORMATS: Map<BrowserAudioTrack, Pcm16Format> = mapOf(
            BrowserAudioTrack.MEDIA to Pcm16Format(sampleRateHz = 48_000, channelCount = 2),
            BrowserAudioTrack.SPEECH to Pcm16Format(sampleRateHz = 16_000, channelCount = 1),
            BrowserAudioTrack.SYSTEM to Pcm16Format(sampleRateHz = 16_000, channelCount = 1),
        )
    }
}

private fun OpenAutoAudioChannel.toBrowserTrack(): BrowserAudioTrack = when (this) {
    OpenAutoAudioChannel.MEDIA -> BrowserAudioTrack.MEDIA
    OpenAutoAudioChannel.GUIDANCE -> BrowserAudioTrack.SPEECH
    OpenAutoAudioChannel.SYSTEM -> BrowserAudioTrack.SYSTEM
}
