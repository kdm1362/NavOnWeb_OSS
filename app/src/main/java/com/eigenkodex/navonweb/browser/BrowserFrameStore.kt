/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.eigenkodex.navonweb.browser

import java.io.Closeable
import java.io.OutputStream
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * A bounded, latest-only JPEG hand-off between projection capture and browsers.
 *
 * The store retains exactly one encoded frame. Subscribers wait by version, so
 * slow or disconnected browsers cannot build an unbounded video backlog.
 */
class BrowserFrameStore(
    private val maxFrameBytes: Int = DEFAULT_MAX_FRAME_BYTES,
) : Closeable {
    private val lock = ReentrantLock()
    private val changed = lock.newCondition()
    private var latestFrame: BrowserFrameSnapshot? = null
    private var nextVersion = 1L
    private var subscriberCount = 0
    private var closed = false
    private var subscriberListener: ((Int) -> Unit)? = null

    init {
        require(maxFrameBytes in MIN_FRAME_BYTES..MAX_ALLOWED_FRAME_BYTES) {
            "invalid browser frame size limit"
        }
    }

    /** Acquires a counted browser subscription. The returned lease is idempotent. */
    fun subscribe(): BrowserFrameSubscription {
        val listener: ((Int) -> Unit)?
        val count: Int
        lock.withLock {
            check(!closed) { "browser frame store is closed" }
            subscriberCount += 1
            count = subscriberCount
            listener = subscriberListener
        }
        listener?.invoke(count)
        return BrowserFrameSubscription(this)
    }

    /** Returns the current immutable snapshot without waiting. */
    fun latest(): BrowserFrameSnapshot? = lock.withLock { latestFrame }

    /**
     * Waits until a frame newer than [afterVersion] exists, or returns null on
     * timeout/close. Multiple consumers may wait independently.
     */
    @Throws(InterruptedException::class)
    fun awaitNext(afterVersion: Long, timeoutMillis: Long): BrowserFrameSnapshot? {
        require(afterVersion >= 0L) { "invalid browser frame version" }
        require(timeoutMillis >= 0L) { "invalid browser frame timeout" }

        return lock.withLock {
            var remainingNanos = TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
            while (!closed) {
                latestFrame?.takeIf { it.version > afterVersion }?.let { return@withLock it }
                if (remainingNanos <= 0L) return@withLock null
                remainingNanos = changed.awaitNanos(remainingNanos)
            }
            null
        }
    }

    /**
     * Publishes a defensive copy. Invalid/non-JPEG or oversized frames are
     * rejected without replacing the last known-good snapshot.
     */
    fun publish(jpegBytes: ByteArray, capturedAtNanos: Long = System.nanoTime()): Boolean =
        publishInternal(jpegBytes.copyOf(), capturedAtNanos)

    /** Transfers ownership of a freshly encoded array from the capture path. */
    internal fun publishOwned(
        jpegBytes: ByteArray,
        capturedAtNanos: Long = System.nanoTime(),
    ): Boolean = publishInternal(jpegBytes, capturedAtNanos)

    /** Drops a stale image while keeping current subscriptions alive. */
    fun clear() {
        lock.withLock {
            latestFrame = null
            changed.signalAll()
        }
    }

    val activeSubscriberCount: Int
        get() = lock.withLock { subscriberCount }

    internal fun setSubscriberListener(listener: ((Int) -> Unit)?) {
        val count = lock.withLock {
            subscriberListener = listener
            subscriberCount
        }
        listener?.invoke(count)
    }

    /** Clears only the listener installed by the closing producer. */
    internal fun clearSubscriberListener(expected: (Int) -> Unit) {
        lock.withLock {
            if (subscriberListener === expected) subscriberListener = null
        }
    }

    override fun close() {
        val listener: ((Int) -> Unit)?
        lock.withLock {
            if (closed) return
            closed = true
            latestFrame = null
            subscriberCount = 0
            listener = subscriberListener
            subscriberListener = null
            changed.signalAll()
        }
        listener?.invoke(0)
    }

    private fun publishInternal(jpegBytes: ByteArray, capturedAtNanos: Long): Boolean {
        if (capturedAtNanos < 0L || !isCompleteJpeg(jpegBytes) || jpegBytes.size > maxFrameBytes) {
            return false
        }
        lock.withLock {
            if (closed) return false
            if (nextVersion == Long.MAX_VALUE) {
                // A monotonic version can no longer be represented safely.
                return false
            }
            latestFrame = BrowserFrameSnapshot(
                version = nextVersion++,
                capturedAtNanos = capturedAtNanos,
                jpegBytes = jpegBytes,
            )
            changed.signalAll()
        }
        return true
    }

    private fun releaseSubscriber() {
        val listener: ((Int) -> Unit)?
        val count: Int
        lock.withLock {
            if (closed || subscriberCount == 0) return
            subscriberCount -= 1
            count = subscriberCount
            listener = subscriberListener
        }
        listener?.invoke(count)
    }

    class BrowserFrameSubscription internal constructor(
        private val owner: BrowserFrameStore,
    ) : Closeable {
        private val released = AtomicBoolean(false)

        override fun close() {
            if (released.compareAndSet(false, true)) owner.releaseSubscriber()
        }
    }

    companion object {
        const val DEFAULT_MAX_FRAME_BYTES = 1024 * 1024
        private const val MIN_FRAME_BYTES = 4
        private const val MAX_ALLOWED_FRAME_BYTES = 4 * 1024 * 1024

        fun isCompleteJpeg(bytes: ByteArray): Boolean =
            bytes.size >= MIN_FRAME_BYTES &&
                bytes[0] == 0xFF.toByte() &&
                bytes[1] == 0xD8.toByte() &&
                bytes[bytes.lastIndex - 1] == 0xFF.toByte() &&
                bytes[bytes.lastIndex] == 0xD9.toByte()
    }
}

/** Read-only JPEG snapshot shared by any number of browser writers. */
class BrowserFrameSnapshot internal constructor(
    val version: Long,
    val capturedAtNanos: Long,
    private val jpegBytes: ByteArray,
) {
    val byteCount: Int
        get() = jpegBytes.size

    fun copyJpegBytes(): ByteArray = jpegBytes.copyOf()

    fun writeJpegTo(output: OutputStream) {
        output.write(jpegBytes)
    }
}
