/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.pebble.tecomheadunit.browser

import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.CopyOnWriteArrayList
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserFrameStoreTest {
    @Test
    fun `retains only latest frame and increments version`() {
        val store = BrowserFrameStore(maxFrameBytes = 64)
        assertTrue(store.publish(jpeg(1), capturedAtNanos = 11L))
        assertTrue(store.publish(jpeg(2), capturedAtNanos = 22L))

        val latest = requireNotNull(store.latest())
        assertEquals(2L, latest.version)
        assertEquals(22L, latest.capturedAtNanos)
        assertArrayEquals(jpeg(2), latest.copyJpegBytes())
    }

    @Test
    fun `publish defensively copies caller array`() {
        val store = BrowserFrameStore(maxFrameBytes = 64)
        val source = jpeg(7)
        assertTrue(store.publish(source))
        source[2] = 99

        assertArrayEquals(jpeg(7), requireNotNull(store.latest()).copyJpegBytes())
    }

    @Test
    fun `rejects malformed and oversized frames without replacing good frame`() {
        val store = BrowserFrameStore(maxFrameBytes = 8)
        assertTrue(store.publish(jpeg(1)))
        val version = requireNotNull(store.latest()).version

        assertFalse(store.publish(byteArrayOf(1, 2, 3, 4)))
        assertFalse(store.publish(byteArrayOf(-1, -40, 1, 2, 3, 4, 5, -1, -39)))
        assertEquals(version, requireNotNull(store.latest()).version)
    }

    @Test
    fun `multiple consumers can await one newer version`() {
        val store = BrowserFrameStore(maxFrameBytes = 64)
        val executor = Executors.newFixedThreadPool(2)
        val ready = CountDownLatch(2)
        val futures = List(2) {
            executor.submit<BrowserFrameSnapshot?> {
                store.subscribe().use {
                    ready.countDown()
                    store.awaitNext(afterVersion = 0L, timeoutMillis = 2_000L)
                }
            }
        }
        assertTrue(ready.await(1, TimeUnit.SECONDS))
        assertEquals(2, store.activeSubscriberCount)

        assertTrue(store.publish(jpeg(9)))
        futures.forEach { future ->
            assertArrayEquals(jpeg(9), requireNotNull(future.get(1, TimeUnit.SECONDS)).copyJpegBytes())
        }
        executor.shutdownNow()
        assertEquals(0, store.activeSubscriberCount)
    }

    @Test
    fun `timeout and close wake return null`() {
        val store = BrowserFrameStore(maxFrameBytes = 64)
        assertNull(store.awaitNext(afterVersion = 0L, timeoutMillis = 0L))

        val executor = Executors.newSingleThreadExecutor()
        val future = executor.submit<BrowserFrameSnapshot?> {
            store.awaitNext(afterVersion = 0L, timeoutMillis = 5_000L)
        }
        store.close()
        assertNull(future.get(1, TimeUnit.SECONDS))
        executor.shutdownNow()
    }

    @Test
    fun `snapshot writes JPEG without exposing backing storage`() {
        val store = BrowserFrameStore(maxFrameBytes = 64)
        assertTrue(store.publish(jpeg(4)))
        val output = ByteArrayOutputStream()

        requireNotNull(store.latest()).writeJpegTo(output)

        assertArrayEquals(jpeg(4), output.toByteArray())
    }

    @Test
    fun `subscription listener observes idempotent lease lifecycle`() {
        val store = BrowserFrameStore(maxFrameBytes = 64)
        val counts = CopyOnWriteArrayList<Int>()
        store.setSubscriberListener(counts::add)

        val first = store.subscribe()
        val second = store.subscribe()
        first.close()
        first.close()
        second.close()

        assertEquals(listOf(0, 1, 2, 1, 0), counts.toList())
        assertEquals(0, store.activeSubscriberCount)
    }

    @Test
    fun `closing replaced producer cannot clear current subscriber listener`() {
        val store = BrowserFrameStore(maxFrameBytes = 64)
        val previousCounts = CopyOnWriteArrayList<Int>()
        val currentCounts = CopyOnWriteArrayList<Int>()
        val previous: (Int) -> Unit = previousCounts::add
        val current: (Int) -> Unit = currentCounts::add
        store.setSubscriberListener(previous)
        store.setSubscriberListener(current)

        store.clearSubscriberListener(previous)
        store.subscribe().close()

        assertEquals(listOf(0), previousCounts.toList())
        assertEquals(listOf(0, 1, 0), currentCounts.toList())
    }

    private fun jpeg(value: Int): ByteArray = byteArrayOf(
        0xFF.toByte(),
        0xD8.toByte(),
        value.toByte(),
        0xFF.toByte(),
        0xD9.toByte(),
    )
}
