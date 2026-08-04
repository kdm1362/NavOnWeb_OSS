/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.pebble.tecomheadunit.browser.audio

import com.pebble.tecomheadunit.audio.Pcm16Format
import com.pebble.tecomheadunit.openauto.OpenAutoMicrophoneFrame
import com.pebble.tecomheadunit.openauto.OpenAutoPcmFormat
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserMicrophonePcmSourceTest {
    @Test
    fun convertsBrowserStereoToOpenAutoMonoSixteenKilohertz() {
        var now = 10L
        val source = BrowserMicrophonePcmSource(nanoTime = { now })
        val inputFormat = Pcm16Format(sampleRateHz = 48_000, channelCount = 2)

        assertEquals(OpenAutoPcmFormat(16_000, 1), source.format)
        assertTrue(source.acceptPcm16LittleEndian(inputFormat, pcm(0, 0)))
        assertTrue(source.isAvailable())
        assertTrue(source.start())

        now = 20L
        // Three 48 kHz stereo frames become one 16 kHz mono frame.
        assertTrue(
            source.acceptPcm16LittleEndian(
                inputFormat,
                pcm(1_000, 3_000, 3_000, 5_000, 5_000, 7_000),
            ),
        )

        val frame = checkNotNull(source.poll(0L))
        assertArrayEquals(pcm(2_000), frame.bytes)
        assertEquals(10L, frame.capturedAtNanos)
    }

    @Test
    fun acceptsSupportedRateBoundariesAndRejectsMalformedPcm() {
        val source = BrowserMicrophonePcmSource(nanoTime = { 0L })
        val mono8k = Pcm16Format(8_000, 1)
        val stereo192k = Pcm16Format(192_000, 2)

        assertTrue(source.acceptPcm16LittleEndian(mono8k, pcm(1)))
        assertTrue(source.acceptPcm16LittleEndian(stereo192k, pcm(1, -1)))
        assertFalse(source.acceptPcm16LittleEndian(mono8k, ByteArray(0)))
        assertFalse(source.acceptPcm16LittleEndian(mono8k, byteArrayOf(1)))
        assertFalse(source.acceptPcm16LittleEndian(stereo192k, pcm(1)))
        assertFalse(
            source.acceptPcm16LittleEndian(
                mono8k,
                ByteArray(BrowserMicrophonePcmSource.MAX_UPLOAD_BYTES + 2),
            ),
        )
    }

    @Test
    fun splitsUpsampledAudioIntoAlignedOpenAutoFrames() {
        val source = BrowserMicrophonePcmSource(queueCapacity = 8, nanoTime = { 0L })
        val format = Pcm16Format(8_000, 1)
        assertTrue(source.acceptPcm16LittleEndian(format, pcm(0)))
        assertTrue(source.start())

        val input = ShortArray(2_000) { it.toShort() }
        assertTrue(source.acceptPcm16LittleEndian(format, pcm(*input.map { it.toInt() }.toIntArray())))

        val frames = generateSequence { source.poll(0L) }.toList()
        assertTrue(frames.size > 1)
        assertTrue(frames.all { it.bytes.isNotEmpty() })
        assertTrue(frames.all { it.bytes.size <= OpenAutoMicrophoneFrame.MAX_PCM_BYTES })
        assertTrue(frames.all { it.bytes.size % source.format.bytesPerFrame == 0 })
    }

    @Test
    fun fullQueueDropsOldestAndKeepsRecentSpeech() {
        val source = BrowserMicrophonePcmSource(queueCapacity = 2, nanoTime = { 0L })
        val format = Pcm16Format(16_000, 1)
        assertTrue(source.acceptPcm16LittleEndian(format, pcm(0)))
        assertTrue(source.start())

        assertTrue(source.acceptPcm16LittleEndian(format, pcm(1)))
        assertTrue(source.acceptPcm16LittleEndian(format, pcm(2)))
        assertTrue(source.acceptPcm16LittleEndian(format, pcm(3)))

        assertArrayEquals(pcm(2), source.poll(0L)?.bytes)
        assertArrayEquals(pcm(3), source.poll(0L)?.bytes)
        assertNull(source.poll(0L))
    }

    @Test
    fun availabilityStartStopAndCloseHaveSeparateLifetimes() {
        var now = 1_000L
        val source = BrowserMicrophonePcmSource(
            availabilityWindowNanos = 100L,
            nanoTime = { now },
        )
        val format = Pcm16Format(16_000, 1)

        assertFalse(source.isAvailable())
        assertFalse(source.isCaptureRequested())
        assertFalse(source.start())
        assertTrue(source.acceptPcm16LittleEndian(format, pcm(7)))
        assertTrue(source.isAvailable())
        assertTrue(source.start())
        assertTrue(source.isCaptureRequested())
        assertTrue(source.acceptPcm16LittleEndian(format, pcm(8)))
        checkNotNull(source.poll(0L))

        source.stop()
        assertFalse(source.isCaptureRequested())
        assertNull(source.poll(0L))
        now += 101L
        assertFalse(source.isAvailable())
        assertFalse(source.start())

        assertTrue(source.acceptPcm16LittleEndian(format, pcm(9)))
        assertTrue(source.start())
        assertTrue(source.isCaptureRequested())
        source.close()
        assertFalse(source.isAvailable())
        assertFalse(source.isCaptureRequested())
        assertFalse(source.start())
        assertFalse(source.acceptPcm16LittleEndian(format, pcm(10)))
        assertNull(source.poll(0L))
    }

    @Test
    fun defaultReadyLeaseToleratesHeartbeatSchedulerJitter() {
        var now = 0L
        val source = BrowserMicrophonePcmSource(nanoTime = { now })
        val format = Pcm16Format(16_000, 1)

        assertTrue(source.acceptPcm16LittleEndian(format, pcm(0)))
        now = BrowserMicrophonePcmSource.DEFAULT_AVAILABILITY_WINDOW_NANOS
        assertTrue(source.isAvailable())
        assertTrue(source.start())

        source.stop()
        now += 1L
        assertFalse(source.isAvailable())
        assertFalse(source.start())
    }

    @Test
    fun receiveTimestampsNeverMoveBackwards() {
        var now = 1_000L
        val source = BrowserMicrophonePcmSource(nanoTime = { now })
        val format = Pcm16Format(16_000, 1)
        assertTrue(source.acceptPcm16LittleEndian(format, pcm(0)))
        assertTrue(source.start())

        now = 1_200L
        assertTrue(source.acceptPcm16LittleEndian(format, pcm(1)))
        val first = checkNotNull(source.poll(0L))
        now = 1_100L
        assertTrue(source.acceptPcm16LittleEndian(format, pcm(2)))
        val second = checkNotNull(source.poll(0L))

        assertTrue(first.capturedAtNanos >= 0L)
        assertTrue(second.capturedAtNanos >= first.capturedAtNanos)
    }

    private fun pcm(vararg samples: Int): ByteArray = ByteArray(samples.size * 2).also { output ->
        samples.forEachIndexed { index, sample ->
            val value = sample.toShort().toInt()
            output[index * 2] = value.toByte()
            output[index * 2 + 1] = (value shr 8).toByte()
        }
    }
}
