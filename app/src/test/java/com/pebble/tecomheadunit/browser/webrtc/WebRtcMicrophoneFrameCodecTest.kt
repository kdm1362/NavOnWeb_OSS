/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.pebble.tecomheadunit.browser.webrtc

import com.pebble.tecomheadunit.audio.Pcm16Format
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WebRtcMicrophoneFrameCodecTest {
    @Test
    fun `decodes bounded mono PCM with an unsigned big endian sample rate`() {
        val pcm = byteArrayOf(0x34, 0x12, 0xCC.toByte(), 0xFF.toByte())

        val decoded = checkNotNull(
            WebRtcMicrophoneFrameCodec.decode(frame(sampleRate = 48_000L, channels = 1, pcm = pcm)),
        )

        assertEquals(Pcm16Format(48_000, 1), decoded.format)
        assertArrayEquals(pcm, decoded.pcm16LittleEndian)
        assertEquals("navonweb-microphone-v1", WebRtcMicrophoneFrameCodec.DATA_CHANNEL_LABEL)
    }

    @Test
    fun `accepts the supported rate and payload boundaries`() {
        val minimum = WebRtcMicrophoneFrameCodec.decode(
            frame(sampleRate = 8_000L, channels = 1, pcm = byteArrayOf(0, 0)),
        )
        val maximumPcm = ByteArray(WebRtcMicrophoneFrameCodec.MAX_PCM_BYTES) { it.toByte() }
        val maximum = WebRtcMicrophoneFrameCodec.decode(
            frame(sampleRate = 192_000L, channels = 2, pcm = maximumPcm),
        )

        assertEquals(Pcm16Format(8_000, 1), checkNotNull(minimum).format)
        assertEquals(Pcm16Format(192_000, 2), checkNotNull(maximum).format)
        assertArrayEquals(maximumPcm, maximum.pcm16LittleEndian)
    }

    @Test
    fun `rejects invalid header fields`() {
        val valid = frame(sampleRate = 16_000L, channels = 1, pcm = byteArrayOf(0, 0))

        assertNull(WebRtcMicrophoneFrameCodec.decode(valid.copyOf().also { it[0] = 'X'.code.toByte() }))
        assertNull(WebRtcMicrophoneFrameCodec.decode(valid.copyOf().also { it[4] = 1 }))
        assertNull(WebRtcMicrophoneFrameCodec.decode(valid.copyOf().also { it[5] = 0 }))
        assertNull(WebRtcMicrophoneFrameCodec.decode(valid.copyOf().also { it[5] = 3 }))
        assertNull(WebRtcMicrophoneFrameCodec.decode(valid.copyOf().also { it[6] = 1 }))
        assertNull(WebRtcMicrophoneFrameCodec.decode(valid.copyOf().also { it[7] = 1 }))
    }

    @Test
    fun `rejects unsupported unsigned sample rates`() {
        assertNull(
            WebRtcMicrophoneFrameCodec.decode(
                frame(sampleRate = 7_999L, channels = 1, pcm = byteArrayOf(0, 0)),
            ),
        )
        assertNull(
            WebRtcMicrophoneFrameCodec.decode(
                frame(sampleRate = 192_001L, channels = 1, pcm = byteArrayOf(0, 0)),
            ),
        )
        assertNull(
            WebRtcMicrophoneFrameCodec.decode(
                frame(sampleRate = 0xFFFF_FFFFL, channels = 1, pcm = byteArrayOf(0, 0)),
            ),
        )
    }

    @Test
    fun `rejects missing odd and oversized PCM payloads`() {
        assertNull(WebRtcMicrophoneFrameCodec.decode(frame(16_000L, 1, ByteArray(0))))
        assertNull(WebRtcMicrophoneFrameCodec.decode(frame(16_000L, 1, byteArrayOf(0))))
        assertNull(
            WebRtcMicrophoneFrameCodec.decode(
                frame(16_000L, 1, ByteArray(WebRtcMicrophoneFrameCodec.MAX_PCM_BYTES + 2)),
            ),
        )
        assertTrue(WebRtcMicrophoneFrameCodec.HEADER_BYTES == 12)
    }

    @Test
    fun `queued frame is discarded after owner downgrade or deletion`() {
        var ownerIsCurrent = true
        var dispatched = 0
        val queuedFrame = {
            dispatchOwnedMicrophoneFrameIfCurrent(
                stillOwned = { ownerIsCurrent },
                dispatch = {
                    dispatched += 1
                    true
                },
            )
        }

        ownerIsCurrent = false

        assertFalse(queuedFrame())
        assertEquals(0, dispatched)
    }

    @Test
    fun `committed mutation prevents a queued frame from reaching the microphone source`() {
        val lock = Any()
        val ownerIsCurrent = AtomicBoolean(true)
        val dispatched = AtomicInteger(0)
        val mutationEntered = CountDownLatch(1)
        val releaseMutation = CountDownLatch(1)
        val frameFinished = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val mutation = executor.submit {
                synchronized(lock) {
                    ownerIsCurrent.set(false)
                    mutationEntered.countDown()
                    assertTrue(releaseMutation.await(2, TimeUnit.SECONDS))
                }
            }
            assertTrue(mutationEntered.await(2, TimeUnit.SECONDS))
            val frame = executor.submit<Boolean> {
                dispatchOwnedMicrophoneFrameWithAdmissionLock(
                    lock = lock,
                    stillOwned = ownerIsCurrent::get,
                    dispatch = {
                        dispatched.incrementAndGet()
                        true
                    },
                ).also { frameFinished.countDown() }
            }
            assertFalse(frameFinished.await(100, TimeUnit.MILLISECONDS))
            releaseMutation.countDown()
            mutation.get(2, TimeUnit.SECONDS)

            assertFalse(frame.get(2, TimeUnit.SECONDS))
            assertEquals(0, dispatched.get())
        } finally {
            releaseMutation.countDown()
            executor.shutdownNow()
        }
    }

    private fun frame(sampleRate: Long, channels: Int, pcm: ByteArray): ByteArray =
        ByteArray(WebRtcMicrophoneFrameCodec.HEADER_BYTES + pcm.size).also { output ->
            output[0] = 'N'.code.toByte()
            output[1] = 'W'.code.toByte()
            output[2] = 'M'.code.toByte()
            output[3] = '1'.code.toByte()
            output[4] = 0
            output[5] = channels.toByte()
            output[6] = 0
            output[7] = 0
            output[8] = (sampleRate ushr 24).toByte()
            output[9] = (sampleRate ushr 16).toByte()
            output[10] = (sampleRate ushr 8).toByte()
            output[11] = sampleRate.toByte()
            pcm.copyInto(output, destinationOffset = WebRtcMicrophoneFrameCodec.HEADER_BYTES)
        }
}
