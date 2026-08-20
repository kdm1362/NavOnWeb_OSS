/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.eigenkodex.navonweb.browser.webrtc

import com.eigenkodex.navonweb.browser.BrowserRelayRequest
import com.eigenkodex.navonweb.browser.BrowserRelayResponse
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.Locale

/** Result of accepting one browser RPC request from the WebRTC control data channel. */
internal sealed interface WebRtcControlDecodeResult {
    data class Accepted(
        val requestId: String,
        val request: BrowserRelayRequest,
    ) : WebRtcControlDecodeResult

    data class Rejected(
        val reason: WebRtcControlRejectReason,
        /** A bounded text response which may be sent without reserving an in-flight slot. */
        val responseText: String? = null,
        /** Protocol violations must tear down this data channel instead of being retried. */
        val shouldCloseChannel: Boolean,
    ) : WebRtcControlDecodeResult
}

internal enum class WebRtcControlRejectReason {
    BINARY_MESSAGE,
    MESSAGE_TOO_LARGE,
    INVALID_UTF8,
    MALFORMED_JSON,
    INVALID_ENVELOPE,
    INVALID_REQUEST,
    DUPLICATE_REQUEST_ID,
    RATE_LIMITED,
    TOO_MANY_IN_FLIGHT,
}

/** Bounded SCTP egress policy shared by the controller and host-side tests. */
internal object WebRtcControlDataChannelSendPolicy {
    const val MAX_BUFFERED_BYTES = 384 * 1_024L

    fun canSend(bufferedAmount: Long, messageBytes: Int): Boolean {
        if (bufferedAmount < 0L || messageBytes !in 1..WebRtcControlDataChannelCodec.MAX_MESSAGE_BYTES) {
            return false
        }
        return bufferedAmount <= MAX_BUFFERED_BYTES - messageBytes
    }
}

/**
 * Strict, session-scoped codec and admission gate for `navonweb-control-v1`.
 *
 * The wire request/response fields deliberately match CloudRelayTransport so the controller can
 * move an established browser session from the Cloudflare relay to a reliable WebRTC data channel
 * without defining a second RPC protocol. Parsing and admission are thread-safe; dispatching the
 * accepted [BrowserRelayRequest] remains the controller's responsibility.
 */
