/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.pebble.tecomheadunit.openauto.protocol

import com.pebble.tecomheadunit.openauto.OpenAutoAudioChannel
import com.pebble.tecomheadunit.openauto.OpenAutoPcmFrame

/** Decodes the pinned AASDK AV media envelope into owned little-endian PCM. */
internal object AasdkAudioPacketCodec {
    private const val AV_MEDIA_WITH_TIMESTAMP = 0x0000
    private const val AV_MEDIA_UNTIMESTAMPED = 0x0001
    private const val MESSAGE_ID_SIZE = 2
    private const val TIMESTAMP_SIZE = 8

    fun decode(
        channel: OpenAutoAudioChannel,
        applicationPayload: ByteArray,
    ): OpenAutoPcmFrame = when (AasdkOpenAutoProtocol.messageId(applicationPayload)) {
        AV_MEDIA_WITH_TIMESTAMP -> decodeTimestamped(channel, applicationPayload)
        AV_MEDIA_UNTIMESTAMPED -> decodeUntimestamped(channel, applicationPayload)
        else -> throw AasdkProtocolException("not an AV audio media message")
    }

    private fun decodeTimestamped(
        channel: OpenAutoAudioChannel,
        payload: ByteArray,
    ): OpenAutoPcmFrame {
        val dataOffset = MESSAGE_ID_SIZE + TIMESTAMP_SIZE
        if (payload.size <= dataOffset) {
            throw AasdkProtocolException("timestamped audio packet has no PCM data")
        }
        return frame(
            channel = channel,
            rawTimestamp = readPositiveUInt64(payload, MESSAGE_ID_SIZE),
            payload = payload,
            dataOffset = dataOffset,
        )
    }

    private fun decodeUntimestamped(
        channel: OpenAutoAudioChannel,
        payload: ByteArray,
    ): OpenAutoPcmFrame {
        if (payload.size <= MESSAGE_ID_SIZE) {
            throw AasdkProtocolException("untimestamped audio packet has no PCM data")
        }
        return frame(
            channel = channel,
            rawTimestamp = null,
            payload = payload,
            dataOffset = MESSAGE_ID_SIZE,
        )
    }

    private fun frame(
        channel: OpenAutoAudioChannel,
        rawTimestamp: Long?,
        payload: ByteArray,
        dataOffset: Int,
    ): OpenAutoPcmFrame {
        val pcm = payload.copyOfRange(dataOffset, payload.size)
        if (pcm.size % channel.format.bytesPerFrame != 0) {
            throw AasdkProtocolException("audio packet is not PCM sample-frame aligned")
        }
        return OpenAutoPcmFrame(
            channel = channel,
            format = channel.format,
            rawTimestamp = rawTimestamp,
            bytes = pcm,
        )
    }

    private fun readPositiveUInt64(data: ByteArray, offset: Int): Long {
        if (offset < 0 || offset > data.size - TIMESTAMP_SIZE) {
            throw AasdkProtocolException("truncated audio timestamp")
        }
        if (data[offset].toInt() and 0x80 != 0) {
            throw AasdkProtocolException("audio timestamp exceeds callback range")
        }
        var value = 0L
        repeat(TIMESTAMP_SIZE) { index ->
            value = (value shl 8) or (data[offset + index].toLong() and 0xFF)
        }
        return value
    }
}
