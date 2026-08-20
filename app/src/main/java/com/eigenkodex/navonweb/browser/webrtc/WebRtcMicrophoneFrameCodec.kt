/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.eigenkodex.navonweb.browser.webrtc

import com.eigenkodex.navonweb.audio.Pcm16Format

/** One bounded microphone frame received through the browser WebRTC data channel. */
internal data class WebRtcMicrophoneFrame(
    val format: Pcm16Format,
    val pcm16LittleEndian: ByteArray,
)

/** Strict decoder for the versioned `navonweb-microphone-v1` binary wire format. */
internal object WebRtcMicrophoneFrameCodec {
    const val DATA_CHANNEL_LABEL = "navonweb-microphone-v1"
    const val HEADER_BYTES = 12
    const val MAX_PCM_BYTES = 32 * 1024
    const val MAX_FRAME_BYTES = HEADER_BYTES + MAX_PCM_BYTES

    fun decode(frame: ByteArray): WebRtcMicrophoneFrame? {
        if (frame.size !in (HEADER_BYTES + MIN_PCM_BYTES)..MAX_FRAME_BYTES) return null
        if (
            frame[0] != MAGIC_N || frame[1] != MAGIC_W ||
            frame[2] != MAGIC_M || frame[3] != MAGIC_VERSION_1
        ) {
            return null
        }
        if (frame[FLAGS_OFFSET].toInt() != 0) return null

        val channelCount = frame[CHANNELS_OFFSET].toInt() and 0xFF
        if (channelCount !in 1..Pcm16Format.MAX_CHANNEL_COUNT) return null
        if (frame[RESERVED_0_OFFSET].toInt() != 0 || frame[RESERVED_1_OFFSET].toInt() != 0) {
            return null
        }

        val sampleRate = readUnsignedIntBigEndian(frame, SAMPLE_RATE_OFFSET)
        if (sampleRate !in Pcm16Format.MIN_SAMPLE_RATE_HZ.toLong()..
            Pcm16Format.MAX_SAMPLE_RATE_HZ.toLong()
        ) {
            return null
        }

        val payloadBytes = frame.size - HEADER_BYTES
        if (payloadBytes !in MIN_PCM_BYTES..MAX_PCM_BYTES || payloadBytes % PCM16_BYTES_PER_SAMPLE != 0) {
            return null
        }
        return WebRtcMicrophoneFrame(
            format = Pcm16Format(sampleRate.toInt(), channelCount),
            pcm16LittleEndian = frame.copyOfRange(HEADER_BYTES, frame.size),
        )
    }

    private fun readUnsignedIntBigEndian(bytes: ByteArray, offset: Int): Long =
        ((bytes[offset].toLong() and 0xFFL) shl 24) or
            ((bytes[offset + 1].toLong() and 0xFFL) shl 16) or
            ((bytes[offset + 2].toLong() and 0xFFL) shl 8) or
            (bytes[offset + 3].toLong() and 0xFFL)

    private const val FLAGS_OFFSET = 4
    private const val CHANNELS_OFFSET = 5
    private const val RESERVED_0_OFFSET = 6
    private const val RESERVED_1_OFFSET = 7
    private const val SAMPLE_RATE_OFFSET = 8
    private const val PCM16_BYTES_PER_SAMPLE = 2
    private const val MIN_PCM_BYTES = PCM16_BYTES_PER_SAMPLE
    private const val MAGIC_N = 'N'.code.toByte()
    private const val MAGIC_W = 'W'.code.toByte()
    private const val MAGIC_M = 'M'.code.toByte()
    private const val MAGIC_VERSION_1 = '1'.code.toByte()
}
