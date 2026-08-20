/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * Frame layout derived from f1xpl/aasdk at
 * 046b3b381595509d0939fa84b14a90978f46ff63.
 */
package com.eigenkodex.navonweb.openauto.protocol

internal enum class AasdkFrameType(val bits: Int) {
    MIDDLE(0),
    FIRST(1),
    LAST(2),
    BULK(3),
    ;

    companion object {
        fun fromBits(bits: Int): AasdkFrameType = entries.firstOrNull { it.bits == bits }
            ?: throw AasdkProtocolException("invalid frame type")
    }
}

internal data class AasdkFrame(
    val channelId: Int,
    val type: AasdkFrameType,
    val encrypted: Boolean,
    val controlMessage: Boolean,
    val payload: ByteArray,
    val declaredTotalSize: Int?,
)

internal data class AasdkPlainMessage(
    val channelId: Int,
    val controlMessage: Boolean,
    val payload: ByteArray,
)

internal class AasdkProtocolException(message: String) : IllegalArgumentException(message)

internal object AasdkFrameCodec {
    const val CONTROL_CHANNEL = 0

    // Upstream splits plaintext at 0x4000 before applying TLS to each fragment.
    const val MAX_PLAINTEXT_FRAME_PAYLOAD = 0x4000

    // The on-wire length field is unsigned 16-bit. Encrypted payloads can be
    // larger than the plaintext fragment because of TLS record overhead.
    const val MAX_WIRE_FRAME_PAYLOAD = 0xFFFF

    // Local defensive bound; it is not an AASDK protocol constant.
    const val MAX_MESSAGE_SIZE = 16 * 1024 * 1024

    private const val HEADER_SIZE = 2
    private const val SHORT_SIZE_FIELD = 2
    private const val EXTENDED_SIZE_FIELD = 6
    private const val FRAME_TYPE_MASK = 0x03
    private const val CONTROL_MESSAGE_FLAG = 0x04
    private const val ENCRYPTED_FLAG = 0x08

    /**
     * Splits an unencrypted message into AASDK frames.
     *
     * Exactly 0x4000 bytes is intentionally emitted as one BULK frame. The
     * pinned upstream encoder emits FIRST plus an empty LAST at that boundary;
     * the single-frame form avoids the empty fragment and is accepted by the
     * corresponding decoder.
     */
    fun encodePlainMessage(
        channelId: Int,
        controlMessage: Boolean,
        payload: ByteArray,
    ): List<ByteArray> {
        requireChannel(channelId)
        if (payload.size > MAX_MESSAGE_SIZE) {
            throw AasdkProtocolException("message exceeds limit")
        }
        if (payload.size <= MAX_PLAINTEXT_FRAME_PAYLOAD) {
            return listOf(
                encodeFrame(
                    channelId = channelId,
                    type = AasdkFrameType.BULK,
                    encrypted = false,
                    controlMessage = controlMessage,
                    payload = payload,
                    declaredTotalSize = null,
                ),
            )
        }

        val frames = ArrayList<ByteArray>((payload.size / MAX_PLAINTEXT_FRAME_PAYLOAD) + 1)
        var offset = 0
        while (offset < payload.size) {
            val end = minOf(offset + MAX_PLAINTEXT_FRAME_PAYLOAD, payload.size)
            val type = when {
                offset == 0 -> AasdkFrameType.FIRST
                end == payload.size -> AasdkFrameType.LAST
                else -> AasdkFrameType.MIDDLE
            }
            frames += encodeFrame(
                channelId = channelId,
                type = type,
                encrypted = false,
                controlMessage = controlMessage,
                payload = payload.copyOfRange(offset, end),
                declaredTotalSize = if (type == AasdkFrameType.FIRST) payload.size else null,
            )
            offset = end
        }
        return frames
    }

    /**
     * Encodes one ciphertext fragment already produced by the TLS layer.
     * Callers must split the plaintext first, wrap each fragment separately,
     * then call this method once per ciphertext fragment.
     */
    fun encodeEncryptedFrame(
        channelId: Int,
        type: AasdkFrameType,
        controlMessage: Boolean,
        ciphertextPayload: ByteArray,
        declaredPlaintextTotalSize: Int? = null,
    ): ByteArray = encodeFrame(
        channelId = channelId,
        type = type,
        encrypted = true,
        controlMessage = controlMessage,
        payload = ciphertextPayload,
        declaredTotalSize = declaredPlaintextTotalSize,
    )

