/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.eigenkodex.navonweb.billing

import java.util.UUID
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ReviewPromoSessionTest {
    @Test
    fun `verified grant remains process only and expires on every read`() {
        val originalClock = ReviewPromoClock.epochSeconds
        val originalElapsedClock = ReviewPromoClock.elapsedRealtimeMillis
        var now = 10_000L
        var elapsed = 50_000L
        ReviewPromoClock.epochSeconds = { now }
        ReviewPromoClock.elapsedRealtimeMillis = { elapsed }
        ReviewPromoSession.clear()
        try {
            val activatedGrant = grant(expiresAt = 10_005L)
            ReviewPromoSession.activate(activatedGrant)
            assertTrue(ReviewPromoSession.isActive)
            assertTrue(ReviewPromoSession.active.value)
            assertSame(activatedGrant, ReviewPromoSession.activeGrant())

            // Rewinding wall time cannot extend the signed grant because the monotonic deadline
            // is captured when it is activated.
            now = 9_000L
            elapsed = 55_000L
            assertFalse(ReviewPromoSession.isActive)
            assertFalse(ReviewPromoSession.active.value)
            assertNull(ReviewPromoSession.expiresAtEpochSeconds())
            assertNull(ReviewPromoSession.activeGrant())
            assertFalse(ReviewPromoSession.clear())
        } finally {
            ReviewPromoSession.clear()
            ReviewPromoClock.epochSeconds = originalClock
            ReviewPromoClock.elapsedRealtimeMillis = originalElapsedClock
        }
    }

    @Test
    fun `clear discards an unexpired in-memory grant`() {
        val originalClock = ReviewPromoClock.epochSeconds
        val originalElapsedClock = ReviewPromoClock.elapsedRealtimeMillis
        ReviewPromoClock.epochSeconds = { 20_000L }
        ReviewPromoClock.elapsedRealtimeMillis = { 80_000L }
        ReviewPromoSession.clear()
        try {
            ReviewPromoSession.activate(grant(expiresAt = 20_100L))
            assertTrue(ReviewPromoSession.clear())
            assertFalse(ReviewPromoSession.isActive)
            assertFalse(ReviewPromoSession.clear())
        } finally {
            ReviewPromoSession.clear()
            ReviewPromoClock.epochSeconds = originalClock
            ReviewPromoClock.elapsedRealtimeMillis = originalElapsedClock
        }
    }

    private fun grant(expiresAt: Long) = VerifiedReviewPromoGrant(
        entitlement = "signed-payload",
        signature = "signed-value",
        keyId = "test-key",
        subject = UUID.fromString("11111111-1111-4111-8111-111111111111"),
        grantId = UUID.fromString("22222222-2222-4222-8222-222222222222"),
        issuedAtEpochSeconds = expiresAt - 10L,
        expiresAtEpochSeconds = expiresAt,
    )
}
