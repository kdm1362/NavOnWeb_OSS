package com.pebble.tecomheadunit.service

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserSessionControllerSwapTest {
    @Test
    fun `mutation cannot return between delegate replacement and old controller retirement`() {
        val lock = Any()
        val old = Controller()
        val replacement = Controller()
        var current: Controller? = old
        var delegate: Controller? = old
        val retirementEntered = CountDownLatch(1)
        val releaseRetirement = CountDownLatch(1)
        val mutationReturned = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val swap = executor.submit<Boolean> {
                replaceBrowserSessionControllerAtomically(
                    lock = lock,
                    current = { current },
                    replacement = replacement,
                    replaceDelegate = { expected, next ->
                        if (delegate !== expected) {
                            false
                        } else {
                            delegate = next
                            true
                        }
                    },
                    updateCurrent = { current = it },
                    retire = { previous ->
                        retirementEntered.countDown()
                        assertTrue(releaseRetirement.await(2, TimeUnit.SECONDS))
                        previous.retired = true
                    },
                )
            }
            assertTrue(retirementEntered.await(2, TimeUnit.SECONDS))
            val mutation = executor.submit {
                synchronized(lock) {
                    assertTrue(old.retired)
                    mutationReturned.countDown()
                }
            }
            assertFalse(mutationReturned.await(100, TimeUnit.MILLISECONDS))
            releaseRetirement.countDown()

            assertTrue(swap.get(2, TimeUnit.SECONDS))
            mutation.get(2, TimeUnit.SECONDS)
            assertSame(replacement, current)
            assertSame(replacement, delegate)
        } finally {
            releaseRetirement.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun `identity mismatch leaves current controller untouched`() {
        val lock = Any()
        val currentController = Controller()
        val unexpectedDelegate = Controller()
        val replacement = Controller()
        var current: Controller? = currentController
        var delegate: Controller? = unexpectedDelegate

        val replaced = replaceBrowserSessionControllerAtomically(
            lock = lock,
            current = { current },
            replacement = replacement,
            replaceDelegate = { expected, next ->
                if (delegate !== expected) {
                    false
                } else {
                    delegate = next
                    true
                }
            },
            updateCurrent = { current = it },
            retire = { it.retired = true },
        )

        assertFalse(replaced)
        assertSame(currentController, current)
        assertSame(unexpectedDelegate, delegate)
        assertFalse(currentController.retired)
    }

    private class Controller {
        var retired = false
    }
}