    fun decodeFrame(data: ByteArray): AasdkFrame {
        if (data.size < HEADER_SIZE + SHORT_SIZE_FIELD) {
            throw AasdkProtocolException("truncated frame")
        }
        val channelId = data[0].toInt() and 0xFF
        val flags = data[1].toInt() and 0xFF
        val type = AasdkFrameType.fromBits(flags and FRAME_TYPE_MASK)
        val sizeField = if (type == AasdkFrameType.FIRST) EXTENDED_SIZE_FIELD else SHORT_SIZE_FIELD
        if (data.size < HEADER_SIZE + sizeField) {
            throw AasdkProtocolException("truncated frame size")
        }
        val payloadSize = readUInt16(data, HEADER_SIZE)
        val declaredTotalSize = if (type == AasdkFrameType.FIRST) {
            readUInt32AsInt(data, HEADER_SIZE + SHORT_SIZE_FIELD)
        } else {
            null
        }
        if ((declaredTotalSize ?: 0) > MAX_MESSAGE_SIZE) {
            throw AasdkProtocolException("declared message size exceeds limit")
        }
        val payloadOffset = HEADER_SIZE + sizeField
        if (data.size != payloadOffset + payloadSize) {
            throw AasdkProtocolException("frame length mismatch")
        }
        return AasdkFrame(
            channelId = channelId,
            type = type,
            encrypted = flags and ENCRYPTED_FLAG != 0,
            controlMessage = flags and CONTROL_MESSAGE_FLAG != 0,
            payload = data.copyOfRange(payloadOffset, data.size),
            declaredTotalSize = declaredTotalSize,
        )
    }

    private fun encodeFrame(
        channelId: Int,
        type: AasdkFrameType,
        encrypted: Boolean,
        controlMessage: Boolean,
        payload: ByteArray,
        declaredTotalSize: Int?,
    ): ByteArray {
        requireChannel(channelId)
        if (payload.size > MAX_WIRE_FRAME_PAYLOAD) {
            throw AasdkProtocolException("frame payload exceeds wire limit")
        }
        if (type == AasdkFrameType.FIRST && declaredTotalSize == null) {
            throw AasdkProtocolException("first frame requires total size")
        }
        if (type != AasdkFrameType.FIRST && declaredTotalSize != null) {
            throw AasdkProtocolException("only first frame has total size")
        }
        if (declaredTotalSize != null && declaredTotalSize !in 0..MAX_MESSAGE_SIZE) {
            throw AasdkProtocolException("invalid total message size")
        }

        val sizeField = if (type == AasdkFrameType.FIRST) EXTENDED_SIZE_FIELD else SHORT_SIZE_FIELD
        val output = ByteArray(HEADER_SIZE + sizeField + payload.size)
        output[0] = channelId.toByte()
        output[1] = (
            type.bits or
                (if (controlMessage) CONTROL_MESSAGE_FLAG else 0) or
                (if (encrypted) ENCRYPTED_FLAG else 0)
            ).toByte()
        writeUInt16(output, HEADER_SIZE, payload.size)
        if (declaredTotalSize != null) {
            writeUInt32(output, HEADER_SIZE + SHORT_SIZE_FIELD, declaredTotalSize)
        }
        payload.copyInto(output, HEADER_SIZE + sizeField)
        return output
    }

    private fun requireChannel(channelId: Int) {
        if (channelId !in 0..255) throw AasdkProtocolException("invalid channel")
    }

    private fun readUInt16(data: ByteArray, offset: Int): Int =
        ((data[offset].toInt() and 0xFF) shl 8) or
            (data[offset + 1].toInt() and 0xFF)

    private fun readUInt32AsInt(data: ByteArray, offset: Int): Int {
        val value =
            ((data[offset].toLong() and 0xFF) shl 24) or
                ((data[offset + 1].toLong() and 0xFF) shl 16) or
                ((data[offset + 2].toLong() and 0xFF) shl 8) or
                (data[offset + 3].toLong() and 0xFF)
        if (value > Int.MAX_VALUE) throw AasdkProtocolException("message size overflows")
        return value.toInt()
    }

