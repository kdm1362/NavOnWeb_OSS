/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.pebble.tecomheadunit.openauto.protocol

import com.pebble.tecomheadunit.core.OpenAutoTouchEvent
import com.pebble.tecomheadunit.core.TouchPhase
import com.pebble.tecomheadunit.openauto.OpenAutoConfig
import com.pebble.tecomheadunit.openauto.ProjectionVideoProfile

internal enum class AasdkVoiceSessionStatus {
    START,
    END,
}

internal data class AasdkAvInputOpenRequest(
    val open: Boolean,
    val ancEnabled: Boolean,
    val echoCancellationEnabled: Boolean,
    val maxUnacked: Int,
)

internal data class AasdkAvMediaAck(
    val session: Int,
    val value: Long,
)

/**
 * Small, dependency-free subset of the protobuf messages emitted by OpenAuto.
 *
 * The bytes are pinned to:
 * - f1xpl/aasdk 046b3b381595509d0939fa84b14a90978f46ff63
 * - f1xpl/openauto aa90412bf93b5a5078495ea85ac9270c6297d369
 *
 * This intentionally does not pretend to be a general protobuf runtime. The
 * full OpenAuto default ServiceDiscoveryResponse is kept as an audited fixture
 * so a modern phone can be tested with Android MediaCodec video, browser audio
 * output, and the browser-backed AV-input microphone path.
 */
internal object AasdkOpenAutoProtocol {
    const val SERVICE_DISCOVERY_REQUEST = 0x0005
    const val SERVICE_DISCOVERY_RESPONSE = 0x0006
    const val CHANNEL_OPEN_REQUEST = 0x0007
    const val CHANNEL_OPEN_RESPONSE = 0x0008
    const val PING_REQUEST = 0x000B
    const val PING_RESPONSE = 0x000C
    const val SHUTDOWN_REQUEST = 0x000F
    const val VOICE_SESSION_REQUEST = 0x0011
    const val AUDIO_FOCUS_REQUEST = 0x0012
    const val AUDIO_FOCUS_RESPONSE = 0x0013

    const val AV_MEDIA_WITH_TIMESTAMP_INDICATION = 0x0000
    const val AV_MEDIA_INDICATION = 0x0001
    const val AV_CHANNEL_SETUP_REQUEST = 0x8000
    const val AV_CHANNEL_START_INDICATION = 0x8001
    const val SENSOR_START_REQUEST = 0x8001
    const val SENSOR_START_RESPONSE = 0x8002
    const val INPUT_EVENT_INDICATION = 0x8001
    const val BINDING_REQUEST = 0x8002
    const val BINDING_RESPONSE = 0x8003
    const val AV_CHANNEL_SETUP_RESPONSE = 0x8003
    const val SENSOR_EVENT_INDICATION = 0x8003
    const val AV_MEDIA_ACK_INDICATION = 0x8004
    const val AV_INPUT_OPEN_REQUEST = 0x8005
    const val AV_INPUT_OPEN_RESPONSE = 0x8006
    const val VIDEO_FOCUS_INDICATION = 0x8008

    const val CHANNEL_CONTROL = 0
    const val CHANNEL_INPUT = 1
    const val CHANNEL_SENSOR = 2
    const val CHANNEL_VIDEO = 3
    const val CHANNEL_MEDIA_AUDIO = 4
    const val CHANNEL_SPEECH_AUDIO = 5
    const val CHANNEL_SYSTEM_AUDIO = 6
    const val CHANNEL_AV_INPUT = 7

    const val SENSOR_TYPE_NIGHT_DATA = 10L
    const val SENSOR_TYPE_DRIVING_STATUS = 13L
    const val AUDIO_FOCUS_RELEASE = 4L
    const val AUDIO_FOCUS_STATE_GAIN = 1
    const val AUDIO_FOCUS_STATE_LOSS = 3

    private const val VOICE_SESSION_START = 1L
    private const val VOICE_SESSION_END = 2L

