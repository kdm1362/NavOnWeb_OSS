/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.eigenkodex.navonweb.browser.webrtc

import com.eigenkodex.navonweb.audio.Pcm16Format
import com.eigenkodex.navonweb.browser.audio.BrowserAudioTrack

/** Versioned labels for browser-created Android -> browser PCM data channels. */
internal object WebRtcAudioDataChannels {
    const val MEDIA_LABEL = "navonweb-audio-media-v1"
    const val SPEECH_LABEL = "navonweb-audio-speech-v1"
    const val SYSTEM_LABEL = "navonweb-audio-system-v1"

    fun label(track: BrowserAudioTrack): String = when (track) {
        BrowserAudioTrack.MEDIA -> MEDIA_LABEL
        BrowserAudioTrack.SPEECH -> SPEECH_LABEL
        BrowserAudioTrack.SYSTEM -> SYSTEM_LABEL
    }

    fun track(label: String?): BrowserAudioTrack? = when (label) {
        MEDIA_LABEL -> BrowserAudioTrack.MEDIA
        SPEECH_LABEL -> BrowserAudioTrack.SPEECH
        SYSTEM_LABEL -> BrowserAudioTrack.SYSTEM
        else -> null
    }
}

/** Encoder for the `NWA1` binary PCM16 wire format consumed by the cloud page. */
internal object WebRtcAudioFrameCodec {
    const val HEADER_BYTES = 12
    const val MAX_PCM_BYTES = 32 * 1024
    const val MAX_FRAME_BYTES = HEADER_BYTES + MAX_PCM_BYTES

    /**
     * Splits a hub chunk into bounded messages without splitting an interleaved PCM sample frame.
     * The PCM payload remains little-endian while all multi-byte header fields are big-endian.
     */
    fun encodeChunk(format: Pcm16Format, pcm16LittleEndian: ByteArray): List<ByteArray> {
        val pcmFrameBytes = format.channelCount * PCM16_BYTES_PER_SAMPLE
        require(pcm16LittleEndian.isNotEmpty()) { "PCM payload must not be empty" }
        require(pcm16LittleEndian.size % pcmFrameBytes == 0) { "PCM payload is not frame-aligned" }

        val maxAlignedPcmBytes = MAX_PCM_BYTES - (MAX_PCM_BYTES % pcmFrameBytes)
        return buildList {
            var offset = 0
            while (offset < pcm16LittleEndian.size) {
                val payloadBytes = minOf(maxAlignedPcmBytes, pcm16LittleEndian.size - offset)
                add(encodeFrame(format, pcm16LittleEndian, offset, payloadBytes))
                offset += payloadBytes
            }
        }
    }

    private fun encodeFrame(
        format: Pcm16Format,
        source: ByteArray,
        sourceOffset: Int,
        payloadBytes: Int,
    ): ByteArray = ByteArray(HEADER_BYTES + payloadBytes).also { output ->
        output[0] = MAGIC_N
        output[1] = MAGIC_W
        output[2] = MAGIC_A
        output[3] = MAGIC_VERSION_1
        output[FLAGS_OFFSET] = 0
        output[CHANNELS_OFFSET] = format.channelCount.toByte()
        output[RESERVED_0_OFFSET] = 0
        output[RESERVED_1_OFFSET] = 0
        val sampleRate = format.sampleRateHz
        output[SAMPLE_RATE_OFFSET] = (sampleRate ushr 24).toByte()
        output[SAMPLE_RATE_OFFSET + 1] = (sampleRate ushr 16).toByte()
        output[SAMPLE_RATE_OFFSET + 2] = (sampleRate ushr 8).toByte()
        output[SAMPLE_RATE_OFFSET + 3] = sampleRate.toByte()
        source.copyInto(
            destination = output,
            destinationOffset = HEADER_BYTES,
            startIndex = sourceOffset,
            endIndex = sourceOffset + payloadBytes,
        )
    }

    private const val FLAGS_OFFSET = 4
    private const val CHANNELS_OFFSET = 5
    private const val RESERVED_0_OFFSET = 6
    private const val RESERVED_1_OFFSET = 7
    private const val SAMPLE_RATE_OFFSET = 8
    private const val PCM16_BYTES_PER_SAMPLE = 2
    private const val MAGIC_N = 'N'.code.toByte()
    private const val MAGIC_W = 'W'.code.toByte()
    private const val MAGIC_A = 'A'.code.toByte()
    private const val MAGIC_VERSION_1 = '1'.code.toByte()
}

/** Caps libwebrtc's own queue in addition to the hub's bounded recent-audio queue. */
internal object WebRtcAudioBackpressurePolicy {
    // About 250 ms at the premium 48 kHz stereo PCM rate. Favor fresh navigation and speech
    // over playing stale audio after a temporarily slow browser.
    const val MAX_BUFFERED_AMOUNT_BYTES = 48L * 1024L

    fun canSend(bufferedAmountBytes: Long, encodedFrameBytes: Int): Boolean =
        bufferedAmountBytes >= 0L &&
            encodedFrameBytes in (WebRtcAudioFrameCodec.HEADER_BYTES + 2)..
            WebRtcAudioFrameCodec.MAX_FRAME_BYTES &&
            bufferedAmountBytes <= MAX_BUFFERED_AMOUNT_BYTES - encodedFrameBytes
}