    private fun writeUInt16(output: ByteArray, offset: Int, value: Int) {
        output[offset] = (value ushr 8).toByte()
        output[offset + 1] = value.toByte()
    }

    private fun writeUInt32(output: ByteArray, offset: Int, value: Int) {
        output[offset] = (value ushr 24).toByte()
        output[offset + 1] = (value ushr 16).toByte()
        output[offset + 2] = (value ushr 8).toByte()
        output[offset + 3] = value.toByte()
    }
}

/** Reassembles plaintext fragments after any required TLS unwrap step. */
internal class AasdkPlainMessageAssembler {
    private var channelId: Int? = null
    private var controlMessage: Boolean = false
    private var expectedSize: Int = 0

    // Fragment bytes accumulate into a geometrically grown scratch so a large fragmented video
    // message costs amortized O(n) copies instead of one full reallocation per fragment. The
    // scratch never grows past the declared size, and a lying FIRST frame still only costs the
    // bytes actually received, matching the previous incremental-growth defensive posture.
    private var accumulated = ByteArray(0)
    private var accumulatedLength = 0

    fun accept(frame: AasdkFrame): AasdkPlainMessage? {
        try {
            if (frame.encrypted) {
                throw AasdkProtocolException("encrypted frame must be unwrapped before reassembly")
            }
            return when (frame.type) {
                AasdkFrameType.BULK -> {
                    requireIdle()
                    if (frame.payload.size > AasdkFrameCodec.MAX_MESSAGE_SIZE) {
                        throw AasdkProtocolException("message exceeds limit")
                    }
                    AasdkPlainMessage(frame.channelId, frame.controlMessage, frame.payload.copyOf())
                }

                AasdkFrameType.FIRST -> {
                    requireIdle()
                    val declared = frame.declaredTotalSize
                        ?: throw AasdkProtocolException("first frame has no total size")
                    if (declared <= frame.payload.size || declared > AasdkFrameCodec.MAX_MESSAGE_SIZE) {
                        throw AasdkProtocolException("invalid fragmented message size")
                    }
                    channelId = frame.channelId
                    controlMessage = frame.controlMessage
                    expectedSize = declared
                    accumulated = frame.payload.copyOf()
                    accumulatedLength = frame.payload.size
                    null
                }

                AasdkFrameType.MIDDLE -> {
                    requireContinuation(frame)
                    if (accumulatedLength + frame.payload.size >= expectedSize) {
                        throw AasdkProtocolException("middle frame reaches declared message size")
                    }
                    append(frame.payload)
                    null
                }

                AasdkFrameType.LAST -> {
                    requireContinuation(frame)
                    if (accumulatedLength + frame.payload.size != expectedSize) {
                        throw AasdkProtocolException("fragmented message size mismatch")
                    }
                    val complete = ByteArray(expectedSize)
                    accumulated.copyInto(complete, endIndex = accumulatedLength)
                    frame.payload.copyInto(complete, accumulatedLength)
                    val message = AasdkPlainMessage(frame.channelId, frame.controlMessage, complete)
                    reset()
                    message
                }
            }
        } catch (error: RuntimeException) {
            reset()
            throw error
        }
    }

    fun reset() {
        accumulated.fill(0)
        channelId = null
        controlMessage = false
        expectedSize = 0
        accumulated = ByteArray(0)
        accumulatedLength = 0
    }

    private fun append(payload: ByteArray) {
        val required = accumulatedLength + payload.size
        if (required > accumulated.size) {
            // Doubling capped at the declared size keeps total copying linear in the message.
            var capacity = maxOf(accumulated.size, 1)
            while (capacity < required) capacity *= 2
            val replacement = ByteArray(minOf(capacity, expectedSize))
            accumulated.copyInto(replacement, endIndex = accumulatedLength)
            accumulated.fill(0)
            accumulated = replacement
        }
        payload.copyInto(accumulated, accumulatedLength)
        accumulatedLength = required
    }

    private fun requireIdle() {
        if (channelId != null) throw AasdkProtocolException("fragmented message already in progress")
    }

    private fun requireContinuation(frame: AasdkFrame) {
        val activeChannel = channelId
            ?: throw AasdkProtocolException("fragment continuation without first frame")
        if (frame.channelId != activeChannel || frame.controlMessage != controlMessage) {
            throw AasdkProtocolException("fragment metadata mismatch")
        }
    }
}