    /** Parses the status-only control notification sent when assistant capture starts or ends. */
    fun parseVoiceSessionStatus(applicationPayload: ByteArray): AasdkVoiceSessionStatus {
        val body = requireApplicationMessage(applicationPayload, VOICE_SESSION_REQUEST, "voice session")
        return when (readFirstVarintField(body, fieldNumber = 1)) {
            VOICE_SESSION_START -> AasdkVoiceSessionStatus.START
            VOICE_SESSION_END -> AasdkVoiceSessionStatus.END
            null -> throw AasdkProtocolException("voice session request has no status")
            else -> throw AasdkProtocolException("unsupported voice session status")
        }
    }

    /** Parses the pinned proto3 microphone open/close request on AV input channel 7. */
    fun parseAvInputOpenRequest(applicationPayload: ByteArray): AasdkAvInputOpenRequest {
        val body = requireApplicationMessage(applicationPayload, AV_INPUT_OPEN_REQUEST, "AV input open")
        val maxUnacked = readFirstVarintField(body, fieldNumber = 4) ?: 0L
        if (maxUnacked !in 0L..Int.MAX_VALUE.toLong()) {
            throw AasdkProtocolException("invalid AV input max_unacked")
        }
        return AasdkAvInputOpenRequest(
            open = readBooleanField(body, fieldNumber = 1, name = "open"),
            ancEnabled = readBooleanField(body, fieldNumber = 2, name = "anc"),
            echoCancellationEnabled = readBooleanField(body, fieldNumber = 3, name = "ec"),
            maxUnacked = maxUnacked.toInt(),
        )
    }

    /** Parses the proto2 ACK used to release one or more microphone send-window slots. */
    fun parseAvMediaAck(applicationPayload: ByteArray): AasdkAvMediaAck {
        val body = requireApplicationMessage(applicationPayload, AV_MEDIA_ACK_INDICATION, "AV media ACK")
        val session = readFirstVarintField(body, fieldNumber = 1)
            ?: throw AasdkProtocolException("AV media ACK has no session")
        if (session !in 0L..Int.MAX_VALUE.toLong()) {
            throw AasdkProtocolException("invalid AV media ACK session")
        }
        val value = readFirstVarintField(body, fieldNumber = 2)
            ?: throw AasdkProtocolException("AV media ACK has no value")
        if (value !in 0L..MAX_UINT32) {
            throw AasdkProtocolException("invalid AV media ACK value")
        }
        return AasdkAvMediaAck(session = session.toInt(), value = value)
    }

    /** Full seven-channel OpenAuto default response, including the message id. */
    fun serviceDiscoveryResponse(): ByteArray = envelope(
        SERVICE_DISCOVERY_RESPONSE,
        OPENAUTO_DEFAULT_SERVICE_DISCOVERY_PROTOBUF,
    )

