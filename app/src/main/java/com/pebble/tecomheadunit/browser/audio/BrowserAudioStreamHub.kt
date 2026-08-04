/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.pebble.tecomheadunit.browser.audio

import com.pebble.tecomheadunit.audio.Pcm16Format
import java.io.Closeable
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

enum class BrowserAudioTrack(val wireName: String) {
    MEDIA("media"),
    SPEECH("speech"),
    SYSTEM("system"),
    ;

    companion object {
        fun fromWireName(value: String?): BrowserAudioTrack? =
            entries.firstOrNull { it.wireName == value?.trim()?.lowercase() }
    }
}

data class BrowserAudioTrackSnapshot(
    val format: Pcm16Format,
    val packetsPublished: Long,
    val bytesPublished: Long,
    val packetsDropped: Long,
    val activeSubscribers: Int,
    val lastPacketAtNanos: Long?,
)

/**
 * Bounded, fan-out PCM16 hub for authenticated browser clients.
 *
 * The publisher transfers ownership of each [ByteArray] to this hub and must not mutate it after
 * [publish] returns. Slow clients drop their oldest queued chunks instead of applying backpressure
 * to the Android Auto protocol thread.
 */
class BrowserAudioStreamHub(
    formats: Map<BrowserAudioTrack, Pcm16Format>,
    private val nanoTime: () -> Long = System::nanoTime,
) : Closeable {
    private val lock = Any()
    private val streams = BrowserAudioTrack.entries.associateWith { track ->
        StreamState(checkNotNull(formats[track]) { "missing browser audio format for $track" })
    }
    private var closed = false

    fun format(track: BrowserAudioTrack): Pcm16Format = streams.getValue(track).format

    fun publish(track: BrowserAudioTrack, pcm16LittleEndian: ByteArray): Boolean {
        val state = streams.getValue(track)
        val frameBytes = state.format.channelCount * PCM16_BYTES_PER_SAMPLE
        if (pcm16LittleEndian.isEmpty() || pcm16LittleEndian.size > MAX_CHUNK_BYTES ||
            pcm16LittleEndian.size % frameBytes != 0
        ) {
            return false
        }
        val subscribers: List<BrowserAudioSubscription>
        synchronized(lock) {
            if (closed) return false
            state.packetsPublished += 1L
            state.bytesPublished += pcm16LittleEndian.size
            state.lastPacketAtNanos = nanoTime()
            subscribers = state.subscribers.toList()
        }
        subscribers.forEach { subscriber ->
            if (!subscriber.offer(pcm16LittleEndian)) {
                synchronized(lock) { state.packetsDropped += 1L }
            }
        }
        return true
    }

    fun subscribe(track: BrowserAudioTrack): BrowserAudioSubscription? = synchronized(lock) {
        if (closed) return null
        val state = streams.getValue(track)
        BrowserAudioSubscription(
            track = track,
            format = state.format,
            onClose = { subscription ->
                synchronized(lock) { state.subscribers.remove(subscription) }
            },
        ).also(state.subscribers::add)
    }

    /** Ends active stream subscriptions while keeping the hub reusable for an AA reconnect. */
    fun reset() {
        val subscriptions = synchronized(lock) {
            streams.values.flatMap { it.subscribers }.also {
                streams.values.forEach { state -> state.subscribers.clear() }
            }
        }
        subscriptions.forEach(BrowserAudioSubscription::closeFromHub)
    }

    fun snapshot(): Map<BrowserAudioTrack, BrowserAudioTrackSnapshot> = synchronized(lock) {
        streams.mapValues { (_, state) ->
            BrowserAudioTrackSnapshot(
                format = state.format,
                packetsPublished = state.packetsPublished,
                bytesPublished = state.bytesPublished,
                packetsDropped = state.packetsDropped,
                activeSubscribers = state.subscribers.size,
                lastPacketAtNanos = state.lastPacketAtNanos,
            )
        }
    }

    override fun close() {
        val subscriptions = synchronized(lock) {
            if (closed) return
            closed = true
            streams.values.flatMap { it.subscribers }.also {
                streams.values.forEach { state -> state.subscribers.clear() }
            }
        }
        subscriptions.forEach(BrowserAudioSubscription::closeFromHub)
    }

    private class StreamState(val format: Pcm16Format) {
        val subscribers = linkedSetOf<BrowserAudioSubscription>()
        var packetsPublished = 0L
        var bytesPublished = 0L
        var packetsDropped = 0L
        var lastPacketAtNanos: Long? = null
    }

    companion object {
        private const val PCM16_BYTES_PER_SAMPLE = 2
        private const val MAX_CHUNK_BYTES = 256 * 1024
    }
}

class BrowserAudioSubscription internal constructor(
    val track: BrowserAudioTrack,
    val format: Pcm16Format,
    private val onClose: (BrowserAudioSubscription) -> Unit,
) : Closeable {
    private val queue = ArrayBlockingQueue<ByteArray>(MAX_QUEUED_CHUNKS)
    private val closeLock = Any()
    @Volatile
    var isClosed: Boolean = false
        private set

    /** Returns null on timeout or after close. Check [isClosed] to distinguish the two. */
    fun poll(timeoutMillis: Long): ByteArray? {
        require(timeoutMillis in 0L..MAX_POLL_MILLIS) { "invalid audio poll timeout" }
        if (isClosed) return null
        return queue.poll(timeoutMillis, TimeUnit.MILLISECONDS)
    }

    internal fun offer(chunk: ByteArray): Boolean {
        if (isClosed) return true
        if (queue.offer(chunk)) return true
        queue.poll()
        val accepted = queue.offer(chunk)
        // A false result records the oldest-chunk drop. If close raced the replacement enqueue,
        // there is no slow-client drop worth exposing in diagnostics.
        return if (!accepted && isClosed) true else false
    }

    override fun close() {
        val notify = synchronized(closeLock) {
            if (isClosed) false else {
                isClosed = true
                queue.clear()
                true
            }
        }
        if (notify) onClose(this)
    }

    internal fun closeFromHub() {
        synchronized(closeLock) {
            if (isClosed) return
            isClosed = true
            queue.clear()
        }
    }

    companion object {
        internal const val MAX_QUEUED_CHUNKS = 12
        private const val MAX_POLL_MILLIS = 10_000L
    }
}
