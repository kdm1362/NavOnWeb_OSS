/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.eigenkodex.navonweb.browser.webrtc

import com.eigenkodex.navonweb.audio.Pcm16Format
import com.eigenkodex.navonweb.browser.audio.BrowserAudioTrack
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class WebRtcAudioFrameCodecTest {
    @Test
    fun `maps all browser-created versioned audio channel labels`() {
        assertEquals(
            "navonweb-audio-media-v1",
            WebRtcAudioDataChannels.label(BrowserAudioTrack.MEDIA),
        )
        assertEquals(
            "navonweb-audio-speech-v1",
            WebRtcAudioDataChannels.label(BrowserAudioTrack.SPEECH),
        )
        assertEquals(
            "navonweb-audio-system-v1",
            WebRtcAudioDataChannels.label(BrowserAudioTrack.SYSTEM),
        )
        BrowserAudioTrack.entries.forEach { track ->
            assertEquals(track, WebRtcAudioDataChannels.track(WebRtcAudioDataChannels.label(track)))
        }
        assertEquals(null, WebRtcAudioDataChannels.track("navonweb-audio-media-v2"))
    }

    @Test
    fun `encodes NWA1 header in big endian while preserving little endian PCM`() {
        val pcm = byteArrayOf(0x34, 0x12, 0xCC.toByte(), 0xFF.toByte())

        val encoded = WebRtcAudioFrameCodec.encodeChunk(Pcm16Format(48_000, 2), pcm).single()

        assertArrayEquals(
            byteArrayOf(
                'N'.code.toByte(),
                'W'.code.toByte(),
                'A'.code.toByte(),
                '1'.code.toByte(),
                0,
                2,
                0,
                0,
                0,
                0,
                0xBB.toByte(),
                0x80.toByte(),
            ),
            encoded.copyOfRange(0, WebRtcAudioFrameCodec.HEADER_BYTES),
        )
        assertArrayEquals(pcm, encoded.copyOfRange(WebRtcAudioFrameCodec.HEADER_BYTES, encoded.size))
    }

    @Test
    fun `splits large stereo chunks on sample-frame boundaries`() {
        val format = Pcm16Format(48_000, 2)
        val pcm = ByteArray(WebRtcAudioFrameCodec.MAX_PCM_BYTES + 16) { it.toByte() }

        val encoded = WebRtcAudioFrameCodec.encodeChunk(format, pcm)

        assertEquals(2, encoded.size)
        assertEquals(
            WebRtcAudioFrameCodec.MAX_FRAME_BYTES,
            encoded.first().size,
        )
        assertEquals(WebRtcAudioFrameCodec.HEADER_BYTES + 16, encoded.last().size)
        val joinedPcm = encoded.flatMap { frame ->
            frame.copyOfRange(WebRtcAudioFrameCodec.HEADER_BYTES, frame.size).asIterable()
        }.toByteArray()
        assertArrayEquals(pcm, joinedPcm)
    }

    @Test
    fun `rejects empty and sample-frame-unaligned PCM`() {
        assertThrows(IllegalArgumentException::class.java) {
            WebRtcAudioFrameCodec.encodeChunk(Pcm16Format(16_000, 1), ByteArray(0))
        }
        assertThrows(IllegalArgumentException::class.java) {
            WebRtcAudioFrameCodec.encodeChunk(Pcm16Format(48_000, 2), ByteArray(2))
        }
    }

    @Test
    fun `backpressure policy bounds the SCTP buffered amount`() {
        val encodedBytes = WebRtcAudioFrameCodec.HEADER_BYTES + 3_200
        val lastAllowedBufferedAmount =
            WebRtcAudioBackpressurePolicy.MAX_BUFFERED_AMOUNT_BYTES - encodedBytes

        assertTrue(WebRtcAudioBackpressurePolicy.canSend(lastAllowedBufferedAmount, encodedBytes))
        assertFalse(WebRtcAudioBackpressurePolicy.canSend(lastAllowedBufferedAmount + 1, encodedBytes))
        assertFalse(WebRtcAudioBackpressurePolicy.canSend(-1, encodedBytes))
        assertFalse(
            WebRtcAudioBackpressurePolicy.canSend(
                0,
                WebRtcAudioFrameCodec.MAX_FRAME_BYTES + 1,
            ),
        )
    }
}
