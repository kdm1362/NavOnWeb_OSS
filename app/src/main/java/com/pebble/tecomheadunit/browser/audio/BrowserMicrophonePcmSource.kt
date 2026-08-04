/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.pebble.tecomheadunit.browser.audio

import com.pebble.tecomheadunit.audio.AudioStreamAccessTier
import com.pebble.tecomheadunit.audio.Pcm16Format
import com.pebble.tecomheadunit.audio.StreamingPcm16Processor
import com.pebble.tecomheadunit.openauto.OpenAutoMicrophoneFrame
import com.pebble.tecomheadunit.openauto.OpenAutoMicrophoneSource
import com.pebble.tecomheadunit.openauto.OpenAutoPcmFormat
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Browser-uploaded PCM16 source for OpenAuto's microphone/AV-input channel.
 *
 * Upload calls are serialized through a stateful [StreamingPcm16Processor]. Every accepted input
 * format is normalized to the fixed OpenAuto 16 kHz mono format. The bounded queue always favors
 * recent speech: when its consumer falls behind, the oldest normalized frame is discarded.
 */
class BrowserMicrophonePcmSource(
    private val queueCapacity: Int = DEFAULT_QUEUE_CAPACITY,
    private val availabilityWindowNanos: Long = DEFAULT_AVAILABILITY_WINDOW_NANOS,
    private val nanoTime: () -> Long = System::nanoTime,
) : OpenAutoMicrophoneSource {
    override val format = OpenAutoPcmFormat(sampleRateHz = OUTPUT_SAMPLE_RATE_HZ, channelCount = 1)

    private val lock = Any()
    private val queue: ArrayBlockingQueue<OpenAutoMicrophoneFrame>
    private val timestampOriginNanos: Long

    private var latestInputFormat: Pcm16Format? = null
    private var processor: StreamingPcm16Processor? = null
    private var lastUploadClockNanos: Long? = null
    private var lastCaptureTimestampNanos: Long? = null
    private var started = false
    private var closed = false

    init {
        require(queueCapacity > 0) { "microphone queue capacity must be positive" }
        require(availabilityWindowNanos > 0L) { "availability window must be positive" }
        queue = ArrayBlockingQueue(queueCapacity)
        timestampOriginNanos = nanoTime()
    }

    /**
     * Accepts one complete interleaved PCM16LE upload from the authenticated browser endpoint.
     *
     * Valid uploads refresh producer availability even while AA is not requesting microphone
     * audio. Audio is converted and queued only during a successful [start]/[stop] interval.
     */
    fun acceptPcm16LittleEndian(inputFormat: Pcm16Format, bytes: ByteArray): Boolean {
        if (!isValidUpload(inputFormat, bytes)) return false
        val samples = decodePcm16LittleEndian(bytes)

        synchronized(lock) {
            if (closed) return false
            val receivedAtNanos = nanoTime()
            lastUploadClockNanos = receivedAtNanos
            latestInputFormat = inputFormat
            if (!started) return true

            val activeProcessor = processor
                ?.takeIf { it.inputFormat == inputFormat }
                ?: newProcessor(inputFormat).also { processor = it }
            val output = activeProcessor.process(samples)
            if (output.isNotEmpty()) {
                enqueue(output, receiveTimestampNanos(receivedAtNanos))
            }
            return true
        }
    }

    override fun isAvailable(): Boolean = synchronized(lock) {
        !closed && hasRecentUpload(nanoTime())
    }

    /** True only while Android Auto has an active AV-input request. */
    fun isCaptureRequested(): Boolean = synchronized(lock) {
        started && !closed
    }

    override fun start(): Boolean = synchronized(lock) {
        if (closed) return false
        if (started) return true
        val inputFormat = latestInputFormat ?: return false
        if (!hasRecentUpload(nanoTime())) return false

        queue.clear()
        processor = newProcessor(inputFormat)
        started = true
        true
    }

    override fun poll(timeoutMillis: Long): OpenAutoMicrophoneFrame? {
        require(timeoutMillis in 0L..MAX_POLL_MILLIS) { "invalid microphone poll timeout" }
        synchronized(lock) {
            if (!started || closed) return null
        }
        val frame = queue.poll(timeoutMillis, TimeUnit.MILLISECONDS) ?: return null
        return synchronized(lock) {
            if (!started || closed) null else frame
        }
    }

    override fun stop() {
        synchronized(lock) {
            if (closed) return
            started = false
            processor = null
            queue.clear()
        }
    }

    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            started = false
            processor = null
            latestInputFormat = null
            lastUploadClockNanos = null
            queue.clear()
        }
    }

    private fun hasRecentUpload(nowNanos: Long): Boolean {
        val lastUpload = lastUploadClockNanos ?: return false
        val age = nowNanos - lastUpload
        return age in 0L..availabilityWindowNanos
    }

    private fun newProcessor(inputFormat: Pcm16Format): StreamingPcm16Processor =
        StreamingPcm16Processor(AudioStreamAccessTier.FREE, inputFormat).also {
            check(it.outputFormat == OUTPUT_FORMAT) { "microphone DSP produced an invalid format" }
        }

    private fun enqueue(samples: ShortArray, receivedAtNanos: Long) {
        var sourceOffset = 0
        while (sourceOffset < samples.size) {
            val sampleCount = minOf(MAX_SAMPLES_PER_FRAME, samples.size - sourceOffset)
            val bytes = encodePcm16LittleEndian(samples, sourceOffset, sampleCount)
            val frame = OpenAutoMicrophoneFrame(
                capturedAtNanos = receivedAtNanos,
                bytes = bytes,
            )
            if (!queue.offer(frame)) {
                queue.poll()
                queue.offer(frame)
            }
            sourceOffset += sampleCount
        }
    }

    /** Normalizes the arbitrary System.nanoTime origin while preserving receive ordering. */
    private fun receiveTimestampNanos(receivedAtNanos: Long): Long {
        val elapsed = receivedAtNanos - timestampOriginNanos
        val candidate = if (elapsed >= 0L) elapsed else 0L
        val monotonic = maxOf(candidate, lastCaptureTimestampNanos ?: 0L)
        lastCaptureTimestampNanos = monotonic
        return monotonic
    }

    private fun isValidUpload(inputFormat: Pcm16Format, bytes: ByteArray): Boolean {
        val frameBytes = inputFormat.channelCount * PCM16_BYTES_PER_SAMPLE
        return inputFormat.sampleRateHz in Pcm16Format.MIN_SAMPLE_RATE_HZ..Pcm16Format.MAX_SAMPLE_RATE_HZ &&
            inputFormat.channelCount in 1..Pcm16Format.MAX_CHANNEL_COUNT &&
            bytes.isNotEmpty() &&
            bytes.size <= MAX_UPLOAD_BYTES &&
            bytes.size % frameBytes == 0
    }

    private fun decodePcm16LittleEndian(bytes: ByteArray): ShortArray {
        val samples = ShortArray(bytes.size / PCM16_BYTES_PER_SAMPLE)
        var sourceOffset = 0
        for (index in samples.indices) {
            val low = bytes[sourceOffset].toInt() and 0xFF
            val high = bytes[sourceOffset + 1].toInt()
            samples[index] = ((high shl 8) or low).toShort()
            sourceOffset += PCM16_BYTES_PER_SAMPLE
        }
        return samples
    }

    private fun encodePcm16LittleEndian(
        samples: ShortArray,
        sourceOffset: Int,
        sampleCount: Int,
    ): ByteArray = ByteArray(sampleCount * PCM16_BYTES_PER_SAMPLE).also { bytes ->
        repeat(sampleCount) { relativeIndex ->
            val sample = samples[sourceOffset + relativeIndex].toInt()
            val destination = relativeIndex * PCM16_BYTES_PER_SAMPLE
            bytes[destination] = sample.toByte()
            bytes[destination + 1] = (sample shr 8).toByte()
        }
    }

    companion object {
        // Browser readiness is refreshed every five seconds. A wider lease
        // tolerates renderer scheduling pauses without accepting a stale page
        // indefinitely when the browser has actually gone away.
        const val DEFAULT_AVAILABILITY_WINDOW_NANOS = 15_000_000_000L
        const val DEFAULT_QUEUE_CAPACITY = 12
        const val MAX_UPLOAD_BYTES = 256 * 1024
        private const val MAX_POLL_MILLIS = 10_000L
        private const val OUTPUT_SAMPLE_RATE_HZ = 16_000
        private const val PCM16_BYTES_PER_SAMPLE = 2
        private const val MAX_SAMPLES_PER_FRAME =
            OpenAutoMicrophoneFrame.MAX_PCM_BYTES / PCM16_BYTES_PER_SAMPLE
        private val OUTPUT_FORMAT = Pcm16Format(OUTPUT_SAMPLE_RATE_HZ, 1)
    }
}