internal class WebRtcControlDataChannelCodec(
    private val elapsedRealtimeMillis: () -> Long = { System.nanoTime() / NANOS_PER_MILLISECOND },
) {
    private val stateLock = Any()
    private val inFlightRequestIds = linkedSetOf<String>()
    private var availableTokens = REQUEST_BURST.toDouble()
    private var tokensUpdatedAtMillis = elapsedRealtimeMillis()

    /** Copies the remaining bytes without changing the caller's buffer position. */
    fun decodeRequest(data: ByteBuffer, binary: Boolean): WebRtcControlDecodeResult {
        if (binary) return protocolRejection(WebRtcControlRejectReason.BINARY_MESSAGE)
        if (data.remaining() > MAX_MESSAGE_BYTES) {
            return protocolRejection(WebRtcControlRejectReason.MESSAGE_TOO_LARGE)
        }
        val copy = ByteArray(data.remaining())
        data.asReadOnlyBuffer().get(copy)
        return decodeRequest(copy, binary = false)
    }

    fun decodeRequest(message: ByteArray, binary: Boolean = false): WebRtcControlDecodeResult {
        if (binary) return protocolRejection(WebRtcControlRejectReason.BINARY_MESSAGE)
        if (message.size > MAX_MESSAGE_BYTES) {
            return protocolRejection(WebRtcControlRejectReason.MESSAGE_TOO_LARGE)
        }
        val text = decodeUtf8(message)
            ?: return protocolRejection(WebRtcControlRejectReason.INVALID_UTF8)
        val envelope = try {
            StrictJsonParser(text).parseObject()
        } catch (_: InvalidJsonException) {
            return protocolRejection(WebRtcControlRejectReason.MALFORMED_JSON)
        }

        val requestId = (envelope[FIELD_REQUEST_ID] as? JsonValue.StringValue)?.value
            ?: return protocolRejection(WebRtcControlRejectReason.INVALID_ENVELOPE)
        if (!REQUEST_ID_PATTERN.matches(requestId)) {
            return protocolRejection(WebRtcControlRejectReason.INVALID_ENVELOPE)
        }
        if (envelope.keys != REQUEST_FIELDS) {
            return protocolRejection(WebRtcControlRejectReason.INVALID_ENVELOPE)
        }
        val type = (envelope[FIELD_TYPE] as? JsonValue.StringValue)?.value
        val method = (envelope[FIELD_METHOD] as? JsonValue.StringValue)?.value
        val target = (envelope[FIELD_TARGET] as? JsonValue.StringValue)?.value
        val encodedBody = (envelope[FIELD_BODY_BASE64] as? JsonValue.StringValue)?.value
        val encodedHeaders = envelope[FIELD_HEADERS] as? JsonValue.ObjectValue
        if (
            type != TYPE_RPC_REQUEST || method == null || target == null ||
            encodedBody == null || encodedHeaders == null ||
            encodedHeaders.members.values.any { it !is JsonValue.StringValue }
        ) {
            return protocolRejection(WebRtcControlRejectReason.INVALID_ENVELOPE)
        }

        val request = runCatching {
            val headers = linkedMapOf<String, String>()
            encodedHeaders.members.forEach { (rawName, rawValue) ->
                val normalizedName = rawName.lowercase(Locale.ROOT)
                require(normalizedName !in headers) { "duplicate normalized relay header" }
                headers[normalizedName] = (rawValue as JsonValue.StringValue).value
            }
            require(encodedBody.length <= MAX_REQUEST_BODY_BASE64_CHARS) {
                "relay body encoding is too large"
            }
            val body = if (encodedBody.isEmpty()) {
                ByteArray(0)
            } else {
                Base64.getDecoder().decode(encodedBody)
            }
            // Keep all target, method, header, and decoded-body validation in the canonical DTO.
            BrowserRelayRequest(
                method = method.uppercase(Locale.ROOT),
                target = target,
                headers = headers,
                body = body,
            )
        }.getOrElse {
            return applicationRejection(
                requestId = requestId,
                reason = WebRtcControlRejectReason.INVALID_REQUEST,
                status = 400,
                error = "invalid_relay_request",
            )
        }

        synchronized(stateLock) {
            refillTokensLocked(elapsedRealtimeMillis())
            if (availableTokens < 1.0) {
                return applicationRejection(
                    requestId = requestId,
                    reason = WebRtcControlRejectReason.RATE_LIMITED,
                    status = 429,
                    error = "cloud_relay_rate_limited",
                )
            }
            availableTokens -= 1.0
            if (requestId in inFlightRequestIds) {
                return protocolRejection(WebRtcControlRejectReason.DUPLICATE_REQUEST_ID)
            }
            if (inFlightRequestIds.size >= MAX_IN_FLIGHT_REQUESTS) {
                return applicationRejection(
                    requestId = requestId,
                    reason = WebRtcControlRejectReason.TOO_MANY_IN_FLIGHT,
                    status = 429,
                    error = "cloud_relay_busy",
                )
            }
            inFlightRequestIds += requestId
        }
        return WebRtcControlDecodeResult.Accepted(requestId, request)
    }

    /**
     * Completes a previously accepted request and returns one text data-channel message.
     * Returns null after session reset, duplicate completion, or an unknown request ID.
     */
    fun encodeResponse(requestId: String, response: BrowserRelayResponse): String? {
        if (!REQUEST_ID_PATTERN.matches(requestId)) return null
        val reserved = synchronized(stateLock) { inFlightRequestIds.remove(requestId) }
        if (!reserved) return null

        // Reconstruct to validate and snapshot the mutable body before encoding it.
        val validated = BrowserRelayResponse(
            status = response.status,
            contentType = response.contentType,
            body = response.body.copyOf(),
        )
        val encoded = encodeResponseEnvelope(requestId, validated)
        return if (encoded.toByteArray(StandardCharsets.UTF_8).size <= MAX_MESSAGE_BYTES) {
            encoded
        } else {
            encodeErrorResponse(requestId, 502, "relay_response_too_large")
        }
    }

    /** Releases a request when dispatch or data-channel send fails. */
    fun abandonRequest(requestId: String): Boolean =
        synchronized(stateLock) { inFlightRequestIds.remove(requestId) }

    /** Clears only per-session reservations; rate-limit state intentionally remains monotonic. */
    fun clearInFlightRequests() {
        synchronized(stateLock) { inFlightRequestIds.clear() }
    }

    internal val inFlightRequestCount: Int
        get() = synchronized(stateLock) { inFlightRequestIds.size }

    private fun refillTokensLocked(nowMillis: Long) {
        val elapsedMillis = (nowMillis - tokensUpdatedAtMillis).coerceAtLeast(0L)
        availableTokens = minOf(
            REQUEST_BURST.toDouble(),
            availableTokens + elapsedMillis.toDouble() * REQUESTS_PER_SECOND / 1_000.0,
        )
        tokensUpdatedAtMillis = nowMillis
    }

    private fun applicationRejection(
        requestId: String,
        reason: WebRtcControlRejectReason,
        status: Int,
        error: String,
    ): WebRtcControlDecodeResult.Rejected = WebRtcControlDecodeResult.Rejected(
        reason = reason,
        responseText = encodeErrorResponse(requestId, status, error),
        shouldCloseChannel = false,
    )

    private fun encodeErrorResponse(requestId: String, status: Int, error: String): String {
        val body = "{\"error\":${jsonString(error)}}".toByteArray(StandardCharsets.UTF_8)
        return encodeResponseEnvelope(
            requestId,
            BrowserRelayResponse(status, JSON_CONTENT_TYPE, body),
        )
    }

    private fun encodeResponseEnvelope(
        requestId: String,
        response: BrowserRelayResponse,
    ): String = buildString {
        append("{\"type\":\"")
        append(TYPE_RPC_RESPONSE)
        append("\",\"requestId\":")
        append(jsonString(requestId))
        append(",\"status\":")
        append(response.status)
        append(",\"contentType\":")
        append(jsonString(response.contentType))
        append(",\"bodyBase64\":")
        append(jsonString(Base64.getEncoder().encodeToString(response.body)))
        append('}')
    }

    private fun decodeUtf8(bytes: ByteArray): String? = runCatching {
        StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    }.getOrNull()

    internal companion object {
        const val DATA_CHANNEL_LABEL = "navonweb-control-v1"
        const val MAX_MESSAGE_BYTES = 192 * 1_024
        const val MAX_REQUEST_BODY_BYTES = BrowserRelayRequest.MAX_BODY_BYTES
        const val MAX_RESPONSE_BODY_BYTES = BrowserRelayResponse.MAX_BODY_BYTES
        const val REQUESTS_PER_SECOND = 64
        const val REQUEST_BURST = 96
        const val MAX_IN_FLIGHT_REQUESTS = 16

        const val NANOS_PER_MILLISECOND = 1_000_000L
        const val MAX_REQUEST_BODY_BASE64_CHARS = ((MAX_REQUEST_BODY_BYTES + 2) / 3) * 4
        const val JSON_CONTENT_TYPE = "application/json; charset=utf-8"
        const val TYPE_RPC_REQUEST = "rpc_request"
        const val TYPE_RPC_RESPONSE = "rpc_response"
        const val FIELD_TYPE = "type"
        const val FIELD_REQUEST_ID = "requestId"
        const val FIELD_METHOD = "method"
        const val FIELD_TARGET = "target"
        const val FIELD_HEADERS = "headers"
        const val FIELD_BODY_BASE64 = "bodyBase64"
        val REQUEST_FIELDS = setOf(
            FIELD_TYPE,
            FIELD_REQUEST_ID,
            FIELD_METHOD,
            FIELD_TARGET,
            FIELD_HEADERS,
            FIELD_BODY_BASE64,
        )
        val REQUEST_ID_PATTERN = Regex("^[A-Za-z0-9_-]{16,64}$")

        fun protocolRejection(reason: WebRtcControlRejectReason) =
            WebRtcControlDecodeResult.Rejected(reason = reason, shouldCloseChannel = true)

        fun jsonString(value: String): String = buildString(value.length + 2) {
            append('"')
            value.forEach { character ->
                when (character) {
                    '"' -> append("\\\"")
                    '\\' -> append("\\\\")
                    '\b' -> append("\\b")
                    '\u000C' -> append("\\f")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    '\t' -> append("\\t")
                    else -> if (character < ' ') {
                        append("\\u")
                        append(character.code.toString(16).padStart(4, '0'))
                    } else {
                        append(character)
                    }
                }
            }
            append('"')
        }
    }
}

