/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * Bootstrap values derived from f1xpl/aasdk at
 * 046b3b381595509d0939fa84b14a90978f46ff63.
 */
package com.eigenkodex.navonweb.openauto.protocol

internal data class AasdkVersionResponse(
    val major: Int,
    val minor: Int,
    val matches: Boolean,
)

internal object AasdkBootstrapCodec {
    const val MAJOR_VERSION = 1
    const val MINOR_VERSION = 1

    private const val VERSION_REQUEST = 0x0001
    private const val VERSION_RESPONSE = 0x0002
    private const val SSL_HANDSHAKE = 0x0003
    private const val AUTH_COMPLETE = 0x0004
    private const val VERSION_MATCH = 0x0000
    private const val VERSION_MISMATCH = 0xFFFF

    fun versionRequest(): ByteArray {
        val payload = ByteArray(6)
        writeUInt16(payload, 0, VERSION_REQUEST)
        writeUInt16(payload, 2, MAJOR_VERSION)
        writeUInt16(payload, 4, MINOR_VERSION)
        return AasdkFrameCodec.encodePlainMessage(
            channelId = AasdkFrameCodec.CONTROL_CHANNEL,
            controlMessage = false,
            payload = payload,
        ).single()
    }

    fun parseVersionResponse(frameBytes: ByteArray): AasdkVersionResponse {
        val frame = AasdkFrameCodec.decodeFrame(frameBytes)
        if (
            frame.channelId != AasdkFrameCodec.CONTROL_CHANNEL ||
            frame.type != AasdkFrameType.BULK ||
            frame.encrypted ||
            frame.controlMessage ||
            frame.payload.size != 8
        ) {
            throw AasdkProtocolException("invalid version response frame")
        }
        if (readUInt16(frame.payload, 0) != VERSION_RESPONSE) {
            throw AasdkProtocolException("unexpected control message")
        }
        val status = readUInt16(frame.payload, 6)
        if (status != VERSION_MATCH && status != VERSION_MISMATCH) {
            throw AasdkProtocolException("unknown version status")
        }
        return AasdkVersionResponse(
            major = readUInt16(frame.payload, 2),
            minor = readUInt16(frame.payload, 4),
            matches = status == VERSION_MATCH,
        )
    }

    /** Encodes one TLS handshake flight as a plain, control-channel specific message. */
    fun tlsHandshake(tlsBytes: ByteArray): List<ByteArray> {
        val payload = ByteArray(2 + tlsBytes.size)
        writeUInt16(payload, 0, SSL_HANDSHAKE)
        tlsBytes.copyInto(payload, 2)
        return AasdkFrameCodec.encodePlainMessage(
            channelId = AasdkFrameCodec.CONTROL_CHANNEL,
            controlMessage = false,
            payload = payload,
        )
    }

    fun parseTlsHandshake(message: AasdkPlainMessage): ByteArray {
        requirePlainControlSpecific(message)
        if (message.payload.size < 2 || readUInt16(message.payload, 0) != SSL_HANDSHAKE) {
            throw AasdkProtocolException("unexpected TLS control message")
        }
        return message.payload.copyOfRange(2, message.payload.size)
    }

    /**
     * AuthCompleteIndication status=OK. The protobuf payload is field 1,
     * varint zero (`08 00`), matching the pinned AASDK implementation.
     */
    fun authCompleteSuccess(): ByteArray {
        val payload = byteArrayOf(
            (AUTH_COMPLETE ushr 8).toByte(),
            AUTH_COMPLETE.toByte(),
            0x08,
            0x00,
        )
        return AasdkFrameCodec.encodePlainMessage(
            channelId = AasdkFrameCodec.CONTROL_CHANNEL,
            controlMessage = false,
            payload = payload,
        ).single()
    }

    private fun requirePlainControlSpecific(message: AasdkPlainMessage) {
        if (
            message.channelId != AasdkFrameCodec.CONTROL_CHANNEL ||
            message.controlMessage
        ) {
            throw AasdkProtocolException("invalid bootstrap control message")
        }
    }

    private fun readUInt16(data: ByteArray, offset: Int): Int =
        ((data[offset].toInt() and 0xFF) shl 8) or
            (data[offset + 1].toInt() and 0xFF)

    private fun writeUInt16(output: ByteArray, offset: Int, value: Int) {
        output[offset] = (value ushr 8).toByte()
        output[offset + 1] = value.toByte()
    }
}
