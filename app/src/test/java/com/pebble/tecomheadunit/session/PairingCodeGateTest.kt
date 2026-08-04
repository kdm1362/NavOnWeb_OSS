package com.pebble.tecomheadunit.session

import java.util.ArrayDeque
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class PairingCodeGateTest {
    @Test
    fun acceptsExactCodeAndImmediatelyRotates() {
        val replacements = ArrayDeque(listOf("65432109", "11111111"))
        val gate = PairingCodeGate(
            expectedCode = "01234567",
            codeFactory = replacements::removeFirst,
        )

        val accepted = gate.verifyAndRotate("01234567")

        assertEquals(PairingCodeDecision.ACCEPTED, accepted.decision)
        assertEquals("65432109", accepted.replacementCode)
        assertEquals("65432109", gate.currentCode())
        assertEquals(PairingCodeDecision.INVALID, gate.verifyAndRotate("01234567").decision)

        val nextAccepted = gate.verifyAndRotate("65432109")
        assertEquals(PairingCodeDecision.ACCEPTED, nextAccepted.decision)
        assertEquals("11111111", nextAccepted.replacementCode)
    }

    @Test
    fun rejectsWrongCode() {
        val gate = PairingCodeGate("01234567")

        assertEquals(PairingCodeDecision.INVALID, gate.verifyAndRotate("99999999").decision)
        assertEquals(PairingCodeDecision.INVALID, gate.verifyAndRotate(null).decision)
        assertEquals("01234567", gate.currentCode())
    }

    @Test
    fun expiredSubmissionRotatesBeforeReturningExpired() {
        var now = 1_000L
        val replacements = ArrayDeque(listOf("65432109", "11111111"))
        val gate = PairingCodeGate(
            expectedCode = "01234567",
            nowMillis = { now },
            lifetimeMillis = 100L,
            codeFactory = replacements::removeFirst,
        )

        now = 1_100L
        val expired = gate.verifyAndRotate("01234567")

        assertEquals(PairingCodeDecision.EXPIRED, expired.decision)
        assertEquals("65432109", expired.replacementCode)
        assertEquals("65432109", gate.currentCode())
    }

    @Test
    fun unusedCodeRotatesOnceAtEachDeadline() {
        var now = 1_000L
        val replacements = ArrayDeque(listOf("65432109", "11111111"))
        val gate = PairingCodeGate(
            expectedCode = "01234567",
            nowMillis = { now },
            codeFactory = replacements::removeFirst,
        )

        now = 600_999L
        assertNull(gate.rotateIfExpired())
        now = 601_000L
        assertEquals("65432109", gate.rotateIfExpired())
        assertNull(gate.rotateIfExpired())
        now = 1_200_999L
        assertNull(gate.rotateIfExpired())
        now = 1_201_000L
        assertEquals("11111111", gate.rotateIfExpired())
    }

    @Test
    fun bootstrapConflictImmediatelyRotatesAndInvalidatesPreviousCode() {
        val gate = PairingCodeGate(
            expectedCode = "01234567",
            codeFactory = { "65432109" },
        )

        assertEquals("65432109", gate.rotateAfterBootstrapConflict())
        assertEquals("65432109", gate.currentCode())
        assertEquals(PairingCodeDecision.INVALID, gate.verifyAndRotate("01234567").decision)
    }

    @Test
    fun explicitPairingRequestStartsWithAFreshLifetime() {
        var now = 1_000L
        val replacements = ArrayDeque(listOf("65432109", "11111111"))
        val gate = PairingCodeGate(
            expectedCode = "01234567",
            nowMillis = { now },
            lifetimeMillis = 100L,
            codeFactory = replacements::removeFirst,
        )

        now = 1_099L
        assertEquals("65432109", gate.rotateForNewPairingRequest())
        assertEquals(
            PairingCodeLease("65432109", 1_199L),
            gate.currentLease(),
        )
        now = 1_198L
        assertNull(gate.rotateIfExpired())
        now = 1_199L
        assertEquals(PairingCodeDecision.EXPIRED, gate.verifyAndRotate("65432109").decision)
    }

    @Test
    fun locksAfterRepeatedFailuresAndRecovers() {
        var now = 1_000L
        val gate = PairingCodeGate(
            expectedCode = "01234567",
            nowMillis = { now },
            maxFailures = 2,
            lockMillis = 50L,
            codeFactory = { "65432109" },
        )

        assertEquals(PairingCodeDecision.INVALID, gate.verifyAndRotate("99999999").decision)
        assertEquals(PairingCodeDecision.LOCKED, gate.verifyAndRotate("99999999").decision)
        assertEquals(PairingCodeDecision.LOCKED, gate.verifyAndRotate("01234567").decision)
        now += 50L
        assertEquals(PairingCodeDecision.ACCEPTED, gate.verifyAndRotate("01234567").decision)
        assertEquals("65432109", gate.currentCode())
    }

    @Test
    fun expiryRotationClearsAnExistingFailureLock() {
        var now = 1_000L
        val replacements = ArrayDeque(listOf("65432109", "11111111"))
        val gate = PairingCodeGate(
            expectedCode = "01234567",
            nowMillis = { now },
            lifetimeMillis = 100L,
            maxFailures = 1,
            lockMillis = 10_000L,
            codeFactory = replacements::removeFirst,
        )

        assertEquals(PairingCodeDecision.LOCKED, gate.verifyAndRotate("99999999").decision)
        now = 1_100L
        assertEquals("65432109", gate.rotateIfExpired())
        val accepted = gate.verifyAndRotate("65432109")
        assertEquals(PairingCodeDecision.ACCEPTED, accepted.decision)
        assertEquals("11111111", accepted.replacementCode)
    }

    @Test
    fun concurrentSubmissionsAcceptTheOneTimeCodeExactlyOnce() {
        val generatedCodeCount = AtomicInteger()
        val gate = PairingCodeGate(
            expectedCode = "01234567",
            codeFactory = {
                generatedCodeCount.incrementAndGet()
                "65432109"
            },
        )
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)

        try {
            val decisions = List(2) {
                executor.submit<PairingCodeDecision> {
                    start.await()
                    gate.verifyAndRotate("01234567").decision
                }
            }
            start.countDown()
            val results = decisions.map { it.get() }

            assertEquals(1, results.count { it == PairingCodeDecision.ACCEPTED })
            assertEquals(1, results.count { it == PairingCodeDecision.INVALID })
            assertEquals(1, generatedCodeCount.get())
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun acceptsOnlyAsciiDigitsForGeneratedCodes() {
        assertThrows(IllegalArgumentException::class.java) {
            PairingCodeGate("012345")
        }
        assertThrows(IllegalArgumentException::class.java) {
            PairingCodeGate("１２３４５６７８")
        }

        val gate = PairingCodeGate(
            expectedCode = "01234567",
            codeFactory = { "１２３４５６７８" },
        )
        assertThrows(IllegalArgumentException::class.java) {
            gate.verifyAndRotate("01234567")
        }
    }
}
