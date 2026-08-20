/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.eigenkodex.navonweb.diagnostics

import java.io.File
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AppDiagnosticsTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun encoderDropsSecretFieldsAndRedactsPayloadMarkers() {
        val encoded = DiagnosticEntryEncoder.encode(
            DiagnosticEntry(
                timestamp = Instant.parse("2026-08-02T00:00:00Z"),
                level = DiagnosticLevel.INFO,
                event = DiagnosticEventCode.RUNTIME_SNAPSHOT,
                fields = mapOf(
                    "connection_state" to "CONNECTED",
                    "identity_provider" to "DEVELOPMENT_PEM",
                    "pairing_code" to "123456",
                    "credential" to "top-secret",
                    "network" to "http://10.0.0.231:8787/",
                    "ipv6" to "fe80::1234:5678:abcd:ef01",
                    "mdns" to "phone-session.local",
                    "code" to "654321",
                    "digest" to "a".repeat(64),
                    "payload" to "a=ice-pwd:raw-secret",
                    "line" to "first\r\nsecond",
                ),
            ),
        )

        assertTrue(encoded.contains("CONNECTED"))
        assertTrue(encoded.contains("\"identity_provider\":\"DEVELOPMENT_PEM\""))
        assertTrue(encoded.contains("[redacted]"))
        assertFalse(encoded.contains("123456"))
        assertFalse(encoded.contains("top-secret"))
        assertFalse(encoded.contains("10.0.0.231"))
        assertFalse(encoded.contains("fe80::1234"))
        assertFalse(encoded.contains("phone-session.local"))
        assertFalse(encoded.contains("654321"))
        assertFalse(encoded.contains("a".repeat(64)))
        assertFalse(encoded.contains("raw-secret"))
        assertFalse(encoded.contains("first\r\nsecond"))
        assertEquals(1, encoded.count { it == '\n' })
    }

    @Test
    fun rotatingSinkBoundsFilesExportsOldestFirstAndClears() {
        val directory = temporaryFolder.newFolder("logs")
        val sink = RotatingFileDiagnosticSink(
            directory = directory,
            maxActiveBytes = 256L,
            archiveCount = 3,
        )

        repeat(12) { index ->
            sink.append(
                DiagnosticEntry(
                    timestamp = Instant.ofEpochSecond(index.toLong()),
                    level = DiagnosticLevel.INFO,
                    event = DiagnosticEventCode.RUNTIME_SNAPSHOT,
                    fields = mapOf("sequence" to index, "detail" to "x".repeat(80)),
                ),
            )
        }

        val summary = sink.summary()
        val exported = sink.exportText()
        assertTrue(summary.fileCount in 1..4)
        assertTrue(exported.contains("\"sequence\":\"11\""))
        assertFalse(exported.contains("\"sequence\":\"0\""))
        assertTrue(exported.indexOf("\"sequence\":\"9\"") < exported.indexOf("\"sequence\":\"11\""))

        sink.clear()
        assertEquals(DiagnosticLogSummary.EMPTY, sink.summary())
        assertEquals("", sink.exportText())
    }

    @Test
    fun expiredFilesAreRemovedAtStartup() {
        val directory = temporaryFolder.newFolder("expired")
        val active = File(directory, RotatingFileDiagnosticSink.ACTIVE_FILE_NAME)
        active.writeText("old")
        active.setLastModified(1_000L)

        val sink = RotatingFileDiagnosticSink(
            directory = directory,
            clockMillis = { 10_000L },
            maxActiveBytes = 256L,
            archiveCount = 1,
            maxAgeMillis = 2_000L,
        )

        assertEquals(DiagnosticLogSummary.EMPTY, sink.summary())
    }

    @Test
    fun appendPrunesOnlyAfterIntervalElapses() {
        val directory = temporaryFolder.newFolder("gated")
        var nowMillis = 10_000L
        val sink = RotatingFileDiagnosticSink(
            directory = directory,
            clockMillis = { nowMillis },
            maxActiveBytes = 4_096L,
            archiveCount = 1,
            maxAgeMillis = 2_000L,
        )
        // Planted after construction so the constructor's sweep cannot remove it.
        val expiredArchive = File(directory, "diagnostics.1.jsonl")
        expiredArchive.writeText("expired")
        expiredArchive.setLastModified(1_000L)

        nowMillis += RotatingFileDiagnosticSink.PRUNE_INTERVAL_MILLIS - 1L
        sink.append(sequencedEntry(0))
        assertTrue(expiredArchive.exists())

        nowMillis += 1L
        sink.append(sequencedEntry(1))
        assertFalse(expiredArchive.exists())
    }

    @Test
    fun rotationPrunesRegardlessOfInterval() {
        val directory = temporaryFolder.newFolder("rotation-prune")
        val sink = RotatingFileDiagnosticSink(
            directory = directory,
            clockMillis = { 10_000L },
            maxActiveBytes = 256L,
            archiveCount = 2,
            maxAgeMillis = 2_000L,
        )
        // Highest archive slot: rotation never shifts a file into it here, so only
        // a prune sweep can delete it. The clock never advances, so the append
        // interval gate stays closed for the whole test.
        val expiredArchive = File(directory, "diagnostics.2.jsonl")
        expiredArchive.writeText("expired")
        expiredArchive.setLastModified(1_000L)

        sink.append(sequencedEntry(0))
        assertTrue(expiredArchive.exists())

        // The second entry overflows maxActiveBytes, forcing a rotation that must
        // sweep unconditionally.
        sink.append(sequencedEntry(1))
        assertFalse(expiredArchive.exists())
    }

    /** Entry whose encoded form is roughly 240 bytes, so two of them overflow a 256-byte cap. */
    private fun sequencedEntry(sequence: Int): DiagnosticEntry = DiagnosticEntry(
        timestamp = Instant.ofEpochSecond(sequence.toLong()),
        level = DiagnosticLevel.INFO,
        event = DiagnosticEventCode.RUNTIME_SNAPSHOT,
        fields = mapOf("sequence" to sequence, "detail" to "x".repeat(120)),
    )
}
