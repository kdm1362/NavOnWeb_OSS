/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.eigenkodex.navonweb.browser

import com.eigenkodex.navonweb.openauto.OpenAutoKeyAction
import com.eigenkodex.navonweb.openauto.OpenAutoKeyCode
import com.eigenkodex.navonweb.openauto.OpenAutoKeyEvent
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

internal sealed interface BrowserKeyInputDecodeResult {
    data class Accepted(val event: OpenAutoKeyEvent) : BrowserKeyInputDecodeResult
    data object PayloadTooLarge : BrowserKeyInputDecodeResult
    data object UnsupportedMediaType : BrowserKeyInputDecodeResult
    data object Invalid : BrowserKeyInputDecodeResult
}

/**
 * Decodes one bounded WebRTC control-channel key event.
 *
 * Keeping this parser independent of Android JSON classes makes the protocol contract directly
 * testable on the host JVM. It also rejects duplicate or unknown members instead of silently
 * accepting whatever a lenient object mapper happens to keep.
 */
internal fun decodeBrowserKeyInput(
    contentType: String?,
    hasQueryParameters: Boolean,
    body: ByteArray,
): BrowserKeyInputDecodeResult {
    if (body.size > MAX_BROWSER_KEY_INPUT_BODY_BYTES) {
        return BrowserKeyInputDecodeResult.PayloadTooLarge
    }
    if (!isBrowserKeyJsonContentType(contentType)) {
        return BrowserKeyInputDecodeResult.UnsupportedMediaType
    }
    if (hasQueryParameters || body.isEmpty()) return BrowserKeyInputDecodeResult.Invalid

    val source = decodeStrictUtf8(body) ?: return BrowserKeyInputDecodeResult.Invalid
    val members = BrowserKeyJsonParser(source).parseObject()
        ?: return BrowserKeyInputDecodeResult.Invalid
    val scanCode = (members["scanCode"] as? BrowserKeyJsonValue.NumberValue)
        ?.encoded
        ?.toIntOrNull()
        ?: return BrowserKeyInputDecodeResult.Invalid
    val action = (members["action"] as? BrowserKeyJsonValue.StringValue)
        ?.value
        ?: return BrowserKeyInputDecodeResult.Invalid

    val event = when {
        scanCode == OpenAutoKeyCode.SCROLL_WHEEL -> {
            if (members.keys != SCROLL_MEMBERS || action != ACTION_SCROLL) {
                return BrowserKeyInputDecodeResult.Invalid
            }
            val delta = (members["delta"] as? BrowserKeyJsonValue.NumberValue)
                ?.encoded
                ?.toIntOrNull()
                ?: return BrowserKeyInputDecodeResult.Invalid
            val keyAction = when (delta) {
                -1 -> OpenAutoKeyAction.SCROLL_LEFT
                1 -> OpenAutoKeyAction.SCROLL_RIGHT
                else -> return BrowserKeyInputDecodeResult.Invalid
            }
            OpenAutoKeyEvent(scanCode, keyAction)
        }
        members.keys == BUTTON_MEMBERS &&
            scanCode in OpenAutoKeyCode.SUPPORTED &&
            scanCode != OpenAutoKeyCode.SCROLL_WHEEL &&
            action == ACTION_CLICK -> {
            OpenAutoKeyEvent(scanCode, OpenAutoKeyAction.CLICK)
        }
        else -> return BrowserKeyInputDecodeResult.Invalid
    }
    return if (OpenAutoKeyCode.supports(event)) {
        BrowserKeyInputDecodeResult.Accepted(event)
    } else {
        BrowserKeyInputDecodeResult.Invalid
    }
}

internal const val MAX_BROWSER_KEY_INPUT_BODY_BYTES = 128

internal fun isBrowserKeyJsonContentType(contentType: String?): Boolean {
    val parts = contentType?.split(';')?.map(String::trim) ?: return false
    if (parts.isEmpty() || !parts.first().equals("application/json", ignoreCase = true)) return false
    if (parts.size == 1) return true
    if (parts.size != 2) return false
    val parameter = parts[1].split('=', limit = 2)
    return parameter.size == 2 &&
        parameter[0].trim().equals("charset", ignoreCase = true) &&
        parameter[1].trim().equals("utf-8", ignoreCase = true)
}

