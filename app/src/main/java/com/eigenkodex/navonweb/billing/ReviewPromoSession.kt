/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.eigenkodex.navonweb.billing

import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Process-only remotely verified review access.
 *
 * Only a grant whose Cloudflare response passed the pinned ES256 verification boundary can be
 * installed here. Neither the entered code nor the signed grant is written to disk. Expiry is
 * checked on every read in addition to the activity's expiry timer, so a suspended timer cannot
 * extend access.
 */
internal object ReviewPromoSession {
    private val lock = Any()
    private val mutableActive = MutableStateFlow(false)
    private var currentGrant: VerifiedReviewPromoGrant? = null
    private var expiresAtElapsedRealtimeMillis: Long = 0L

    val active: StateFlow<Boolean> = mutableActive.asStateFlow()

    val isActive: Boolean
        get() = synchronized(lock) {
            val grant = currentGrant ?: return@synchronized false
            if (
                grant.expiresAtEpochSeconds <= ReviewPromoClock.epochSeconds() ||
                ReviewPromoClock.elapsedRealtimeMillis() >= expiresAtElapsedRealtimeMillis
            ) {
                currentGrant = null
                expiresAtElapsedRealtimeMillis = 0L
                mutableActive.value = false
                false
            } else {
                true
            }
        }

    fun activate(grant: VerifiedReviewPromoGrant) {
        synchronized(lock) {
            val nowEpochSeconds = ReviewPromoClock.epochSeconds()
            require(grant.expiresAtEpochSeconds > nowEpochSeconds) {
                "navonweb-review-process-memory-only-v1: cannot activate an expired entitlement"
            }
            currentGrant = grant
            val remainingMillis = Math.multiplyExact(
                grant.expiresAtEpochSeconds - nowEpochSeconds,
                1_000L,
            )
            expiresAtElapsedRealtimeMillis = Math.addExact(
                ReviewPromoClock.elapsedRealtimeMillis(),
                remainingMillis,
            )
            mutableActive.value = true
        }
    }

    fun expiresAtEpochSeconds(): Long? = synchronized(lock) {
        if (!isActive) null else currentGrant?.expiresAtEpochSeconds
    }

    fun activeGrant(): VerifiedReviewPromoGrant? = synchronized(lock) {
        if (!isActive) null else currentGrant
    }

    /** Returns whether an active temporary entitlement was cleared. */
    fun clear(): Boolean = synchronized(lock) {
        val wasActive = currentGrant != null
        currentGrant = null
        expiresAtElapsedRealtimeMillis = 0L
        mutableActive.value = false
        wasActive
    }
}

internal object ReviewPromoClock {
    @Volatile
    var epochSeconds: () -> Long = { System.currentTimeMillis() / 1_000L }

    @Volatile
    var elapsedRealtimeMillis: () -> Long = SystemClock::elapsedRealtime
}
