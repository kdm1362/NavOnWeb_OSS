/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.eigenkodex.navonweb.browser.cloud

import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class CloudRelayIdentityTest {
    @Test
    fun roomIdIsStableOpaqueDigestPrefix() {
        val secret = "A".repeat(43)
        val first = CloudRelayIdentity.fromSecret(secret)
        val second = CloudRelayIdentity.fromSecret(secret)

        assertEquals(first.roomId, second.roomId)
        assertEquals(22, first.roomId.length)
        assertNotEquals(secret.take(22), first.roomId)
    }

    @Test
    fun generatedIdentityHasExpectedEntropyEncoding() {
        val generated = CloudRelayIdentity.generate()

        assertEquals(43, generated.deviceSecret.length)
        assertEquals(22, generated.roomId.length)
        assertTrue(generated.deviceSecret.all { it.isLetterOrDigit() || it == '-' || it == '_' })
    }

    @Test
    fun publicationEpochPersistsAcrossAllocatorRecreation() {
        var persisted = 0L
        fun recreatedAllocator() = PairingPublicationEpochAllocator(
            readCurrent = { persisted },
            commitNext = { next ->
                persisted = next
                true
            },
        )

        assertEquals(1L, recreatedAllocator().next())
        assertEquals(2L, recreatedAllocator().next())
        assertEquals(2L, persisted)
    }

    @Test
    fun allocatorInstancesSharingTheProcessMonitorCannotReuseAnEpoch() {
        var persisted = 0L
        val processMonitor = Any()
        val start = CountDownLatch(1)
        val results = LongArray(2)
        val allocators = List(2) {
            PairingPublicationEpochAllocator(
                readCurrent = { persisted },
                commitNext = { next ->
                    persisted = next
                    true
                },
                monitor = processMonitor,
            )
        }
        val threads = allocators.mapIndexed { index, allocator ->
            thread {
                start.await()
                results[index] = allocator.next()
            }
        }

        start.countDown()
        threads.forEach(Thread::join)
        assertEquals(listOf(1L, 2L), results.sorted())
        assertEquals(2L, persisted)
    }

    @Test
    fun publicationEpochNeverWrapsOrRecoversCorruptionSilently() {
        assertEquals(1L, PairingPublicationEpochCounter.next(0L))
        assertEquals(
            MAX_SAFE_PAIRING_PUBLICATION_EPOCH,
            PairingPublicationEpochCounter.next(MAX_SAFE_PAIRING_PUBLICATION_EPOCH - 1L),
        )
        for (invalid in listOf(-1L, MAX_SAFE_PAIRING_PUBLICATION_EPOCH)) {
            try {
                PairingPublicationEpochCounter.next(invalid)
                fail("epoch $invalid should fail closed")
            } catch (_: IllegalStateException) {
                // Expected: an operator must deliberately rotate the whole identity.
            }
        }
    }
}
