/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.eigenkodex.navonweb.remote

import java.nio.charset.StandardCharsets
import java.time.Instant
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SupabaseNoticeRepositoryPolicyTest {
    private val now = Instant.parse("2026-08-02T12:00:00Z").toEpochMilli()

    @Test
    fun publicationWindowIncludesStartAndExcludesEnd() {
        assertTrue(
            SupabaseNoticeRepository.isActiveWindow(
                startsAt = "2026-08-02T12:00:00Z",
                endsAt = null,
                nowEpochMillis = now,
            ),
        )
        assertTrue(
            SupabaseNoticeRepository.isActiveWindow(
                startsAt = "2026-08-02T11:00:00+00:00",
                endsAt = "2026-08-02T12:00:00.001Z",
                nowEpochMillis = now,
            ),
        )
        assertFalse(
            SupabaseNoticeRepository.isActiveWindow(
                startsAt = "2026-08-02T12:00:00.001Z",
                endsAt = null,
                nowEpochMillis = now,
            ),
        )
        assertFalse(
            SupabaseNoticeRepository.isActiveWindow(
                startsAt = "2026-08-02T11:00:00Z",
                endsAt = "2026-08-02T12:00:00Z",
                nowEpochMillis = now,
            ),
        )
    }

    @Test
    fun publicationWindowFailsClosedForMalformedDates() {
        assertFalse(SupabaseNoticeRepository.isActiveWindow("not-a-date", null, now))
        assertFalse(
            SupabaseNoticeRepository.isActiveWindow(
                "2026-08-02T11:00:00Z",
                "not-a-date",
                now,
            ),
        )
    }

    @Test
    fun noticeBodyLimitPreservesUtf8CodePointBoundaries() {
        val bounded = SupabaseNoticeRepository.boundedText(
            value = "😀".repeat(2_000),
            maxBytes = SupabaseNoticeRepository.MAX_NOTICE_BODY_BYTES,
        )

        assertTrue(
            bounded.toByteArray(StandardCharsets.UTF_8).size <=
                SupabaseNoticeRepository.MAX_NOTICE_BODY_BYTES,
        )
        assertFalse(bounded.last().isHighSurrogate())
        assertTrue(bounded.length % 2 == 0)
    }
}