private sealed interface JsonValue {
    data class ObjectValue(val members: Map<String, JsonValue>) : JsonValue
    data class ArrayValue(val elements: List<JsonValue>) : JsonValue
    data class StringValue(val value: String) : JsonValue
    data class NumberValue(val encoded: String) : JsonValue
    data class BooleanValue(val value: Boolean) : JsonValue
    data object NullValue : JsonValue
}

private class InvalidJsonException : RuntimeException()

/** Small strict parser keeps the codec usable in host-side JVM tests without Android JSON stubs. */
private class StrictJsonParser(private val source: String) {
    private var offset = 0
    private var nodes = 0

    fun parseObject(): Map<String, JsonValue> {
        skipWhitespace()
        val root = parseValue(depth = 0) as? JsonValue.ObjectValue ?: invalid()
        skipWhitespace()
        if (offset != source.length) invalid()
        return root.members
    }

    private fun parseValue(depth: Int): JsonValue {
        if (depth > MAX_DEPTH || ++nodes > MAX_NODES || offset >= source.length) invalid()
        return when (source[offset]) {
            '{' -> parseObjectValue(depth + 1)
            '[' -> parseArrayValue(depth + 1)
            '"' -> JsonValue.StringValue(parseString())
            't' -> parseLiteral("true", JsonValue.BooleanValue(true))
            'f' -> parseLiteral("false", JsonValue.BooleanValue(false))
            'n' -> parseLiteral("null", JsonValue.NullValue)
            '-', in '0'..'9' -> JsonValue.NumberValue(parseNumber())
            else -> invalid()
        }
    }

