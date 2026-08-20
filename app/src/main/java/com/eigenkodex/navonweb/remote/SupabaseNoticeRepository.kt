/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.eigenkodex.navonweb.remote

import android.content.Context
import com.eigenkodex.navonweb.BuildConfig
import com.eigenkodex.navonweb.browser.BrowserNotice
import com.eigenkodex.navonweb.browser.BrowserNoticeFeed
import com.eigenkodex.navonweb.browser.BrowserNoticeTranslation
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONArray
import org.json.JSONObject

class SupabaseNoticeRepository(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE,
    )
    private val current = AtomicReference(loadCachedFeed())

    fun snapshot(): BrowserNoticeFeed = current.get().activeAt(System.currentTimeMillis())

    fun refresh(): BrowserNoticeFeed {
        if (!isConfigured()) {
            current.set(BrowserNoticeFeed.UNAVAILABLE)
            return BrowserNoticeFeed.UNAVAILABLE
        }
        return runCatching {
            val body = fetchRows()
            val fetchedAt = System.currentTimeMillis()
            val feed = parseRows(
                body = body,
                fetchedAt = fetchedAt,
                stale = false,
                nowEpochMillis = fetchedAt,
            )
            preferences.edit()
                .putString(CACHE_ROWS_KEY, body)
                .putLong(CACHE_FETCHED_AT_KEY, fetchedAt)
                .apply()
            current.set(feed)
            feed
        }.getOrElse {
            // Reparse the cached rows on every fallback. A row that was active when fetched
            // may have reached ends_at while the phone was offline.
            val stale = loadCachedFeed()
            current.set(stale)
            stale
        }
    }

    private fun loadCachedFeed(nowEpochMillis: Long = System.currentTimeMillis()): BrowserNoticeFeed {
        val rows = preferences.getString(CACHE_ROWS_KEY, null) ?: return BrowserNoticeFeed.UNAVAILABLE
        val fetchedAt = preferences.getLong(CACHE_FETCHED_AT_KEY, 0L)
        val cacheAgeMillis = nowEpochMillis - fetchedAt
        if (fetchedAt <= 0L || cacheAgeMillis !in 0..MAX_CACHE_AGE_MILLIS) {
            return BrowserNoticeFeed.UNAVAILABLE
        }
        return runCatching {
            parseRows(
                body = rows,
                fetchedAt = fetchedAt,
                stale = true,
                nowEpochMillis = nowEpochMillis,
            )
        }
            .getOrDefault(BrowserNoticeFeed.UNAVAILABLE)
    }

    private fun fetchRows(): String {
        val query = listOf(
            "select" to SELECT_COLUMNS,
            "min_version_code" to "lte.${BuildConfig.VERSION_CODE}",
            "or" to "(max_version_code.is.null,max_version_code.gte.${BuildConfig.VERSION_CODE})",
            "order" to "priority.desc,starts_at.desc",
            "limit" to BrowserNoticeFeed.MAX_NOTICES.toString(),
        ).joinToString("&") { (name, value) ->
            "${urlEncode(name)}=${urlEncode(value)}"
        }
        val connection = java.net.URL("${BuildConfig.SUPABASE_URL}/rest/v1/notices?$query")
            .openConnection() as HttpURLConnection
        return try {
            connection.requestMethod = "GET"
            connection.connectTimeout = CONNECT_TIMEOUT_MILLIS
            connection.readTimeout = READ_TIMEOUT_MILLIS
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("apikey", BuildConfig.SUPABASE_PUBLISHABLE_KEY)
            connection.setRequestProperty("Connection", "close")
            val status = connection.responseCode
            if (status != HttpURLConnection.HTTP_OK) error("notice HTTP $status")
            readLimitedUtf8(connection.inputStream, MAX_REMOTE_RESPONSE_BYTES)
        } finally {
            connection.disconnect()
        }
    }

    private fun parseRows(
        body: String,
        fetchedAt: Long,
        stale: Boolean,
        nowEpochMillis: Long,
    ): BrowserNoticeFeed {
        val rows = JSONArray(body)
        val notices = buildList {
            for (index in 0 until minOf(rows.length(), BrowserNoticeFeed.MAX_NOTICES)) {
                val row = rows.optJSONObject(index) ?: continue
                parseNotice(row, nowEpochMillis)?.let(::add)
            }
        }
        return BrowserNoticeFeed.bounded(notices, fetchedAt, stale)
    }

    private fun parseNotice(row: JSONObject, nowEpochMillis: Long): BrowserNotice? {
        val id = row.optString("id").trim().takeIf(UUID_PATTERN::matches) ?: return null
        val startsAt = row.optString("starts_at").trim()
        val endsAt = if (row.isNull("ends_at")) null else row.optString("ends_at").trim()
        if (!isActiveWindow(startsAt, endsAt, nowEpochMillis)) return null
        val titleKo = boundedText(row.optString("title_ko"), MAX_TITLE_BYTES)
        val titleEn = boundedText(row.optString("title_en"), MAX_TITLE_BYTES)
        val bodyKo = boundedText(row.optString("body_ko"), MAX_NOTICE_BODY_BYTES)
        val bodyEn = boundedText(row.optString("body_en"), MAX_NOTICE_BODY_BYTES)
        if (titleKo.isBlank() && titleEn.isBlank()) return null
        if (bodyKo.isBlank() && bodyEn.isBlank()) return null
        val translations = linkedMapOf<String, BrowserNoticeTranslation>()
        val translatedRows = row.optJSONArray("translations")
        if (translatedRows != null) {
            for (index in 0 until minOf(translatedRows.length(), MAX_TRANSLATIONS_PER_NOTICE)) {
                val translated = translatedRows.optJSONObject(index) ?: continue
                val languageTag = translated.optString("language_tag").trim()
                if (!LANGUAGE_TAG_PATTERN.matches(languageTag) ||
                    translations.keys.any { it.equals(languageTag, ignoreCase = true) }
                ) {
                    continue
                }
                val title = boundedText(translated.optString("title"), MAX_TITLE_BYTES)
                val body = boundedText(translated.optString("body"), MAX_NOTICE_BODY_BYTES)
                if (title.isBlank() || body.isBlank()) continue
                translations[languageTag] = BrowserNoticeTranslation(title, body)
            }
        }
        translations.putIfAbsent("ko", BrowserNoticeTranslation(titleKo.ifBlank { titleEn }, bodyKo.ifBlank { bodyEn }))
        translations.putIfAbsent("en", BrowserNoticeTranslation(titleEn.ifBlank { titleKo }, bodyEn.ifBlank { bodyKo }))
        return BrowserNotice(
            id = id,
            publishedAt = startsAt.take(MAX_DATE_CHARACTERS),
            titleKo = titleKo.ifBlank { titleEn },
            titleEn = titleEn.ifBlank { titleKo },
            bodyKo = bodyKo.ifBlank { bodyEn },
            bodyEn = bodyEn.ifBlank { bodyKo },
            endsAt = endsAt?.take(MAX_DATE_CHARACTERS),
            translations = translations,
        )
    }

    companion object {
        private const val PREFERENCES_NAME = "navonweb_remote_notices_v1"
        private const val CACHE_ROWS_KEY = "rows"
        private const val CACHE_FETCHED_AT_KEY = "fetched_at"
        private const val MAX_CACHE_AGE_MILLIS = 7L * 24L * 60L * 60L * 1_000L
        private const val CONNECT_TIMEOUT_MILLIS = 5_000
        private const val READ_TIMEOUT_MILLIS = 8_000
        // public.notices permits two 16 KiB bodies per row. Four MiB contains the
        // 20-row response even when JSON escaping expands every control character.
        internal const val MAX_REMOTE_RESPONSE_BYTES = 4 * 1_024 * 1_024
        private const val MAX_TITLE_BYTES = 480
        // The browser intentionally renders at most 4,000 characters. Bounding by
        // UTF-8 bytes here is stricter and leaves ample room in the 64 KiB local feed.
        internal const val MAX_NOTICE_BODY_BYTES = 4_000
        private const val MAX_DATE_CHARACTERS = 64
        private const val MAX_TRANSLATIONS_PER_NOTICE = 10
        private const val SELECT_COLUMNS =
            "id,title_ko,title_en,body_ko,body_en,priority,starts_at,ends_at," +
                "translations:notice_translations(language_tag,title,body)," +
                "min_version_code,max_version_code,updated_at"
        private val UUID_PATTERN = Regex(
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-5][0-9a-fA-F]{3}-[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}",
        )
        private val LANGUAGE_TAG_PATTERN = Regex("[A-Za-z]{2,8}(?:-[A-Za-z0-9]{1,8})*")

        fun isConfigured(): Boolean =
            BuildConfig.SUPABASE_URL.startsWith("https://") &&
                BuildConfig.SUPABASE_PUBLISHABLE_KEY.startsWith("sb_publishable_")

        internal fun isActiveWindow(
            startsAt: String,
            endsAt: String?,
            nowEpochMillis: Long,
        ): Boolean {
            if (nowEpochMillis < 0L) return false
            val starts = runCatching { Instant.parse(startsAt) }.getOrNull() ?: return false
            val ends = endsAt
                ?.takeIf(String::isNotBlank)
                ?.let { value -> runCatching { Instant.parse(value) }.getOrNull() ?: return false }
            val now = Instant.ofEpochMilli(nowEpochMillis)
            return !starts.isAfter(now) && (ends == null || ends.isAfter(now))
        }

        internal fun boundedText(value: String, maxBytes: Int): String {
            require(maxBytes > 0)
            val normalized = value.replace("\u0000", "").trim()
            if (normalized.toByteArray(StandardCharsets.UTF_8).size <= maxBytes) return normalized
            var low = 0
            var high = normalized.length
            while (low < high) {
                val middle = (low + high + 1) / 2
                val candidate = normalized.substring(0, middle)
                if (candidate.toByteArray(StandardCharsets.UTF_8).size <= maxBytes) low = middle else high = middle - 1
            }
            var end = low
            if (end in 1 until normalized.length && Character.isHighSurrogate(normalized[end - 1])) end -= 1
            return normalized.substring(0, end).trimEnd()
        }

        private fun urlEncode(value: String): String =
            URLEncoder.encode(value, StandardCharsets.UTF_8.name()).replace("+", "%20")

        private fun readLimitedUtf8(input: java.io.InputStream, maxBytes: Int): String = input.use { stream ->
            val output = ByteArrayOutputStream(minOf(maxBytes, 16 * 1024))
            val buffer = ByteArray(8 * 1024)
            while (true) {
                val count = stream.read(buffer)
                if (count < 0) break
                if (output.size() + count > maxBytes) error("notice response too large")
                output.write(buffer, 0, count)
            }
            output.toString(StandardCharsets.UTF_8.name())
        }
    }
}
