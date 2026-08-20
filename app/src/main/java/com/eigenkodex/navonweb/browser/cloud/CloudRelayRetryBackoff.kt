/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.eigenkodex.navonweb.browser.cloud

import kotlin.math.roundToLong
import kotlin.random.Random

/** Exponential backoff with bounded jitter to avoid a fleet-wide reconnect wave. */
internal object CloudRelayRetryBackoff {
    fun randomizedDelayMillis(
        attempt: Int,
        baseMillis: Long,
        maxMillis: Long,
    ): Long = delayMillis(
        attempt = attempt,
        baseMillis = baseMillis,
        maxMillis = maxMillis,
        jitterUnit = Random.Default.nextDouble(from = -1.0, until = 1.0),
    )

    /** [jitterUnit] is injectable for deterministic JVM tests and must be in -1.0..1.0. */
    fun delayMillis(
        attempt: Int,
        baseMillis: Long,
        maxMillis: Long,
        jitterUnit: Double,
    ): Long {
        require(attempt >= 0)
        require(baseMillis > 0L && maxMillis >= baseMillis)
        require(jitterUnit in -1.0..1.0)

        var nominal = baseMillis
        repeat(attempt.coerceAtMost(MAX_DOUBLING_STEPS)) {
            nominal = if (nominal > maxMillis / 2L) maxMillis else nominal * 2L
        }
        nominal = nominal.coerceAtMost(maxMillis)
        val jittered = nominal + (nominal * JITTER_FRACTION * jitterUnit).roundToLong()
        return jittered.coerceIn(baseMillis, maxMillis)
    }

    private const val JITTER_FRACTION = 0.2
    private const val MAX_DOUBLING_STEPS = 62
}