    /**
     * Advertises exactly one of the audited landscape or portrait OpenAuto encoded profiles.
     * Optional total margins describe a centred, browser-shaped content viewport without changing
     * the selected encoded frame size. Unsupported dimensions, FPS or DPI fail closed.
     */
    fun serviceDiscoveryResponse(config: OpenAutoConfig): ByteArray {
        val resolution = when (
            config.viewport.width to config.viewport.height
        ) {
            800 to 480 -> VIDEO_RESOLUTION_480P
            1280 to 720 -> VIDEO_RESOLUTION_720P
            1920 to 1080 -> VIDEO_RESOLUTION_1080P
            720 to 1280 -> VIDEO_RESOLUTION_720X1280
            1080 to 1920 -> VIDEO_RESOLUTION_1080X1920
            else -> throw AasdkProtocolException("unsupported projection resolution")
        }
        val supportedDensity = ProjectionVideoProfile.isSupportedDensityDpi(config.dpi)
        if (config.fps != SAFE_VIDEO_FPS || !supportedDensity) {
            throw AasdkProtocolException("unsupported projection FPS or DPI")
        }
        val videoConfigParts = mutableListOf(
            encodeVarintField(fieldNumber = 1, value = resolution.toLong()),
            encodeVarintField(fieldNumber = 2, value = VIDEO_FPS_60_ENUM),
        )
        if (config.totalMarginWidth > 0) {
            videoConfigParts += encodeVarintField(
                fieldNumber = 3,
                value = config.totalMarginWidth.toLong(),
            )
        }
        if (config.totalMarginHeight > 0) {
            videoConfigParts += encodeVarintField(
                fieldNumber = 4,
                value = config.totalMarginHeight.toLong(),
            )
        }
        videoConfigParts += encodeVarintField(fieldNumber = 5, value = config.dpi.toLong())

        // Rebuild every enclosing length-delimited field. This is necessary because margins add
        // bytes to VideoConfig and may also make one or more parent lengths use a wider varint.
        val videoConfig = videoConfigParts.joinByteArrays()
        val avChannel = listOf(
            encodeVarintField(fieldNumber = 1, value = VIDEO_STREAM_TYPE),
            encodeLengthDelimitedField(fieldNumber = 4, value = videoConfig),
            encodeVarintField(fieldNumber = 5, value = 1L),
        ).joinByteArrays()
        val videoDescriptor = encodeLengthDelimitedField(
            fieldNumber = 1,
            value = listOf(
                encodeVarintField(fieldNumber = 1, value = CHANNEL_VIDEO.toLong()),
                encodeLengthDelimitedField(fieldNumber = 3, value = avChannel),
            ).joinByteArrays(),
        )
        val protobuf = OPENAUTO_DEFAULT_SERVICE_DISCOVERY_PROTOBUF.replaceUnique(
            marker = VIDEO_CHANNEL_DESCRIPTOR_MARKER,
            replacement = videoDescriptor,
        )

        // The input channel's TouchConfig must describe the same viewport used by the video
        // channel and local TouchMapper. All audited profiles encode each dimension in two
        // varint bytes, preserving the pinned outer protobuf lengths.
        val width = encodeVarint(config.viewport.width.toLong())
        val height = encodeVarint(config.viewport.height.toLong())
        if (width.size != TOUCH_DIMENSION_VARINT_BYTES || height.size != TOUCH_DIMENSION_VARINT_BYTES) {
            throw AasdkProtocolException("unsupported touch viewport encoding")
        }
        val touchMarkerOffset = protobuf.indexOfUnique(TOUCH_CONFIG_MARKER)
        width.copyInto(
            destination = protobuf,
            destinationOffset = touchMarkerOffset + TOUCH_WIDTH_OFFSET_IN_MARKER,
        )
        height.copyInto(
            destination = protobuf,
            destinationOffset = touchMarkerOffset + TOUCH_HEIGHT_OFFSET_IN_MARKER,
        )
        return envelope(SERVICE_DISCOVERY_RESPONSE, protobuf)
    }

    /** proto2 Status.OK must remain explicitly encoded as field 1, value 0. */
    fun channelOpenSuccess(): ByteArray = envelope(
        CHANNEL_OPEN_RESPONSE,
        byteArrayOf(0x08, 0x00),
    )

    fun sensorStartSuccess(): ByteArray = envelope(
        SENSOR_START_RESPONSE,
        byteArrayOf(0x08, 0x00),
    )

    fun sensorStartFailure(): ByteArray = envelope(
        SENSOR_START_RESPONSE,
        byteArrayOf(0x08, 0x01),
    )

    fun bindingSuccess(): ByteArray = envelope(
        BINDING_RESPONSE,
        byteArrayOf(0x08, 0x00),
    )

    fun bindingFailure(): ByteArray = envelope(
        BINDING_RESPONSE,
        byteArrayOf(0x08, 0x01),
    )

    /**
     * Encodes the pinned AASDK InputEventIndication touch schema.
     *
     * OpenAuto advertises a single primary touchscreen and uses pointer 0. The
     * caller maps normalized coordinates against the negotiated profile first.
     * Modern Android Auto parses this message with a proto2 schema, so all
     * required fields must remain present even when their numeric value is 0.
     * Explicit proto3 default values are wire-compatible with the pinned AASDK.
     */
    fun inputEventIndication(
        event: OpenAutoTouchEvent,
        timestampNanos: Long,
    ): ByteArray {
        if (timestampNanos < 0L) throw AasdkProtocolException("invalid touch timestamp")
        if (event.x < 0 || event.y < 0 || event.pointerId < 0) {
            throw AasdkProtocolException("invalid unsigned touch field")
        }

        val touchLocation = encodeVarintField(1, event.x.toLong()) +
            encodeVarintField(2, event.y.toLong()) +
            encodeVarintField(3, event.pointerId.toLong())

        val touchAction = when (event.phase) {
            TouchPhase.DOWN -> TOUCH_ACTION_PRESS
            TouchPhase.MOVE -> TOUCH_ACTION_DRAG
            TouchPhase.UP -> TOUCH_ACTION_RELEASE
            TouchPhase.CANCEL -> TOUCH_ACTION_CANCEL
        }
        val touchEvent = encodeLengthDelimitedField(1, touchLocation) +
            // action_index=0 is the only valid index for the single location.
            encodeVarintField(2, 0L) +
            encodeVarintField(3, touchAction)
        val indication = encodeVarintField(1, timestampNanos) +
            // disp_channel=0 selects the primary display and remains optional.
            encodeLengthDelimitedField(3, touchEvent)

        return envelope(INPUT_EVENT_INDICATION, indication)
    }

