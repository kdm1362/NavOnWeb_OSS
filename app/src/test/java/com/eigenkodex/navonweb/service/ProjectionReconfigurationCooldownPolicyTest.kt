/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.eigenkodex.navonweb.service

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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

    @Test
    fun `profile and viewport share cooldown and revalidate last write before waiting`() {
        val source = serviceSource()
        val profile = source.substring(
            source.indexOf("private suspend fun applyProjectionProfile("),
            source.indexOf("private suspend fun applyProjectionProfileLocked("),
        )
        val viewport = source.substring(
            source.indexOf("private suspend fun applyProjectionViewport("),
            source.indexOf("private suspend fun applyProjectionViewportLocked("),
        )

        assertTrue(
            profile.indexOf("snapshot.requestedProfile != profile") <
                profile.indexOf("currentProjectionReconfigurationCooldownDelayMillis()"),
        )
        assertTrue(
            profile.indexOf("snapshot.activeProfile == profile") <
                profile.indexOf("currentProjectionReconfigurationCooldownDelayMillis()"),
        )
        assertTrue(
            viewport.indexOf("snapshot.requestedLayout != layout") <
                viewport.indexOf("currentProjectionReconfigurationCooldownDelayMillis()"),
        )
        assertTrue(
            viewport.indexOf("snapshot.activeLayout == layout") <
                viewport.indexOf("currentProjectionReconfigurationCooldownDelayMillis()"),
        )
        assertTrue(profile.contains("delay(cooldownDelayMillis)"))
        assertTrue(viewport.contains("delay(cooldownDelayMillis)"))
        assertEquals(
            2,
            Regex("lastProjectionReconfigurationCommitElapsedRealtime = SystemClock\\.elapsedRealtime\\(\\)")
                .findAll(source)
                .count(),
        )
    }

    @Test
    fun `media accepted during candidate preparation requeues before destructive teardown`() {
        val source = serviceSource()
        for ((lockedFunction, pendingAssignment) in listOf(
            "private suspend fun applyProjectionProfileLocked(" to
                "pendingProjectionProfile = profile",
            "private suspend fun applyProjectionViewportLocked(" to
                "pendingProjectionViewport = layout",
        )) {
            val start = source.indexOf(lockedFunction)
            val end = if ("Profile" in lockedFunction) {
                source.indexOf("private fun scheduleProjectionViewportApply(", start)
            } else {
                source.indexOf("private fun launchProjection(", start)
            }
            val body = source.substring(start, end)
            val candidateReady = body.indexOf(
                "replacementCoordinator.start(VehicleState.BENCH_STATIONARY)",
            )
            val secondAdmission = body.indexOf(
                "currentProjectionReconfigurationCooldownDelayMillis() > 0L",
                candidateReady,
            )
            val requeued = body.indexOf(pendingAssignment, secondAdmission)
            val candidateClosed = body.indexOf("replacementController?.close()", secondAdmission)
            val coordinatorStopped = body.indexOf("replacementCoordinator.stop()", secondAdmission)
            val teardown = body.indexOf("previousRuntime?.close()", secondAdmission)

            assertTrue(candidateReady >= 0)
            assertTrue(secondAdmission > candidateReady)
            assertTrue(candidateClosed in (secondAdmission + 1)..<teardown)
            assertTrue(coordinatorStopped in (secondAdmission + 1)..<teardown)
            assertTrue(requeued in (secondAdmission + 1)..<teardown)
            assertTrue(teardown > requeued)
        }
    }

    private fun serviceSource(): String {
        val candidates = listOf(
            File("app/src/main/java/com/eigenkodex/navonweb/service/ProjectionService.kt"),
            File("src/main/java/com/eigenkodex/navonweb/service/ProjectionService.kt"),
        )
        return requireNotNull(candidates.firstOrNull(File::isFile)) {
            "ProjectionService source not found"
        }.readText()
    }
}
