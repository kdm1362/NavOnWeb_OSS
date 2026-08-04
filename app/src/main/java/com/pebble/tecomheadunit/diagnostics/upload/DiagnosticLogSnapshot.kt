/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.pebble.tecomheadunit.diagnostics.upload

import java.nio.charset.StandardCharsets
import java.util.ArrayDeque

internal data class DiagnosticLogSnapshot(
    val logText: String,
    val truncated: Boolean,
    val originalByteCount: Long,
) {
    val selectedByteCount: Int
        get() = logText.toByteArray(StandardCharsets.UTF_8).size
}

/** Selects the newest newline-terminated JSONL records without splitting UTF-8 or a record. */
internal object DiagnosticLogSnapshotter {
    fun newestCompleteRecords(
        exportedText: String,
        maxBytes: Int = DiagnosticUploadPolicy.MAX_LOG_BYTES,
    ): DiagnosticLogSnapshot {
        require(maxBytes > 0)
        val originalByteCount = exportedText.toByteArray(StandardCharsets.UTF_8).size.toLong()
        val completeEnd = exportedText.lastIndexOf('\n').let { lastNewline ->
            if (lastNewline < 0) 0 else lastNewline + 1
        }
        var cursor = completeEnd
        var selectedBytes = 0
        val selectedRecords = ArrayDeque<String>()

        while (cursor > 0) {
            val previousNewline = exportedText.lastIndexOf('\n', cursor - 2)
            val recordStart = previousNewline + 1
            val record = exportedText.substring(recordStart, cursor)
            val recordBytes = record.toByteArray(StandardCharsets.UTF_8).size
            if (record.isNotBlank()) {
                if (recordBytes > maxBytes || selectedBytes + recordBytes > maxBytes) break
                selectedRecords.addFirst(record)
                selectedBytes += recordBytes
            }
            cursor = recordStart
        }

        val selected = buildString(selectedBytes) {
            selectedRecords.forEach(::append)
        }
        return DiagnosticLogSnapshot(
            logText = selected,
            truncated = selectedBytes.toLong() < originalByteCount,
            originalByteCount = originalByteCount,
        )
    }
}