    fun avChannelSetupSuccess(): ByteArray = envelope(
        AV_CHANNEL_SETUP_RESPONSE,
        byteArrayOf(0x08, 0x02, 0x10, 0x01, 0x18, 0x00),
    )

    fun avChannelSetupFailure(): ByteArray = envelope(
        AV_CHANNEL_SETUP_RESPONSE,
        byteArrayOf(0x08, 0x01, 0x10, 0x01, 0x18, 0x00),
    )

    fun avInputOpenSuccess(): ByteArray = envelope(
        AV_INPUT_OPEN_RESPONSE,
        byteArrayOf(0x08, 0x00, 0x10, 0x00),
    )

    /** Used when the browser microphone is absent, stale, denied, or otherwise unavailable. */
    fun avInputOpenUnavailable(): ByteArray = envelope(
        AV_INPUT_OPEN_RESPONSE,
        byteArrayOf(0x08, 0x00, 0x10, 0x01),
    )

    /** Encodes owned 16 kHz, mono, signed 16-bit little-endian microphone PCM. */
    fun microphonePcmWithTimestamp(
        timestampMicros: Long,
        pcm: ByteArray,
    ): ByteArray {
        if (timestampMicros < 0L) throw AasdkProtocolException("invalid microphone timestamp")
        if (pcm.isEmpty()) throw AasdkProtocolException("microphone PCM is empty")
        if (pcm.size % MICROPHONE_BYTES_PER_SAMPLE != 0) {
            throw AasdkProtocolException("microphone PCM is not sample aligned")
        }
        if (pcm.size > AasdkFrameCodec.MAX_MESSAGE_SIZE - MESSAGE_ID_SIZE - AV_TIMESTAMP_SIZE) {
            throw AasdkProtocolException("microphone PCM exceeds message limit")
        }
        return envelope(
            AV_MEDIA_WITH_TIMESTAMP_INDICATION,
            listOf(encodeUInt64(timestampMicros), pcm).joinByteArrays(),
        )
    }

    fun videoFocusFocused(): ByteArray = envelope(
        VIDEO_FOCUS_INDICATION,
        byteArrayOf(0x08, 0x01),
    )

    /**
     * OpenAuto's parked-state signal. It is deliberately impossible to obtain
     * without the service passing its explicit bench safety decision.
     */
    fun drivingStatusUnrestricted(benchSafetyConfirmed: Boolean): ByteArray {
        if (!benchSafetyConfirmed) {
            throw SecurityException("unrestricted driving status requires the parked bench safety gate")
        }
        return envelope(
            SENSOR_EVENT_INDICATION,
            byteArrayOf(0x6A, 0x02, 0x08, 0x00),
        )
    }

    /** SensorEventIndication.night_mode(10).NightMode.is_night(1). */
    fun nightMode(isNight: Boolean): ByteArray = envelope(
        SENSOR_EVENT_INDICATION,
        encodeLengthDelimitedField(
            fieldNumber = 10,
            value = encodeVarintField(fieldNumber = 1, value = if (isNight) 1L else 0L),
        ),
    )

    fun dayMode(): ByteArray = nightMode(isNight = false)

    /**
     * Modern Android Auto requires field 1 even though the pinned 2018 AASDK
     * schema marked it proto3. Supplying a monotonic timestamp remains backward
     * compatible and is echoed by the peer.
     */
    fun pingRequest(timestampMicros: Long): ByteArray {
        if (timestampMicros < 0) throw AasdkProtocolException("invalid ping timestamp")
        return envelope(PING_REQUEST, encodeVarintField(1, timestampMicros))
    }

