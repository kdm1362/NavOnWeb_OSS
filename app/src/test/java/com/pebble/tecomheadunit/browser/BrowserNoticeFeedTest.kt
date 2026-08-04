/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.pebble.tecomheadunit.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserNoticeFeedTest {
    @Test
    fun feedEscapesMarkupAndKeepsTheResponseBounded() {
        val notices = (1..40).map { index ->
            BrowserNotice(
                id = "00000000-0000-4000-8000-${index.toString().padStart(12, '0')}",
                publishedAt = "2026-08-02T00:00:00Z",
                titleKo = "<script>알림 $index</script>",
                titleEn = "Notice $index",
                bodyKo = "내용 \"$index\" " + "가".repeat(8_000),
                bodyEn = "Body $index",
            )
        }

        val feed = BrowserNoticeFeed.bounded(notices, 123L, stale = false)
        val json = feed.toJsonString()

        assertTrue(feed.notices.size <= BrowserNoticeFeed.MAX_NOTICES)
        assertTrue(feed.encodedSizeBytes() <= BrowserNoticeFeed.MAX_RESPONSE_BYTES)
        assertTrue(json.contains("<script>"))
        assertTrue(json.contains("\\\"1\\\""))
    }

    @Test
    fun unavailableFeedHasNoRowsAndIsMarkedStale() {
        assertEquals(0, BrowserNoticeFeed.UNAVAILABLE.notices.size)
        assertTrue(BrowserNoticeFeed.UNAVAILABLE.stale)
        assertFalse(BrowserNoticeFeed.UNAVAILABLE.available)
    }

    @Test
    fun feedCarriesAdditionalLanguageTranslationsInTheSameNotice() {
        val notice = notice(idSuffix = "3", body = "fallback").copy(
            translations = mapOf(
                "ko" to BrowserNoticeTranslation("Korean", "Korean body"),
                "en" to BrowserNoticeTranslation("English", "English body"),
                "ja" to BrowserNoticeTranslation("Japanese", "Japanese body"),
                "pt-BR" to BrowserNoticeTranslation("Portuguese", "Portuguese body"),
            ),
        )

        val json = BrowserNoticeFeed.bounded(listOf(notice), 1L, false).toJsonString()

        assertTrue(json.contains("\"ja\":\"Japanese\""))
        assertTrue(json.contains("\"pt-BR\":\"Portuguese body\""))
    }

    @Test
    fun oversizedFirstNoticeDoesNotHideLaterValidNotices() {
        val oversized = notice(
            idSuffix = "1",
            body = "\\".repeat(BrowserNoticeFeed.MAX_RESPONSE_BYTES),
        )
        val valid = notice(idSuffix = "2", body = "정상 공지")

        val feed = BrowserNoticeFeed.bounded(
            notices = listOf(oversized, valid),
            fetchedAtEpochMillis = 123L,
            stale = false,
        )

        assertEquals(listOf(valid.id), feed.notices.map(BrowserNotice::id))
        assertTrue(feed.encodedSizeBytes() <= BrowserNoticeFeed.MAX_RESPONSE_BYTES)
    }

    @Test
    fun expiredNoticesAreRemovedWithoutWaitingForTheRemoteRefresh() {
        val expired = notice(idSuffix = "1", body = "지난 공지").copy(
            endsAt = "2026-08-02T12:00:00Z",
        )
        val active = notice(idSuffix = "2", body = "현재 공지").copy(
            endsAt = "2026-08-02T12:00:00.001Z",
        )

        val feed = BrowserNoticeFeed.bounded(
            notices = listOf(expired, active),
            fetchedAtEpochMillis = 123L,
            stale = false,
        ).activeAt(java.time.Instant.parse("2026-08-02T12:00:00Z").toEpochMilli())

        assertEquals(listOf(active.id), feed.notices.map(BrowserNotice::id))
    }

    private fun notice(idSuffix: String, body: String): BrowserNotice = BrowserNotice(
        id = "00000000-0000-4000-8000-${idSuffix.padStart(12, '0')}",
        publishedAt = "2026-08-02T00:00:00Z",
        titleKo = "공지",
        titleEn = "Notice",
        bodyKo = body,
        bodyEn = body,
    )
}
