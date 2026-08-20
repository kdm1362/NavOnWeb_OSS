/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.eigenkodex.navonweb.browser

/** Handler-thread-confined fixed-rate gate, separated for JVM verification. */
internal class BrowserCaptureRateLimiter(
    private val intervalMillis: Long,
) {
    private var nextAllowedAtMillis = 0L

    init {
        require(intervalMillis > 0L) { "invalid browser capture interval" }
    }

    fun markRequestStarted(nowMillis: Long) {
        require(nowMillis >= 0L) { "invalid monotonic time" }
        nextAllowedAtMillis = if (nowMillis > Long.MAX_VALUE - intervalMillis) {
            Long.MAX_VALUE
        } else {
            nowMillis + intervalMillis
        }
    }

    fun remainingDelayMillis(nowMillis: Long): Long {
        require(nowMillis >= 0L) { "invalid monotonic time" }
        if (nowMillis >= nextAllowedAtMillis) return 0L
        return nextAllowedAtMillis - nowMillis
    }
}