    fun shutdownResponse(): ByteArray = envelope(0x0010)

    fun avMediaAck(session: Int): ByteArray {
        if (session < 0) throw AasdkProtocolException("invalid AV session")
        return envelope(
            AV_MEDIA_ACK_INDICATION,
            encodeVarintField(1, session.toLong()) + encodeVarintField(2, 1),
        )
    }

    fun audioFocusResponse(request: ByteArray): ByteArray {
        require(messageId(request) == AUDIO_FOCUS_REQUEST) { "not an audio focus request" }
        val requestedFocus = readFirstVarintField(protobuf(request), fieldNumber = 1)
        // Matches both pinned OpenAuto and the current DHU schema:
        // RELEASE(4) -> LOSS(3), every gain request -> GAIN(1).
        val state = if (requestedFocus == AUDIO_FOCUS_RELEASE) {
            AUDIO_FOCUS_STATE_LOSS
        } else {
            AUDIO_FOCUS_STATE_GAIN
        }
        return envelope(AUDIO_FOCUS_RESPONSE, byteArrayOf(0x08, state.toByte()))
    }

    fun messageId(applicationPayload: ByteArray): Int {
        if (applicationPayload.size < MESSAGE_ID_SIZE) {
            throw AasdkProtocolException("application message has no id")
        }
        return ((applicationPayload[0].toInt() and 0xFF) shl 8) or
            (applicationPayload[1].toInt() and 0xFF)
    }

    fun protobuf(applicationPayload: ByteArray): ByteArray {
        messageId(applicationPayload)
        return applicationPayload.copyOfRange(MESSAGE_ID_SIZE, applicationPayload.size)
    }

    /** Reads one bounded protobuf varint field from the small request messages used here. */
    fun readFirstVarintField(protobuf: ByteArray, fieldNumber: Int): Long? {
        if (fieldNumber <= 0) throw AasdkProtocolException("invalid protobuf field number")
        var offset = 0
        while (offset < protobuf.size) {
            val key = readVarint(protobuf, offset)
            offset = key.nextOffset
            val number = (key.value ushr 3).toInt()
            val wireType = (key.value and 0x07).toInt()
            if (number <= 0) throw AasdkProtocolException("invalid protobuf key")
            when (wireType) {
                WIRE_VARINT -> {
                    val value = readVarint(protobuf, offset)
                    offset = value.nextOffset
                    if (number == fieldNumber) return value.value
                }

                WIRE_FIXED_64 -> offset = checkedAdvance(offset, 8, protobuf.size)
                WIRE_LENGTH_DELIMITED -> {
                    val length = readVarint(protobuf, offset)
                    offset = length.nextOffset
                    if (length.value > Int.MAX_VALUE) {
                        throw AasdkProtocolException("protobuf field length exceeds limit")
                    }
                    offset = checkedAdvance(offset, length.value.toInt(), protobuf.size)
                }

                WIRE_FIXED_32 -> offset = checkedAdvance(offset, 4, protobuf.size)
                else -> throw AasdkProtocolException("unsupported protobuf wire type")
            }
        }
        return null
    }

    private fun requireApplicationMessage(
        applicationPayload: ByteArray,
        expectedMessageId: Int,
        name: String,
    ): ByteArray {
        if (messageId(applicationPayload) != expectedMessageId) {
            throw AasdkProtocolException("not a $name message")
        }
        return protobuf(applicationPayload)
    }

    private fun readBooleanField(
        protobuf: ByteArray,
        fieldNumber: Int,
        name: String,
    ): Boolean = when (readFirstVarintField(protobuf, fieldNumber) ?: 0L) {
        0L -> false
        1L -> true
        else -> throw AasdkProtocolException("invalid AV input $name flag")
    }

    private fun envelope(messageId: Int, protobuf: ByteArray = ByteArray(0)): ByteArray {
        if (messageId !in 0..0xFFFF) throw AasdkProtocolException("invalid message id")
        if (protobuf.size > AasdkFrameCodec.MAX_MESSAGE_SIZE - MESSAGE_ID_SIZE) {
            throw AasdkProtocolException("protobuf message exceeds limit")
        }
        return ByteArray(MESSAGE_ID_SIZE + protobuf.size).also { output ->
            output[0] = (messageId ushr 8).toByte()
            output[1] = messageId.toByte()
            protobuf.copyInto(output, MESSAGE_ID_SIZE)
        }
    }

