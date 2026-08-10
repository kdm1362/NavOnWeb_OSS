/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.pebble.tecomheadunit.browser

import com.pebble.tecomheadunit.core.NormalizedTouch
import com.pebble.tecomheadunit.core.TouchPhase

internal enum class BrowserTouchGestureAdmission {
    ACCEPTED,
    BUSY,
    OUT_OF_SEQUENCE,
    RECOVERY_REJECTED,
}

internal data class BrowserTouchGestureSource(
    val ownerKey: String,
    val deviceId: String,
    val colorSlot: Int,
) {
    init {
        require(ownerKey.isNotBlank())
        require(deviceId.isNotBlank())
        require(colorSlot in 0..2)
    }
}

/**
 * Serializes the single Android Auto pointer across several controlling browsers. ownerKey is
 * kept only in memory and is never returned or logged. A lease recovers from a browser that
 * disappears between DOWN and UP.
 */
internal class BrowserTouchGestureGate(
    private val nowMillis: () -> Long,
    private val leaseMillis: Long = DEFAULT_LEASE_MILLIS,
) {
    private var activeSource: BrowserTouchGestureSource? = null
    private var lastEventMillis = 0L
    private var lastTouch: NormalizedTouch? = null

    init {
        require(leaseMillis > 0L)
    }

    @Synchronized
    fun tryAdmit(
        source: BrowserTouchGestureSource,
        touch: NormalizedTouch,
        cancelExpiredGesture: (
            source: BrowserTouchGestureSource,
            lastTouch: NormalizedTouch,
        ) -> Boolean =
            { _, _ -> false },
    ): BrowserTouchGestureAdmission {
        val now = nowMillis()
        val current = activeSource
        val expired = current != null && elapsed(now, lastEventMillis) >= leaseMillis
        if (expired) {
            val staleTouch = lastTouch
            val cancelled = staleTouch != null && runCatching {
                cancelExpiredGesture(checkNotNull(current), staleTouch)
            }.getOrDefault(false)
            if (!cancelled) return BrowserTouchGestureAdmission.RECOVERY_REJECTED
            clearLease()
        }

        return when (touch.phase) {
            TouchPhase.DOWN -> when {
                activeSource == null -> {
                    activeSource = source
                    lastEventMillis = now
                    lastTouch = touch
                    BrowserTouchGestureAdmission.ACCEPTED
                }
                activeSource?.ownerKey == source.ownerKey ->
                    BrowserTouchGestureAdmission.OUT_OF_SEQUENCE
                else -> BrowserTouchGestureAdmission.BUSY
            }
            TouchPhase.MOVE,
            TouchPhase.UP,
            TouchPhase.CANCEL,
            -> if (activeSource?.ownerKey == source.ownerKey) {
                activeSource = source
                lastEventMillis = now
                lastTouch = touch
                BrowserTouchGestureAdmission.ACCEPTED
            } else {
                BrowserTouchGestureAdmission.OUT_OF_SEQUENCE
            }
        }
    }

    @Synchronized
    fun complete(ownerKey: String, phase: TouchPhase, accepted: Boolean) {
        if (activeSource?.ownerKey != ownerKey) return
        val shouldRelease = when (phase) {
            TouchPhase.DOWN -> !accepted
            TouchPhase.MOVE -> false
            TouchPhase.UP,
            TouchPhase.CANCEL,
            -> accepted
        }
        if (shouldRelease) clearLease()
    }

    private fun clearLease() {
        activeSource = null
        lastEventMillis = 0L
        lastTouch = null
    }

    private fun elapsed(now: Long, then: Long): Long = (now - then).coerceAtLeast(0L)

    private companion object {
        const val DEFAULT_LEASE_MILLIS = 10_000L
    }
}
