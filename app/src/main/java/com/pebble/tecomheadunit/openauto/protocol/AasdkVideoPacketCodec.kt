/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.pebble.tecomheadunit.openauto.protocol

internal data class AasdkVideoPacket(
    val rawTimestamp: Long?,
    val bytes: ByteArray,
    val dataOffset: Int,
    val dataLength: Int,
    val codecConfigOnly: Boolean,
)

/** Extracts the AV message id/timestamp envelope before H.264 decoding. */
internal object AasdkVideoPacketCodec {
    private const val AV_MEDIA_WITH_TIMESTAMP = 0x0000
    private const val AV_MEDIA_UNTIMESTAMPED = 0x0001
    private const val MESSAGE_ID_SIZE = 2
    private const val TIMESTAMP_SIZE = 8

    fun decode(applicationPayload: ByteArray): AasdkVideoPacket {
        return when (AasdkOpenAutoProtocol.messageId(applicationPayload)) {
            AV_MEDIA_WITH_TIMESTAMP -> decodeTimestamped(applicationPayload)
            AV_MEDIA_UNTIMESTAMPED -> decodeUntimestamped(applicationPayload)
            else -> throw AasdkProtocolException("not an AV video media message")
        }
    }

    private fun decodeTimestamped(payload: ByteArray): AasdkVideoPacket {
        val dataOffset = MESSAGE_ID_SIZE + TIMESTAMP_SIZE
        if (payload.size <= dataOffset) {
            throw AasdkProtocolException("timestamped video packet has no H.264 data")
        }
        return AasdkVideoPacket(
            rawTimestamp = readPositiveUInt64(payload, MESSAGE_ID_SIZE),
            bytes = payload,
            dataOffset = dataOffset,
            dataLength = payload.size - dataOffset,
            codecConfigOnly = containsOnlyCodecConfigNalUnits(payload, dataOffset, payload.size),
        )
    }

    private fun decodeUntimestamped(payload: ByteArray): AasdkVideoPacket {
        if (payload.size <= MESSAGE_ID_SIZE) {
            throw AasdkProtocolException("video codec config is empty")
        }
        return AasdkVideoPacket(
            rawTimestamp = null,
            bytes = payload,
            dataOffset = MESSAGE_ID_SIZE,
            dataLength = payload.size - MESSAGE_ID_SIZE,
            codecConfigOnly = containsOnlyCodecConfigNalUnits(payload, MESSAGE_ID_SIZE, payload.size),
        )
    }

    /**
     * Message id 0x0001 means untimestamped media, not necessarily codec data.
     * Only Annex-B access units containing SPS/PPS and no VCL NAL are marked as
     * MediaCodec codec-config buffers. Mixed SPS/PPS + IDR remains normal media.
     */
    private fun containsOnlyCodecConfigNalUnits(data: ByteArray, start: Int, end: Int): Boolean {
        var cursor = start
        var hasParameterSet = false
        var hasVcl = false
        while (true) {
            val nalHeader = findNalHeader(data, cursor, end) ?: break
            val nalType = data[nalHeader].toInt() and 0x1F
            if (nalType == NAL_SPS || nalType == NAL_PPS) hasParameterSet = true
            if (nalType in NAL_VCL_MIN..NAL_VCL_MAX) hasVcl = true
            cursor = nalHeader + 1
        }
        return hasParameterSet && !hasVcl
    }

    private fun findNalHeader(data: ByteArray, start: Int, end: Int): Int? {
        var index = start
        while (index + 3 < end) {
            if (data[index] == 0.toByte() && data[index + 1] == 0.toByte()) {
                if (data[index + 2] == 1.toByte()) return index + 3
                if (index + 4 < end && data[index + 2] == 0.toByte() && data[index + 3] == 1.toByte()) {
                    return index + 4
                }
            }
            index += 1
        }
        return null
    }

    private fun readPositiveUInt64(data: ByteArray, offset: Int): Long {
        if (offset < 0 || offset > data.size - TIMESTAMP_SIZE) {
            throw AasdkProtocolException("truncated video timestamp")
        }
        // MediaCodec uses signed presentation timestamps. AASDK timestamps are
        // uint64, but real monotonic microsecond values must fit in Long.
        if (data[offset].toInt() and 0x80 != 0) {
            throw AasdkProtocolException("video timestamp exceeds decoder range")
        }
        var value = 0L
        repeat(TIMESTAMP_SIZE) { index ->
            value = (value shl 8) or (data[offset + index].toLong() and 0xFF)
        }
        return value
    }

    private const val NAL_VCL_MIN = 1
    private const val NAL_VCL_MAX = 5
    private const val NAL_SPS = 7
    private const val NAL_PPS = 8
}