    private fun encodeVarintField(fieldNumber: Int, value: Long): ByteArray {
        if (fieldNumber <= 0 || value < 0) throw AasdkProtocolException("invalid protobuf varint")
        return encodeVarint((fieldNumber.toLong() shl 3) or WIRE_VARINT.toLong()) +
            encodeVarint(value)
    }

    private fun encodeLengthDelimitedField(fieldNumber: Int, value: ByteArray): ByteArray {
        if (fieldNumber <= 0) throw AasdkProtocolException("invalid protobuf field number")
        return encodeVarint((fieldNumber.toLong() shl 3) or WIRE_LENGTH_DELIMITED.toLong()) +
            encodeVarint(value.size.toLong()) +
            value
    }

    private fun encodeVarint(input: Long): ByteArray {
        if (input < 0L) throw AasdkProtocolException("invalid protobuf varint")
        val output = ArrayList<Byte>(MAX_VARINT_BYTES)
        var remaining = input
        do {
            var byte = (remaining and 0x7F).toInt()
            remaining = remaining ushr 7
            if (remaining != 0L) byte = byte or 0x80
            output += byte.toByte()
        } while (remaining != 0L)
        return output.toByteArray()
    }

    private fun encodeUInt64(value: Long): ByteArray {
        if (value < 0L) throw AasdkProtocolException("invalid unsigned 64-bit value")
        return ByteArray(AV_TIMESTAMP_SIZE) { index ->
            (value ushr ((AV_TIMESTAMP_SIZE - index - 1) * Byte.SIZE_BITS)).toByte()
        }
    }

    private fun readVarint(data: ByteArray, startOffset: Int): Varint {
        var offset = startOffset
        var value = 0L
        var shift = 0
        repeat(MAX_VARINT_BYTES) {
            if (offset >= data.size) throw AasdkProtocolException("truncated protobuf varint")
            val byte = data[offset++].toInt() and 0xFF
            if (shift == 63 && byte and 0xFE != 0) {
                throw AasdkProtocolException("protobuf varint overflows")
            }
            value = value or ((byte and 0x7F).toLong() shl shift)
            if (byte and 0x80 == 0) return Varint(value, offset)
            shift += 7
        }
        throw AasdkProtocolException("protobuf varint exceeds limit")
    }

    private fun checkedAdvance(offset: Int, count: Int, size: Int): Int {
        if (count < 0 || offset > size - count) {
            throw AasdkProtocolException("truncated protobuf field")
        }
        return offset + count
    }

    private data class Varint(val value: Long, val nextOffset: Int)

    private fun List<ByteArray>.joinByteArrays(): ByteArray {
        val totalSize = sumOf(ByteArray::size)
        if (totalSize > AasdkFrameCodec.MAX_MESSAGE_SIZE) {
            throw AasdkProtocolException("protobuf message exceeds limit")
        }
        return ByteArray(totalSize).also { output ->
            var offset = 0
            for (part in this) {
                part.copyInto(output, offset)
                offset += part.size
            }
        }
    }

    private const val MESSAGE_ID_SIZE = 2
    private const val AV_TIMESTAMP_SIZE = 8
    private const val MICROPHONE_BYTES_PER_SAMPLE = 2
    private const val MAX_VARINT_BYTES = 10
    private const val MAX_UINT32 = 0xFFFF_FFFFL
    private const val WIRE_VARINT = 0
    private const val WIRE_FIXED_64 = 1
    private const val WIRE_LENGTH_DELIMITED = 2
    private const val WIRE_FIXED_32 = 5
    private const val TOUCH_ACTION_PRESS = 0L
    private const val TOUCH_ACTION_RELEASE = 1L
    private const val TOUCH_ACTION_DRAG = 2L
    private const val TOUCH_ACTION_CANCEL = 3L
    private const val SAFE_VIDEO_FPS = 60
    private const val VIDEO_RESOLUTION_480P = 1
    private const val VIDEO_RESOLUTION_720P = 2
    private const val VIDEO_RESOLUTION_1080P = 3
    private const val VIDEO_RESOLUTION_720X1280 = 6
    private const val VIDEO_RESOLUTION_1080X1920 = 7
    private const val VIDEO_STREAM_TYPE = 3L
    private const val VIDEO_FPS_60_ENUM = 2L
    private const val TOUCH_WIDTH_OFFSET_IN_MARKER = 3
    private const val TOUCH_HEIGHT_OFFSET_IN_MARKER = 6
    private const val TOUCH_DIMENSION_VARINT_BYTES = 2

