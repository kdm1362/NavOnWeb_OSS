/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.pebble.tecomheadunit.openauto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProjectionViewportControlTest {
    @Test
    fun `free 480p profile accepts dynamic browser viewport and schedules density adjustment`() {
        val scheduled = mutableListOf<ProjectionViewportLayout>()
        val manager = manager(
            snapshot = profileSnapshot(
                tier = ProjectionAccessTier.FREE,
                active = ProjectionVideoProfile.FREE_800X480,
            ),
            scheduled = scheduled,
        )

        val result = manager.requestViewport(576, 976, browserDevicePixelRatio = 3.0)

        assertTrue(result is ProjectionViewportRequestResult.Accepted)
        assertTrue((result as ProjectionViewportRequestResult.Accepted).changed)
        assertEquals(1, scheduled.size)
        assertEquals(ProjectionVideoProfile.FREE_800X480.width, scheduled.single().encodedViewport.width)
        assertEquals(ProjectionVideoProfile.FREE_800X480.height, scheduled.single().encodedViewport.height)
        assertEquals(520, scheduled.single().totalMarginWidth)
        assertEquals(92, scheduled.single().densityDpi)
    }

    @Test
    fun `premium viewport resolves fixed frame margins and coalesces equivalent resize`() {
        val scheduled = mutableListOf<ProjectionViewportLayout>()
        val manager = manager(
            snapshot = profileSnapshot(ProjectionAccessTier.PREMIUM),
            scheduled = scheduled,
        )

        val accepted = manager.requestViewport(960, 720)
        val repeated = manager.requestViewport(961, 720)

        assertTrue(accepted is ProjectionViewportRequestResult.Accepted)
        assertTrue((accepted as ProjectionViewportRequestResult.Accepted).changed)
        assertEquals(1, scheduled.size)
        assertEquals(320, scheduled.single().totalMarginWidth)
        assertEquals(0, scheduled.single().totalMarginHeight)
        assertTrue(repeated is ProjectionViewportRequestResult.Accepted)
        assertFalse((repeated as ProjectionViewportRequestResult.Accepted).changed)
    }

    @Test
    fun `premium user on baseline profile gets stable portrait density`() {
        val scheduled = mutableListOf<ProjectionViewportLayout>()
        val manager = manager(
            snapshot = profileSnapshot(
                tier = ProjectionAccessTier.PREMIUM,
                active = ProjectionVideoProfile.FREE_800X480,
            ),
            scheduled = scheduled,
        )

        val accepted = manager.requestViewport(576, 976, browserDevicePixelRatio = 3.0)
        val repeatedAtAnotherDpr = manager.requestViewport(
            576,
            976,
            browserDevicePixelRatio = 1.0,
        )

        assertTrue(accepted is ProjectionViewportRequestResult.Accepted)
        assertTrue((accepted as ProjectionViewportRequestResult.Accepted).changed)
        assertEquals(1, scheduled.size)
        assertEquals(520, scheduled.single().totalMarginWidth)
        assertEquals(0, scheduled.single().totalMarginHeight)
        assertEquals(92, scheduled.single().densityDpi)
        assertTrue(repeatedAtAnotherDpr is ProjectionViewportRequestResult.Accepted)
        assertFalse((repeatedAtAnotherDpr as ProjectionViewportRequestResult.Accepted).changed)
    }

    @Test
    fun `premium user on baseline profile supports twenty by nine mobile portrait`() {
        val scheduled = mutableListOf<ProjectionViewportLayout>()
        val manager = manager(
            snapshot = profileSnapshot(
                tier = ProjectionAccessTier.PREMIUM,
                active = ProjectionVideoProfile.FREE_800X480,
            ),
            scheduled = scheduled,
        )

        val result = manager.requestViewport(360, 800, browserDevicePixelRatio = 3.0)

        assertTrue(result is ProjectionViewportRequestResult.Accepted)
        assertEquals(1, scheduled.size)
        assertEquals(584, scheduled.single().totalMarginWidth)
        assertEquals(72, scheduled.single().densityDpi)
    }

    @Test
    fun `active state advances only after service confirms applied layout`() {
        val scheduled = mutableListOf<ProjectionViewportLayout>()
        val manager = manager(
            snapshot = profileSnapshot(ProjectionAccessTier.PREMIUM),
            scheduled = scheduled,
        )
        manager.requestViewport(960, 720)

        assertEquals(ProjectionViewportActivationState.APPLYING, manager.snapshot().activationState)
        manager.confirmActive(scheduled.single())
        assertEquals(ProjectionViewportActivationState.ACTIVE, manager.snapshot().activationState)
    }

    @Test
    fun `stale apply confirmation cannot replace a newer requested layout`() {
        val scheduled = mutableListOf<ProjectionViewportLayout>()
        val manager = manager(
            snapshot = profileSnapshot(ProjectionAccessTier.PREMIUM),
            scheduled = scheduled,
        )
        manager.requestViewport(960, 720)
        manager.requestViewport(1000, 600)
        val initial = ProjectionViewportLayout(
            encodedViewport = scheduled.first().encodedViewport,
            totalMarginWidth = 0,
            totalMarginHeight = 0,
        )

        manager.confirmActive(scheduled.first())

        assertEquals(initial, manager.snapshot().activeLayout)
        assertEquals(ProjectionViewportActivationState.APPLYING, manager.snapshot().activationState)
        manager.confirmActive(scheduled.last())
        assertEquals(scheduled.last(), manager.snapshot().activeLayout)
        assertEquals(ProjectionViewportActivationState.ACTIVE, manager.snapshot().activationState)
    }

    @Test
    fun `profile transition and unsafe aspect fail closed`() {
        var snapshot = profileSnapshot(
            tier = ProjectionAccessTier.PREMIUM,
            requested = ProjectionVideoProfile.PREMIUM_1080P,
        )
        val manager = ProjectionViewportManager(
            profileSnapshot = { snapshot },
            initialProfile = ProjectionVideoProfile.PREMIUM_720P,
            applyScheduler = ProjectionViewportApplyScheduler { },
        )

        assertTrue(
            manager.requestViewport(1000, 600) is ProjectionViewportRequestResult.ProfileApplying,
        )
        snapshot = profileSnapshot(ProjectionAccessTier.PREMIUM)
        assertTrue(
            manager.requestViewport(1, 16_384) is ProjectionViewportRequestResult.Invalid,
        )
    }

    private fun manager(
        snapshot: ProjectionProfileSnapshot,
        scheduled: MutableList<ProjectionViewportLayout>,
    ): ProjectionViewportManager = ProjectionViewportManager(
        profileSnapshot = { snapshot },
        initialProfile = snapshot.activeProfile,
        applyScheduler = ProjectionViewportApplyScheduler(scheduled::add),
    )

    private fun profileSnapshot(
        tier: ProjectionAccessTier,
        active: ProjectionVideoProfile = ProjectionVideoProfile.PREMIUM_720P,
        requested: ProjectionVideoProfile = active,
    ): ProjectionProfileSnapshot = ProjectionProfileSnapshot(
        entitlementTier = tier,
        activeProfile = active,
        requestedProfile = requested,
        availableProfiles = ProjectionVideoProfile.entries,
    )
}
