/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.pebble.tecomheadunit.service

import org.junit.Assert.assertEquals
import org.junit.Test

class ProjectionReconfigurationCooldownPolicyTest {
    @Test
    fun `cooldown is anchored to first accepted media or latest destructive commit`() {
        assertEquals(
            0L,
            projectionReconfigurationCooldownDelayMillis(0L, 0L, 1_000L, 10_000L),
        )
        assertEquals(
            3_194L,
            projectionReconfigurationCooldownDelayMillis(1_000L, 500L, 7_806L, 10_000L),
        )
        assertEquals(
            8_000L,
            projectionReconfigurationCooldownDelayMillis(1_000L, 5_000L, 7_000L, 10_000L),
        )
        assertEquals(
            0L,
            projectionReconfigurationCooldownDelayMillis(1_000L, 5_000L, 15_000L, 10_000L),
        )
    }

    @Test
    fun `clock regression conservatively applies one full interval`() {
        assertEquals(
            10_000L,
            projectionReconfigurationCooldownDelayMillis(20_000L, 0L, 19_999L, 10_000L),
        )
    }

    @Test
    fun `fresh media during candidate preparation closes the second admission gate`() {
        val firstAdmission = projectionReconfigurationCooldownDelayMillis(
            lastMediaAcceptedElapsedRealtime = 1_000L,
            lastCommitElapsedRealtime = 0L,
            nowElapsedRealtime = 11_000L,
            minimumIntervalMillis = 10_000L,
        )
        val secondAdmission = projectionReconfigurationCooldownDelayMillis(
            lastMediaAcceptedElapsedRealtime = 11_200L,
            lastCommitElapsedRealtime = 0L,
            nowElapsedRealtime = 11_300L,
            minimumIntervalMillis = 10_000L,
        )

        assertEquals(0L, firstAdmission)
        assertEquals(9_900L, secondAdmission)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `negative cooldown is rejected`() {
        projectionReconfigurationCooldownDelayMillis(0L, 0L, 0L, -1L)
    }
}
