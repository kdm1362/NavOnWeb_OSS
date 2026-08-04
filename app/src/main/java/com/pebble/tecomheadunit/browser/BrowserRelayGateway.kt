/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.pebble.tecomheadunit.browser

import java.nio.charset.StandardCharsets

/** A bounded HTTP-shaped request received through the cloud signaling WebSocket. */
data class BrowserRelayRequest(
    val method: String,
    val target: String,
    val headers: Map<String, String> = emptyMap(),
    val body: ByteArray = ByteArray(0),
) {
    init {
        require(method in ALLOWED_METHODS) { "unsupported relay method" }
        require(target.length in 1..MAX_TARGET_CHARS && target.startsWith('/')) {
            "invalid relay target"
        }
        require('#' !in target && "://" !in target) { "relay target must be origin-relative" }
        require(headers.size <= MAX_HEADERS) { "too many relay headers" }
        require(headers.all { (name, value) ->
            name == name.lowercase() &&
                name in ALLOWED_HEADERS &&
                value.length <= MAX_HEADER_VALUE_CHARS &&
                value.none { it == '\r' || it == '\n' || it == '\u0000' }
        }) { "invalid relay header" }
        require(body.size <= MAX_BODY_BYTES) { "relay body is too large" }
    }

    companion object {
        const val MAX_BODY_BYTES = 128 * 1_024
        const val MAX_TARGET_CHARS = 4_096
        private const val MAX_HEADERS = 8
        private const val MAX_HEADER_VALUE_CHARS = 4_096
        private val ALLOWED_METHODS = setOf("GET", "POST", "DELETE")
        private val ALLOWED_HEADERS = setOf(
            "accept",
            "content-type",
            "x-browser-credential",
            "x-pairing-code",
            "x-viewport-client-id",
        )
    }
}

data class BrowserRelayResponse(
    val status: Int,
    val contentType: String,
    val body: ByteArray,
) {
    init {
        require(status in 100..599) { "invalid relay response status" }
        require(contentType.length in 1..MAX_CONTENT_TYPE_CHARS) { "invalid relay content type" }
        require(body.size <= MAX_BODY_BYTES) { "relay response is too large" }
    }

    companion object {
        const val MAX_BODY_BYTES = 160 * 1_024
        private const val MAX_CONTENT_TYPE_CHARS = 160
        private val HEADER_END = "\r\n\r\n".toByteArray(StandardCharsets.US_ASCII)

        internal fun decodeHttpResponse(encoded: ByteArray): BrowserRelayResponse? {
            if (encoded.size > MAX_BODY_BYTES + MAX_HTTP_HEADER_BYTES) return null
            val headerEnd = encoded.indexOfSequence(HEADER_END)
            if (headerEnd !in 1..MAX_HTTP_HEADER_BYTES) return null
            val headerText = String(encoded, 0, headerEnd, StandardCharsets.US_ASCII)
            val lines = headerText.split("\r\n")
            val status = lines.firstOrNull()
                ?.split(' ')
                ?.getOrNull(1)
                ?.toIntOrNull()
                ?.takeIf { it in 100..599 }
                ?: return null
            val headers = lines.drop(1).mapNotNull { line ->
                val delimiter = line.indexOf(':')
                if (delimiter <= 0) null else {
                    line.substring(0, delimiter).trim().lowercase() to
                        line.substring(delimiter + 1).trim()
                }
            }.toMap()
            val contentType = headers["content-type"]?.takeIf(String::isNotBlank)
                ?: "application/octet-stream"
            val bodyOffset = headerEnd + HEADER_END.size
            val body = encoded.copyOfRange(bodyOffset, encoded.size)
            val declaredLength = headers["content-length"]?.toIntOrNull() ?: return null
            if (declaredLength != body.size || body.size > MAX_BODY_BYTES) return null
            return BrowserRelayResponse(status, contentType, body)
        }

        private fun ByteArray.indexOfSequence(needle: ByteArray): Int {
            if (needle.isEmpty() || size < needle.size) return -1
            for (start in 0..size - needle.size) {
                var matches = true
                for (offset in needle.indices) {
                    if (this[start + offset] != needle[offset]) {
                        matches = false
                        break
                    }
                }
                if (matches) return start
            }
            return -1
        }

        private const val MAX_HTTP_HEADER_BYTES = 16 * 1_024
    }
}
