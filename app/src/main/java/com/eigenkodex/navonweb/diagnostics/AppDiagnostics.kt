/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.eigenkodex.navonweb.diagnostics

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Coarse, closed event codes. Raw protocol payloads must never be used as event names. */
enum class DiagnosticEventCode {
    PROCESS_STARTED,
    SERVICE_CREATED,
    SERVICE_DESTROYED,
    SESSION_START_REQUESTED,
    SESSION_START_REJECTED,
    SESSION_READY,
    SESSION_STOPPED,
    SESSION_FAILED,
    CONNECTION_CHANGED,
    RUNTIME_SNAPSHOT,
    PROJECTION_PROFILE_CHANGED,
    WEBRTC_CODEC_CHANGED,
    NIGHT_MODE_CHANGED,
    PROJECTION_SURFACE_CHANGED,
    DIAGNOSTIC_UPLOAD_RESULT,
    LOG_EVENTS_DROPPED,
}

enum class DiagnosticLevel {
    INFO,
    WARN,
    ERROR,
}

data class DiagnosticLogSummary(
    val fileCount: Int,
    val totalBytes: Long,
    val lastModifiedEpochMillis: Long?,
) {
    companion object {
        val EMPTY = DiagnosticLogSummary(0, 0L, null)
    }
}

/**
 * Process-wide, bounded diagnostic recorder.
 *
 * It deliberately accepts only a closed event code and scalar fields. The final
 * sink independently rejects secret-bearing field names and payload markers.
 */
object AppDiagnostics {
    private val initializationLock = Any()
    private val droppedEvents = AtomicInteger(0)
    private val executor = ThreadPoolExecutor(
        1,
        1,
        0L,
        TimeUnit.MILLISECONDS,
        ArrayBlockingQueue(EVENT_QUEUE_CAPACITY),
        { runnable -> Thread(runnable, "navonweb-diagnostic-log").apply { isDaemon = true } },
        ThreadPoolExecutor.AbortPolicy(),
    )

    @Volatile
    private var sink: RotatingFileDiagnosticSink? = null

    fun initialize(context: Context) {
        var initialized = false
        synchronized(initializationLock) {
            if (sink == null) {
                sink = RotatingFileDiagnosticSink(
                    directory = File(context.applicationContext.noBackupFilesDir, LOG_DIRECTORY_NAME),
                )
                initialized = true
            }
        }
        if (initialized) record(DiagnosticEventCode.PROCESS_STARTED)
    }

    fun record(
        event: DiagnosticEventCode,
        level: DiagnosticLevel = DiagnosticLevel.INFO,
        fields: Map<String, Any?> = emptyMap(),
    ) {
        val target = sink ?: return
        try {
            executor.execute {
                val dropped = droppedEvents.getAndSet(0)
                if (dropped > 0) {
                    target.append(
                        DiagnosticEntry(
                            timestamp = Instant.now(),
                            level = DiagnosticLevel.WARN,
                            event = DiagnosticEventCode.LOG_EVENTS_DROPPED,
                            fields = mapOf("count" to dropped),
                        ),
                    )
                }
                target.append(DiagnosticEntry(Instant.now(), level, event, fields))
            }
        } catch (_: RejectedExecutionException) {
            droppedEvents.incrementAndGet()
        }
    }

    fun recordFailure(
        event: DiagnosticEventCode,
        error: Throwable,
        fields: Map<String, Any?> = emptyMap(),
    ) {
        record(
            event = event,
            level = DiagnosticLevel.ERROR,
            fields = fields + ("error_type" to error.javaClass.simpleName),
        )
    }

    fun summary(): DiagnosticLogSummary = sink?.summary() ?: DiagnosticLogSummary.EMPTY

    /** Call from an IO dispatcher. Waits for earlier queued records before exporting. */
    fun exportText(): String {
        val target = sink ?: return ""
        return executor.submit<String> { target.exportText() }.get(EXPORT_WAIT_SECONDS, TimeUnit.SECONDS)
    }

    /** Call from an IO dispatcher. Deletion is serialized after pending writes. */
    fun clear() {
        val target = sink ?: return
        executor.submit { target.clear() }.get(EXPORT_WAIT_SECONDS, TimeUnit.SECONDS)
    }

    internal fun resetForTest(testSink: RotatingFileDiagnosticSink?) {
        executor.submit { sink = testSink }.get(EXPORT_WAIT_SECONDS, TimeUnit.SECONDS)
    }

    private const val EVENT_QUEUE_CAPACITY = 512
    private const val EXPORT_WAIT_SECONDS = 5L
    private const val LOG_DIRECTORY_NAME = "diagnostics"
}

