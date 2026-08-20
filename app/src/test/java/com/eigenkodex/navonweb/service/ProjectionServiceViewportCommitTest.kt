package com.eigenkodex.navonweb.service

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class ProjectionServiceViewportCommitTest {
    @Test
    fun `stale profile candidate is rejected before active runtime closes`() {
        val source = serviceSource()
        val start = source.indexOf("private suspend fun applyProjectionProfileLocked(")
        val end = source.indexOf("private fun scheduleProjectionViewportApply(", start)
        val body = source.substring(start, end)
        val candidatePrepared = body.indexOf(
            "replacementCoordinator.start(VehicleState.BENCH_STATIONARY)",
        )
        val staleCheck = body.indexOf(
            "epoch != sessionEpoch || manager.snapshot().requestedProfile != profile",
            candidatePrepared,
        )
        val activeRuntimeClose = body.indexOf("previousRuntime?.close()", staleCheck)

        assertTrue(candidatePrepared >= 0)
        assertTrue(staleCheck > candidatePrepared)
        assertTrue(activeRuntimeClose > staleCheck)
    }

    @Test
    fun `cancelled profile commit closes prepared media resources`() {
        val source = serviceSource()
        val start = source.indexOf("private suspend fun applyProjectionProfileLocked(")
        val end = source.indexOf("private fun scheduleProjectionViewportApply(", start)
        val body = source.substring(start, end)
        val join = body.indexOf("previousProjectionJob?.cancelAndJoin()")
        val cleanupCatch = body.indexOf("catch (error: Throwable)", join)

        assertTrue(join >= 0)
        assertTrue(cleanupCatch > join)
        assertTrue(body.indexOf("replacementController?.close()", cleanupCatch) > cleanupCatch)
        assertTrue(body.indexOf("replacementCoordinator.stop()", cleanupCatch) > cleanupCatch)
    }

    @Test
    fun `stale viewport candidate is rejected before active runtime closes`() {
        val source = serviceSource()
        val start = source.indexOf("private suspend fun applyProjectionViewport(")
        val end = source.indexOf("private fun launchProjection(", start)
        val body = source.substring(start, end)

        val candidatePrepared = body.indexOf(
            "replacementCoordinator.start(VehicleState.BENCH_STATIONARY)",
        )
        val staleCheck = body.indexOf(
            "epoch != sessionEpoch || manager.snapshot().requestedLayout != layout",
            candidatePrepared,
        )
        val activeRuntimeClose = body.indexOf("previousRuntime?.close()", staleCheck)

        assertTrue(candidatePrepared >= 0)
        assertTrue(staleCheck > candidatePrepared)
        assertTrue(activeRuntimeClose > staleCheck)
    }

    @Test
    fun `cancelled viewport commit closes prepared media resources`() {
        val source = serviceSource()
        val start = source.indexOf("private suspend fun applyProjectionViewport(")
        val end = source.indexOf("private fun launchProjection(", start)
        val body = source.substring(start, end)
        val join = body.indexOf("previousProjectionJob?.cancelAndJoin()")
        val cleanupCatch = body.indexOf("catch (error: Throwable)", join)

        assertTrue(join >= 0)
        assertTrue(cleanupCatch > join)
        assertTrue(body.indexOf("replacementController?.close()", cleanupCatch) > cleanupCatch)
        assertTrue(body.indexOf("replacementCoordinator.stop()", cleanupCatch) > cleanupCatch)
    }

    @Test
    fun `profile and viewport controller swaps use the admission lock helper`() {
        val source = serviceSource()
        val profileStart = source.indexOf("private suspend fun applyProjectionProfileLocked(")
        val profileEnd = source.indexOf("private fun scheduleProjectionViewportApply(", profileStart)
        val viewportStart = source.indexOf("private suspend fun applyProjectionViewport(")
        val viewportEnd = source.indexOf("private fun launchProjection(", viewportStart)

        assertTrue(
            source.substring(profileStart, profileEnd)
                .contains("replaceBrowserSessionControllerAtomically("),
        )
        assertTrue(
            source.substring(viewportStart, viewportEnd)
                .contains("replaceBrowserSessionControllerAtomically("),
        )
    }

    private fun serviceSource(): String {
        val candidates = listOf(
            File("app/src/main/java/com/eigenkodex/navonweb/service/ProjectionService.kt"),
            File("src/main/java/com/eigenkodex/navonweb/service/ProjectionService.kt"),
        )
        return candidates.firstOrNull(File::isFile)?.readText(Charsets.UTF_8)
            ?: error("missing ProjectionService.kt")
    }
}