private fun decodeStrictUtf8(body: ByteArray): String? = runCatching {
    StandardCharsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(body))
        .toString()
}.getOrNull()

private sealed interface BrowserKeyJsonValue {
    data class StringValue(val value: String) : BrowserKeyJsonValue
    data class NumberValue(val encoded: String) : BrowserKeyJsonValue
}

/** Strict, deliberately small JSON-object parser for the three primitive key-event members. */
private class BrowserKeyJsonParser(private val source: String) {
    private var offset = 0

    fun parseObject(): Map<String, BrowserKeyJsonValue>? = runCatching {
        skipWhitespace()
        requireCharacter('{')
        skipWhitespace()
        val members = linkedMapOf<String, BrowserKeyJsonValue>()
        if (!consume('}')) {
            while (true) {
                val name = parseString()
                if (name in members) invalid()
                skipWhitespace()
                requireCharacter(':')
                skipWhitespace()
                members[name] = parseValue()
                skipWhitespace()
                if (consume('}')) break
                requireCharacter(',')
                skipWhitespace()
            }
        }
        skipWhitespace()
        if (offset != source.length) invalid()
        members
    }.getOrNull()

    private fun parseValue(): BrowserKeyJsonValue {
        if (offset >= source.length) invalid()
        return if (source[offset] == '"') {
            BrowserKeyJsonValue.StringValue(parseString())
        } else {
            BrowserKeyJsonValue.NumberValue(parseInteger())
        }
    }

    private fun parseString(): String {
        requireCharacter('"')
        val value = StringBuilder()
        while (offset < source.length) {
            when (val character = source[offset++]) {
                '"' -> return value.toString()
                '\\' -> appendEscape(value)
                in '\u0000'..'\u001f' -> invalid()
                else -> value.append(character)
            }
        }
        invalid()
    }

    private fun appendEscape(value: StringBuilder) {
        if (offset >= source.length) invalid()
        when (source[offset++]) {
            '"' -> value.append('"')
            '\\' -> value.append('\\')
            '/' -> value.append('/')
            'b' -> value.append('\b')
            'f' -> value.append('\u000c')
            'n' -> value.append('\n')
            'r' -> value.append('\r')
            't' -> value.append('\t')
            'u' -> value.append(readUnicodeEscape())
            else -> invalid()
        }
    }

    private fun readUnicodeEscape(): Char {
        if (offset + 4 > source.length) invalid()
        var code = 0
        repeat(4) {
            code = (code shl 4) or (source[offset++].digitToIntOrNull(16) ?: invalid())
        }
        // The endpoint's names/actions are ASCII; rejecting standalone surrogates also prevents
        // accepting malformed Unicode strings that cannot round-trip through UTF-8.
        if (code in 0xD800..0xDFFF) invalid()
        return code.toChar()
    }

    private fun parseInteger(): String {
        val start = offset
        consume('-')
        if (consume('0')) {
            if (offset < source.length && source[offset].isDigit()) invalid()
        } else {
            val firstDigit = offset
            while (offset < source.length && source[offset].isDigit()) offset += 1
            if (offset == firstDigit) invalid()
        }
        if (offset < source.length && source[offset] in NUMBER_CONTINUATION) invalid()
        return source.substring(start, offset)
    }

    private fun skipWhitespace() {
        while (offset < source.length && source[offset] in JSON_WHITESPACE) offset += 1
    }

    private fun requireCharacter(expected: Char) {
        if (!consume(expected)) invalid()
    }

    private fun consume(expected: Char): Boolean {
        if (offset >= source.length || source[offset] != expected) return false
        offset += 1
        return true
    }

    private fun invalid(): Nothing = throw IllegalArgumentException("invalid key input JSON")
}

private const val ACTION_CLICK = "click"
private const val ACTION_SCROLL = "scroll"
private val BUTTON_MEMBERS = setOf("scanCode", "action")
private val SCROLL_MEMBERS = setOf("scanCode", "action", "delta")
private val JSON_WHITESPACE = setOf(' ', '\t', '\r', '\n')
private val NUMBER_CONTINUATION = setOf('.', 'e', 'E')