internal data class DiagnosticEntry(
    val timestamp: Instant,
    val level: DiagnosticLevel,
    val event: DiagnosticEventCode,
    val fields: Map<String, Any?>,
)

internal class RotatingFileDiagnosticSink(
    private val directory: File,
    private val clockMillis: () -> Long = System::currentTimeMillis,
    private val maxActiveBytes: Long = DEFAULT_MAX_ACTIVE_BYTES,
    private val archiveCount: Int = DEFAULT_ARCHIVE_COUNT,
    private val maxAgeMillis: Long = DEFAULT_MAX_AGE_MILLIS,
) {
    /**
     * Clock reading of the most recent [pruneExpired] sweep. The constructor's sweep
     * initializes it, so [append] re-stats file metadata at most once per
     * [PRUNE_INTERVAL_MILLIS]; [rotate] always sweeps regardless of this marker.
     */
    private var lastPruneAtMillis = 0L

    init {
        require(maxActiveBytes >= MINIMUM_LOG_FILE_BYTES)
        require(archiveCount >= 0)
        pruneExpired()
    }

    @Synchronized
    fun append(entry: DiagnosticEntry) {
        if (!directory.exists() && !directory.mkdirs()) return
        // The expiry sweep stats every log file; appends are frequent (connection,
        // touch, video paths), so only sweep once the interval has elapsed.
        // A backward wall-clock jump must not park the sweep until the clock re-passes the
        // old marker, so a negative delta also triggers (and re-anchors) the prune.
        val sincePrune = clockMillis() - lastPruneAtMillis
        if (sincePrune >= PRUNE_INTERVAL_MILLIS || sincePrune < 0L) pruneExpired()
        val encoded = DiagnosticEntryEncoder.encode(entry)
        val bytes = encoded.toByteArray(StandardCharsets.UTF_8)
        val active = activeFile()
        if (active.exists() && active.length() + bytes.size > maxActiveBytes) rotate()
        runCatching {
            FileOutputStream(activeFile(), true).use { output ->
                output.write(bytes)
                output.flush()
            }
        }
    }

    @Synchronized
    fun summary(): DiagnosticLogSummary {
        val files = logFiles().filter(File::isFile)
        return DiagnosticLogSummary(
            fileCount = files.size,
            totalBytes = files.sumOf(File::length),
            lastModifiedEpochMillis = files.maxOfOrNull(File::lastModified),
        )
    }

    @Synchronized
    fun exportText(): String = buildString {
        orderedOldestFirst().filter(File::isFile).forEach { file ->
            file.bufferedReader(StandardCharsets.UTF_8).use { reader -> append(reader.readText()) }
        }
    }

    @Synchronized
    fun clear() {
        logFiles().forEach { file -> runCatching { if (file.exists()) file.delete() } }
    }

    @Synchronized
    private fun rotate() {
        // Rotation is rare and already pays file-system costs, so sweep expired
        // files unconditionally before shifting archives.
        pruneExpired()
        if (archiveCount == 0) {
            runCatching { activeFile().delete() }
            return
        }
        for (index in archiveCount downTo 1) {
            val source = if (index == 1) activeFile() else archiveFile(index - 1)
            val destination = archiveFile(index)
            if (!source.exists()) continue
            runCatching {
                if (destination.exists()) destination.delete()
                if (!source.renameTo(destination)) {
                    source.copyTo(destination, overwrite = true)
                    source.delete()
                }
            }
        }
    }

    @Synchronized
    private fun pruneExpired() {
        val now = clockMillis()
        val cutoff = now - maxAgeMillis
        logFiles().forEach { file ->
            if (file.isFile && file.lastModified() in 1 until cutoff) {
                runCatching { file.delete() }
            }
        }
        lastPruneAtMillis = now
    }

    private fun activeFile(): File = File(directory, ACTIVE_FILE_NAME)

    private fun archiveFile(index: Int): File = File(directory, "diagnostics.$index.jsonl")

    private fun logFiles(): List<File> =
        listOf(activeFile()) + (1..archiveCount).map(::archiveFile)

    private fun orderedOldestFirst(): List<File> =
        (archiveCount downTo 1).map(::archiveFile) + activeFile()

    internal companion object {
        const val DEFAULT_MAX_ACTIVE_BYTES = 1_048_576L
        const val DEFAULT_ARCHIVE_COUNT = 3
        const val TOTAL_MAX_BYTES = DEFAULT_MAX_ACTIVE_BYTES * (DEFAULT_ARCHIVE_COUNT + 1)
        const val DEFAULT_MAX_AGE_MILLIS = 7L * 24L * 60L * 60L * 1_000L

        /** Minimum spacing between the metadata sweeps that [append] may trigger. */
        const val PRUNE_INTERVAL_MILLIS = 5L * 60L * 1_000L
        const val MINIMUM_LOG_FILE_BYTES = 256L
        const val ACTIVE_FILE_NAME = "diagnostics.jsonl"
    }
}

