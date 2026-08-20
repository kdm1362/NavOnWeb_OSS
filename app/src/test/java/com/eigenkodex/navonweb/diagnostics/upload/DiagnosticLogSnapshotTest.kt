/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.eigenkodex.navonweb.diagnostics.upload

import java.nio.charset.StandardCharsets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticLogSnapshotTest {
    @Test
    fun keepsNewestWholeRecordsInChronologicalOrder() {
        val exported = "{\"sequence\":1}\n{\"sequence\":2}\n{\"sequence\":3}\n"
        val lastTwoBytes = "{\"sequence\":2}\n{\"sequence\":3}\n"
            .toByteArray(StandardCharsets.UTF_8)
            .size

        val snapshot = DiagnosticLogSnapshotter.newestCompleteRecords(exported, lastTwoBytes)

        assertEquals("{\"sequence\":2}\n{\"sequence\":3}\n", snapshot.logText)
        assertTrue(snapshot.truncated)
        assertEquals(exported.toByteArray(StandardCharsets.UTF_8).size.toLong(), snapshot.originalByteCount)
        assertEquals(lastTwoBytes, snapshot.selectedByteCount)
    }

    @Test
    fun dropsAnIncompleteTailAndNeverSplitsKoreanUtf8() {
        val complete = "{\"detail\":\"연결 끊김\"}\n"
        val exported = complete + "{\"detail\":\"미완성"

        val snapshot = DiagnosticLogSnapshotter.newestCompleteRecords(
            exported,
            complete.toByteArray(StandardCharsets.UTF_8).size,
        )

        assertEquals(complete, snapshot.logText)
        assertTrue(snapshot.truncated)
        assertTrue(snapshot.selectedByteCount <= DiagnosticUploadPolicy.MAX_LOG_BYTES)
    }

    @Test
    fun fullExportUnderLimitIsNotMarkedTruncated() {
        val exported = "{\"event\":\"SESSION_FAILED\"}\n"

        val snapshot = DiagnosticLogSnapshotter.newestCompleteRecords(exported)

        assertEquals(exported, snapshot.logText)
        assertFalse(snapshot.truncated)
    }

    @Test
    fun acceptsExactlyTwoHundredFiftySixKibibytesAndNoMore() {
        val prefix = "{\"detail\":\""
        val suffix = "\"}\n"
        val paddingBytes = DiagnosticUploadPolicy.MAX_LOG_BYTES -
            prefix.toByteArray(StandardCharsets.UTF_8).size -
            suffix.toByteArray(StandardCharsets.UTF_8).size
        val maximumRecord = prefix + "x".repeat(paddingBytes) + suffix
        val exported = "{\"old\":true}\n" + maximumRecord

        val snapshot = DiagnosticLogSnapshotter.newestCompleteRecords(exported)

        assertEquals(DiagnosticUploadPolicy.MAX_LOG_BYTES, snapshot.selectedByteCount)
        assertEquals(maximumRecord, snapshot.logText)
        assertTrue(snapshot.truncated)
    }

    @Test
    fun exportWithoutAnyCompleteRecordProducesEmptySnapshot() {
        val exported = "{\"event\":\"SESSION_FAILED\"}"

        val snapshot = DiagnosticLogSnapshotter.newestCompleteRecords(exported)

        assertEquals("", snapshot.logText)
        assertTrue(snapshot.truncated)
        assertEquals(exported.toByteArray(StandardCharsets.UTF_8).size.toLong(), snapshot.originalByteCount)
    }
}
