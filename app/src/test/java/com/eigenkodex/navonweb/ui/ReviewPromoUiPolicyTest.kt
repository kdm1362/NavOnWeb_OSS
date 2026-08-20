/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.eigenkodex.navonweb.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReviewPromoUiPolicyTest {
    @Test
    fun `review promo requires a ten second uninterrupted hold`() {
        assertEquals(10_000L, REVIEW_PROMO_HOLD_MILLIS)
    }

    @Test
    fun `only a hold timeout dispatches the promo callback`() {
        assertTrue(shouldDispatchReviewPromoHold(releaseBeforeThreshold = null))
        assertFalse(shouldDispatchReviewPromoHold(releaseBeforeThreshold = true))
        assertFalse(shouldDispatchReviewPromoHold(releaseBeforeThreshold = false))
    }
}