    private val VIDEO_CHANNEL_DESCRIPTOR_MARKER = byteArrayOf(
        0x0A,
        0x11,
        0x08,
        CHANNEL_VIDEO.toByte(),
        0x1A,
        0x0D,
        0x08,
        VIDEO_STREAM_TYPE.toByte(),
        0x22,
        0x07,
        0x08,
        VIDEO_RESOLUTION_480P.toByte(),
        0x10,
        VIDEO_FPS_60_ENUM.toByte(),
        0x28,
        0x8C.toByte(),
        0x01,
        0x28,
        0x01,
    )

    private val TOUCH_CONFIG_MARKER = byteArrayOf(
        0x12,
        0x06,
        0x08,
        0xA0.toByte(),
        0x06,
        0x10,
        0xE0.toByte(),
        0x03,
    )

    private val OPENAUTO_DEFAULT_SERVICE_DISCOVERY_PROTOBUF = decodeHex(
        """
        0A 0F 08 07 2A 0B 08 01 12 07 08 80 7D 10 10 18 01
        0A 14 08 04 1A 10 08 01 10 03 1A 08 08 80 F7 02 10 10 18 02 28 01
        0A 13 08 05 1A 0F 08 01 10 01 1A 07 08 80 7D 10 10 18 01 28 01
        0A 13 08 06 1A 0F 08 01 10 02 1A 07 08 80 7D 10 10 18 01 28 01
        0A 0C 08 02 12 08 0A 02 08 0D 0A 02 08 0A
        0A 11 08 03 1A 0D 08 03 22 07 08 01 10 02 28 8C 01 28 01
        0A 0C 08 01 22 08 12 06 08 A0 06 10 E0 03
        12 08 4F 70 65 6E 41 75 74 6F
        1A 09 55 6E 69 76 65 72 73 61 6C
        22 04 32 30 31 38
        2A 08 32 30 31 38 30 33 30 31
        30 01
        3A 03 66 31 78
        42 10 4F 70 65 6E 41 75 74 6F 20 41 75 74 6F 61 70 70
        4A 01 31
        52 03 31 2E 30
        """.trimIndent(),
    )

    private fun decodeHex(value: String): ByteArray {
        val normalized = value.filterNot(Char::isWhitespace)
        require(normalized.length % 2 == 0) { "invalid pinned hex fixture" }
        return ByteArray(normalized.length / 2) { index ->
            normalized.substring(index * 2, index * 2 + 2).toInt(16).toByte()
        }
    }

    private fun ByteArray.indexOfUnique(marker: ByteArray): Int {
        var match = -1
        for (offset in 0..size - marker.size) {
            if (marker.indices.all { index -> this[offset + index] == marker[index] }) {
                if (match >= 0) throw AasdkProtocolException("ambiguous service discovery fixture marker")
                match = offset
            }
        }
        if (match < 0) throw AasdkProtocolException("service discovery fixture marker missing")
        return match
    }

    private fun ByteArray.replaceUnique(marker: ByteArray, replacement: ByteArray): ByteArray {
        val markerOffset = indexOfUnique(marker)
        val outputSize = size - marker.size + replacement.size
        if (outputSize > AasdkFrameCodec.MAX_MESSAGE_SIZE - MESSAGE_ID_SIZE) {
            throw AasdkProtocolException("service discovery response exceeds message limit")
        }
        return ByteArray(outputSize).also { output ->
            copyInto(output, endIndex = markerOffset)
            replacement.copyInto(output, destinationOffset = markerOffset)
            copyInto(
                destination = output,
                destinationOffset = markerOffset + replacement.size,
                startIndex = markerOffset + marker.size,
            )
        }
    }
}