    private fun parseObjectValue(depth: Int): JsonValue.ObjectValue {
        expect('{')
        skipWhitespace()
        val result = linkedMapOf<String, JsonValue>()
        if (consume('}')) return JsonValue.ObjectValue(result)
        while (true) {
            if (offset >= source.length || source[offset] != '"') invalid()
            val name = parseString()
            if (name in result) invalid()
            skipWhitespace()
            expect(':')
            skipWhitespace()
            result[name] = parseValue(depth)
            skipWhitespace()
            if (consume('}')) return JsonValue.ObjectValue(result)
            expect(',')
            skipWhitespace()
        }
    }

    private fun parseArrayValue(depth: Int): JsonValue.ArrayValue {
        expect('[')
        skipWhitespace()
        val result = mutableListOf<JsonValue>()
        if (consume(']')) return JsonValue.ArrayValue(result)
        while (true) {
            result += parseValue(depth)
            skipWhitespace()
            if (consume(']')) return JsonValue.ArrayValue(result)
            expect(',')
            skipWhitespace()
        }
    }

    private fun parseString(): String {
        expect('"')
        val result = StringBuilder()
        while (offset < source.length) {
            val character = source[offset++]
            when {
                character == '"' -> return result.toString()
                character == '\\' -> appendEscape(result)
                character < ' ' -> invalid()
                else -> result.append(character)
            }
        }
        invalid()
    }

    private fun appendEscape(result: StringBuilder) {
        if (offset >= source.length) invalid()
        when (source[offset++]) {
            '"' -> result.append('"')
            '\\' -> result.append('\\')
            '/' -> result.append('/')
            'b' -> result.append('\b')
            'f' -> result.append('\u000C')
            'n' -> result.append('\n')
            'r' -> result.append('\r')
            't' -> result.append('\t')
            'u' -> appendUnicodeEscape(result)
            else -> invalid()
        }
    }

    private fun appendUnicodeEscape(result: StringBuilder) {
        val first = readHexCodeUnit()
        if (first in HIGH_SURROGATE_RANGE) {
            if (offset + 2 > source.length || source[offset] != '\\' || source[offset + 1] != 'u') {
                invalid()
            }
            offset += 2
            val second = readHexCodeUnit()
            if (second !in LOW_SURROGATE_RANGE) invalid()
            result.appendCodePoint(Character.toCodePoint(first.toChar(), second.toChar()))
        } else {
            if (first in LOW_SURROGATE_RANGE) invalid()
            result.append(first.toChar())
        }
    }

    private fun readHexCodeUnit(): Int {
        if (offset + 4 > source.length) invalid()
        var result = 0
        repeat(4) {
            val digit = source[offset++].digitToIntOrNull(16) ?: invalid()
            result = (result shl 4) or digit
        }
        return result
    }

    private fun parseNumber(): String {
        val start = offset
        consume('-')
        if (consume('0')) {
            if (offset < source.length && source[offset].isDigit()) invalid()
        } else {
            requireDigits()
        }
        if (consume('.')) requireDigits()
        if (offset < source.length && (source[offset] == 'e' || source[offset] == 'E')) {
            offset += 1
            if (offset < source.length && (source[offset] == '+' || source[offset] == '-')) offset += 1
            requireDigits()
        }
        return source.substring(start, offset)
    }

    private fun requireDigits() {
        val start = offset
        while (offset < source.length && source[offset].isDigit()) offset += 1
        if (offset == start) invalid()
    }

    private fun <T : JsonValue> parseLiteral(encoded: String, value: T): T {
        if (!source.regionMatches(offset, encoded, 0, encoded.length)) invalid()
        offset += encoded.length
        return value
    }

    private fun skipWhitespace() {
        while (
            offset < source.length &&
            (source[offset] == ' ' || source[offset] == '\t' ||
                source[offset] == '\r' || source[offset] == '\n')
        ) {
            offset += 1
        }
    }

    private fun expect(expected: Char) {
        if (!consume(expected)) invalid()
    }

    private fun consume(expected: Char): Boolean {
        if (offset >= source.length || source[offset] != expected) return false
        offset += 1
        return true
    }

    private fun invalid(): Nothing = throw InvalidJsonException()

    private companion object {
        const val MAX_DEPTH = 8
        const val MAX_NODES = 1_024
        val HIGH_SURROGATE_RANGE = 0xD800..0xDBFF
        val LOW_SURROGATE_RANGE = 0xDC00..0xDFFF
    }
}