internal object DiagnosticEntryEncoder {
    fun encode(entry: DiagnosticEntry): String {
        val safeFields = entry.fields.entries
            .asSequence()
            .filterNot { DiagnosticRedactor.isForbiddenFieldName(it.key) }
            .mapNotNull { (key, value) ->
                val safeKey = DiagnosticRedactor.safeFieldName(key) ?: return@mapNotNull null
                safeKey to DiagnosticRedactor.redact(value?.toString().orEmpty())
            }
            .take(MAX_FIELDS)
            .toList()
        return buildString {
            append("{\"timestamp\":\"")
            append(jsonEscape(DateTimeFormatter.ISO_INSTANT.format(entry.timestamp)))
            append("\",\"level\":\"")
            append(entry.level.name)
            append("\",\"event\":\"")
            append(entry.event.name)
            append("\",\"fields\":{")
            safeFields.forEachIndexed { index, (key, value) ->
                if (index > 0) append(',')
                append('"').append(jsonEscape(key)).append("\":\"")
                append(jsonEscape(value)).append('"')
            }
            append("}}\n")
        }
    }

    private fun jsonEscape(value: String): String = buildString(value.length) {
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
    }

    private const val MAX_FIELDS = 12
}

internal object DiagnosticRedactor {
    fun isForbiddenFieldName(name: String): Boolean =
        FORBIDDEN_FIELD_NAME.containsMatchIn(name.trim().lowercase())

    fun safeFieldName(name: String): String? = name
        .trim()
        .lowercase()
        .takeIf { SAFE_FIELD_NAME.matches(it) }

    fun redact(value: String): String {
        val singleLine = value
            .replace('\r', ' ')
            .replace('\n', ' ')
            .trim()
            .take(MAX_FIELD_VALUE_CHARACTERS)
        if (singleLine.isBlank()) return "none"
        val normalized = singleLine.lowercase()
        return if (
            FORBIDDEN_VALUE_MARKERS.any(normalized::contains) ||
            IPV4_ADDRESS.containsMatchIn(singleLine) ||
            IPV6_ADDRESS.containsMatchIn(singleLine) ||
            MDNS_HOST.containsMatchIn(singleLine) ||
            SIX_DIGIT_CODE.containsMatchIn(singleLine) ||
            SHA256_HEX.containsMatchIn(singleLine) ||
            URL.containsMatchIn(singleLine) ||
            COORDINATES.containsMatchIn(normalized)
        ) {
            REDACTED
        } else {
            singleLine
        }
    }

    private const val MAX_FIELD_VALUE_CHARACTERS = 160
    private const val REDACTED = "[redacted]"
    private val SAFE_FIELD_NAME = Regex("[a-z][a-z0-9_]{0,39}")
    private val FORBIDDEN_FIELD_NAME = Regex(
        "credential|secret|token|password|authorization|cookie|pairing|registration|" +
            "certificate|fingerprint|private|public_key|sdp|ice_|address|host|url|" +
            "(^|_)ip($|_)|location|latitude|longitude|ssid|bssid|serial|" +
            "touch_x|touch_y|pointer|timestamp_raw",
    )
    private val FORBIDDEN_VALUE_MARKERS = listOf(
        "authorization:",
        "bearer ",
        "browser credential",
        "browsercredential",
        "pairing code",
        "등록 코드",
        "-----begin ",
        "-----end ",
        "a=ice-ufrag",
        "a=ice-pwd",
        "candidate:",
        "a=fingerprint:",
        "m=audio ",
        "m=video ",
        "set-cookie:",
    )
    private val IPV4_ADDRESS = Regex("(?<![0-9])(?:[0-9]{1,3}\\.){3}[0-9]{1,3}(?![0-9])")
    private val IPV6_ADDRESS = Regex(
        "(?i)(?<![0-9a-f:])[0-9a-f]{0,4}(?::[0-9a-f]{0,4}){2,7}(?![0-9a-f:])",
    )
    private val MDNS_HOST = Regex("(?i)\\b[a-z0-9-]{1,63}\\.local\\b")
    private val SIX_DIGIT_CODE = Regex("(?<![0-9])[0-9]{6}(?![0-9])")
    private val SHA256_HEX = Regex("(?i)(?<![0-9a-f])[0-9a-f]{64}(?![0-9a-f])")
    private val URL = Regex("(?i)\\b(?:https?|wss?)://")
    private val COORDINATES = Regex("(?:lat|latitude|lon|lng|longitude)\\s*[=:]")
}
