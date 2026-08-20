/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.eigenkodex.navonweb.session

enum class PairingCodeDecision {
    ACCEPTED,
    INVALID,
    EXPIRED,
    LOCKED,
}

data class PairingCodeVerification(
    val decision: PairingCodeDecision,
    /** A fresh code generated because the submitted code was accepted or expired. */
    val replacementCode: String? = null,
)

/** One locally generated code and its fixed process-monotonic expiry deadline. */
data class PairingCodeLease(
    val code: String,
    val expiresAtMonotonicMillis: Long,
)

class PairingCodeGate(
    expectedCode: String,
    private val nowMillis: () -> Long = { System.nanoTime() / 1_000_000L },
    private val lifetimeMillis: Long = DEFAULT_LIFETIME_MILLIS,
    private val maxFailures: Int = DEFAULT_MAX_FAILURES,
    private val lockMillis: Long = DEFAULT_LOCK_MILLIS,
    private val codeFactory: () -> String = PairingToken::create,
) {
    private var expectedCode = expectedCode
    private var createdAtMillis = nowMillis()
    private var failures = 0
    private var lockedUntilMillis = 0L

    init {
        require(isWellFormedCode(expectedCode))
        require(lifetimeMillis > 0)
        require(maxFailures > 0)
        require(lockMillis > 0)
    }

    @Synchronized
    fun currentCode(): String = expectedCode

    @Synchronized
    fun currentLease(): PairingCodeLease = PairingCodeLease(
        code = expectedCode,
        expiresAtMonotonicMillis = saturatingAdd(createdAtMillis, lifetimeMillis),
    )

    /** Rotates an unused code at its monotonic ten-minute deadline. */
    @Synchronized
    fun rotateIfExpired(): String? {
        val now = nowMillis()
        return if (hasExpired(now)) rotate(now) else null
    }

    /** Rotates immediately when the cloud bootstrap reports a same-network code collision. */
    @Synchronized
    fun rotateAfterBootstrapConflict(): String = rotate(nowMillis())

    /** Starts an explicitly requested pairing window with a fresh full-lifetime code. */
    @Synchronized
    fun rotateForNewPairingRequest(): String = rotate(nowMillis())

    /**
     * Verifies a one-time code and atomically replaces it when accepted.
     *
     * Expired submissions also trigger a replacement so the phone never keeps
     * showing a permanently unusable code. Browser credentials are deliberately
     * outside this gate and remain valid across every code generation.
     */
    @Synchronized
    fun verifyAndRotate(candidate: String?): PairingCodeVerification {
        val now = nowMillis()
        if (hasExpired(now)) {
            return PairingCodeVerification(
                decision = PairingCodeDecision.EXPIRED,
                replacementCode = rotate(now),
            )
        }
        if (now < lockedUntilMillis) {
            return PairingCodeVerification(PairingCodeDecision.LOCKED)
        }

        if (PairingToken.isValid(candidate, expectedCode)) {
            return PairingCodeVerification(
                decision = PairingCodeDecision.ACCEPTED,
                replacementCode = rotate(now),
            )
        }

        failures += 1
        if (failures >= maxFailures) {
            failures = 0
            lockedUntilMillis = saturatingAdd(now, lockMillis)
            return PairingCodeVerification(PairingCodeDecision.LOCKED)
        }
        return PairingCodeVerification(PairingCodeDecision.INVALID)
    }

    private fun hasExpired(now: Long): Boolean =
        now >= createdAtMillis && now - createdAtMillis >= lifetimeMillis

    private fun rotate(now: Long): String {
        val previousCode = expectedCode
        val replacementCode = generateDistinctCode(previousCode)
        expectedCode = replacementCode
        createdAtMillis = now
        failures = 0
        lockedUntilMillis = 0L
        return replacementCode
    }

    private fun generateDistinctCode(previousCode: String): String {
        repeat(MAX_CODE_GENERATION_ATTEMPTS) {
            val candidate = codeFactory()
            require(isWellFormedCode(candidate)) { "pairing code factory returned an invalid code" }
            if (candidate != previousCode) return candidate
        }
        throw IllegalStateException("pairing code factory did not produce a fresh code")
    }

    private fun saturatingAdd(left: Long, right: Long): Long =
        if (left > Long.MAX_VALUE - right) Long.MAX_VALUE else left + right

    private fun isWellFormedCode(code: String): Boolean =
        code.length == PAIRING_CODE_LENGTH && code.all { it in '0'..'9' }

    private companion object {
        const val PAIRING_CODE_LENGTH = 8
        const val MAX_CODE_GENERATION_ATTEMPTS = 16
        const val DEFAULT_LIFETIME_MILLIS = 10 * 60 * 1_000L
        const val DEFAULT_MAX_FAILURES = 5
        const val DEFAULT_LOCK_MILLIS = 30_000L
    }
}
