/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.eigenkodex.navonweb.browser

import java.nio.charset.StandardCharsets
import java.time.Instant

data class BrowserNotice(
    val id: String,
    val publishedAt: String,
    val titleKo: String,
    val titleEn: String,
    val bodyKo: String,
    val bodyEn: String,
    val endsAt: String? = null,
    val translations: Map<String, BrowserNoticeTranslation> = emptyMap(),
)

data class BrowserNoticeTranslation(
    val title: String,
    val body: String,
)

data class BrowserNoticeFeed(
    val notices: List<BrowserNotice>,
    val fetchedAtEpochMillis: Long?,
    val stale: Boolean,
    val available: Boolean,
) {
    fun activeAt(nowEpochMillis: Long): BrowserNoticeFeed {
        if (nowEpochMillis < 0L || notices.isEmpty()) return this
        val now = Instant.ofEpochMilli(nowEpochMillis)
        val active = notices.filter { notice ->
            notice.endsAt?.let { value ->
                runCatching { Instant.parse(value) }.getOrNull()?.isAfter(now) == true
            } ?: true
        }
        return if (active.size == notices.size) this else copy(notices = active)
    }

    fun toJsonString(): String = buildString {
        append("{\"notices\":[")
        notices.take(MAX_NOTICES).forEachIndexed { index, notice ->
            if (index > 0) append(',')
            append("{\"id\":")
            appendJsonString(notice.id)
            append(",\"publishedAt\":")
            appendJsonString(notice.publishedAt)
            val translations = notice.browserTranslations()
            append(",\"title\":{")
            translations.entries.forEachIndexed { translationIndex, entry ->
                if (translationIndex > 0) append(',')
                appendJsonString(entry.key)
                append(':')
                appendJsonString(entry.value.title)
            }
            append("},\"body\":{")
            translations.entries.forEachIndexed { translationIndex, entry ->
                if (translationIndex > 0) append(',')
                appendJsonString(entry.key)
                append(':')
                appendJsonString(entry.value.body)
            }
            append("},\"endsAt\":")
            if (notice.endsAt == null) append("null") else appendJsonString(notice.endsAt)
            append('}')
        }
        append("],\"fetchedAtEpochMillis\":")
        append(fetchedAtEpochMillis ?: "null")
        append(",\"stale\":")
        append(stale)
        append(",\"available\":")
        append(available)
        append('}')
    }

    fun encodedSizeBytes(): Int = toJsonString().toByteArray(StandardCharsets.UTF_8).size

    companion object {
        const val MAX_NOTICES = 20
        const val MAX_RESPONSE_BYTES = 64 * 1024

        val UNAVAILABLE = BrowserNoticeFeed(
            notices = emptyList(),
            fetchedAtEpochMillis = null,
            stale = true,
            available = false,
        )

        fun bounded(
            notices: List<BrowserNotice>,
            fetchedAtEpochMillis: Long,
            stale: Boolean,
            available: Boolean = true,
        ): BrowserNoticeFeed {
            val accepted = ArrayList<BrowserNotice>(minOf(notices.size, MAX_NOTICES))
            for (notice in notices) {
                if (accepted.size >= MAX_NOTICES) break
                val candidate = BrowserNoticeFeed(
                    notices = accepted + notice,
                    fetchedAtEpochMillis = fetchedAtEpochMillis,
                    stale = stale,
                    available = available,
                )
                // A malformed or independently supplied oversized high-priority row must not
                // prevent later, valid notices from reaching the browser.
                if (candidate.encodedSizeBytes() > MAX_RESPONSE_BYTES) continue
                accepted += notice
            }
            return BrowserNoticeFeed(accepted, fetchedAtEpochMillis, stale, available)
        }
    }
}

private fun BrowserNotice.browserTranslations(): Map<String, BrowserNoticeTranslation> {
    val result = linkedMapOf(
        "ko" to BrowserNoticeTranslation(titleKo, bodyKo),
        "en" to BrowserNoticeTranslation(titleEn, bodyEn),
    )
    translations.entries
        .asSequence()
        .filter { (language, value) ->
            language.matches(BROWSER_LANGUAGE_TAG) && value.title.isNotBlank() && value.body.isNotBlank()
        }
        .sortedBy { it.key.lowercase() }
        .forEach { (language, value) ->
            val existing = result.keys.firstOrNull { it.equals(language, ignoreCase = true) }
            if (existing != null) {
                result[existing] = value
            } else if (result.size < MAX_BROWSER_TRANSLATIONS) {
                result[language] = value
            }
        }
    return result
}

private const val MAX_BROWSER_TRANSLATIONS = 10
private val BROWSER_LANGUAGE_TAG = Regex("[A-Za-z]{2,8}(?:-[A-Za-z0-9]{1,8})*")

private fun StringBuilder.appendJsonString(value: String) {
    append('"')
    value.forEach { character ->
        when (character) {
            '\\' -> append("\\\\")
            '"' -> append("\\\"")
            '\b' -> append("\\b")
            '\u000c' -> append("\\f")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> if (character.code < 0x20) append(' ') else append(character)
        }
    }
    append('"')
}
