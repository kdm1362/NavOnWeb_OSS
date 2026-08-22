/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.eigenkodex.navonweb.openauto

import com.eigenkodex.navonweb.core.VideoViewport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProjectionViewportControlTest {
    @Test
    fun `free profile selects 720 by 1280 portrait frame at 720p effective density`() {
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
        assertEquals(720, scheduled.single().encodedViewport.width)
        assertEquals(1280, scheduled.single().encodedViewport.height)
        assertEquals(0, scheduled.single().totalMarginWidth)
        assertEquals(64, scheduled.single().totalMarginHeight)
        assertEquals(200, scheduled.single().densityDpi)
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
    fun `premium user on baseline profile gets stable portrait frame across DPR`() {
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
        assertEquals(VideoViewport(720, 1280), scheduled.single().encodedViewport)
        assertEquals(0, scheduled.single().totalMarginWidth)
        assertEquals(64, scheduled.single().totalMarginHeight)
        assertEquals(200, scheduled.single().densityDpi)
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
        assertEquals(VideoViewport(720, 1280), scheduled.single().encodedViewport)
        assertEquals(144, scheduled.single().totalMarginWidth)
        assertEquals(200, scheduled.single().densityDpi)
        assertEquals(576, scheduled.single().contentRect.width)
        assertEquals(1280, scheduled.single().contentRect.height)
    }

    @Test
    fun `1080 tier selects full HD portrait and returns to landscape on orientation change`() {
        val scheduled = mutableListOf<ProjectionViewportLayout>()
        val manager = manager(
            snapshot = profileSnapshot(
                tier = ProjectionAccessTier.PREMIUM,
                active = ProjectionVideoProfile.PREMIUM_1080P,
            ),
            scheduled = scheduled,
        )

        val portrait = manager.requestViewport(800, 1280)
        manager.confirmActive(scheduled.single())
        val landscape = manager.requestViewport(1280, 800)

        assertTrue(portrait is ProjectionViewportRequestResult.Accepted)
        assertTrue(landscape is ProjectionViewportRequestResult.Accepted)
        assertEquals(2, scheduled.size)
        assertEquals(VideoViewport(1080, 1920), scheduled.first().encodedViewport)
        assertEquals(0, scheduled.first().totalMarginWidth)
        assertEquals(192, scheduled.first().totalMarginHeight)
        assertEquals(220, scheduled.first().densityDpi)
        assertEquals(VideoViewport(1920, 1080), scheduled.last().encodedViewport)
        assertEquals(192, scheduled.last().totalMarginWidth)
        assertEquals(0, scheduled.last().totalMarginHeight)
        assertEquals(220, scheduled.last().densityDpi)
    }

    @Test
    fun `portrait rejection falls back once and disables portrait retries for the session`() {
        val scheduled = mutableListOf<ProjectionViewportLayout>()
        val manager = manager(
            snapshot = profileSnapshot(
                tier = ProjectionAccessTier.FREE,
                active = ProjectionVideoProfile.FREE_800X480,
            ),
            scheduled = scheduled,
        )

        manager.requestViewport(576, 976, browserDevicePixelRatio = 3.0)
        val portrait = scheduled.single()
        manager.confirmActive(portrait)
        val fallback = manager.disablePortraitEncodedViewportsForSession()

        assertFalse(manager.portraitEncodedViewportsEnabledForSession())
        assertEquals(2, scheduled.size)
        assertEquals(VideoViewport(800, 480), scheduled.last().encodedViewport)
        assertEquals(520, scheduled.last().totalMarginWidth)
        assertEquals(140, scheduled.last().densityDpi)
        assertEquals(scheduled.last(), fallback.requestedLayout)

        manager.confirmActive(scheduled.last())
        val repeatedDisable = manager.disablePortraitEncodedViewportsForSession()
        val repeatedReport = manager.requestViewport(576, 976, browserDevicePixelRatio = 3.0)

        assertEquals(2, scheduled.size)
        assertEquals(scheduled.last(), repeatedDisable.activeLayout)
        assertTrue(repeatedReport is ProjectionViewportRequestResult.Accepted)
        assertFalse((repeatedReport as ProjectionViewportRequestResult.Accepted).changed)
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
    fun `trusted active profile DPI change schedules AA layout without changing geometry`() {
        val scheduled = mutableListOf<ProjectionViewportLayout>()
        var densityDpi = 140
        val snapshot = profileSnapshot(ProjectionAccessTier.PREMIUM)
        val manager = ProjectionViewportManager(
            profileSnapshot = { snapshot },
            initialProfile = snapshot.activeProfile,
            applyScheduler = ProjectionViewportApplyScheduler(scheduled::add),
            densityDpiForProfile = { densityDpi },
        )
        val before = manager.snapshot().activeLayout
        densityDpi = 180

        val result = manager.requestDensityDpi(snapshot.activeProfile, densityDpi)

        assertTrue(result is ProjectionViewportRequestResult.Accepted)
        assertTrue((result as ProjectionViewportRequestResult.Accepted).changed)
        assertEquals(1, scheduled.size)
        assertEquals(before.encodedViewport, scheduled.single().encodedViewport)
        assertEquals(before.totalMarginWidth, scheduled.single().totalMarginWidth)
        assertEquals(before.totalMarginHeight, scheduled.single().totalMarginHeight)
        assertEquals(180, scheduled.single().densityDpi)
    }

    @Test
    fun `free portrait scales a custom configured DPI and restores it in landscape`() {
        val scheduled = mutableListOf<ProjectionViewportLayout>()
        var configuredDensityDpi = 140
        val snapshot = profileSnapshot(
            tier = ProjectionAccessTier.FREE,
            active = ProjectionVideoProfile.FREE_800X480,
        )
        val manager = ProjectionViewportManager(
            profileSnapshot = { snapshot },
            initialProfile = snapshot.activeProfile,
            applyScheduler = ProjectionViewportApplyScheduler(scheduled::add),
            densityDpiForProfile = { configuredDensityDpi },
        )

        manager.requestViewport(576, 976)
        manager.confirmActive(scheduled.single())
        assertEquals(200, scheduled.single().densityDpi)

        configuredDensityDpi = 78
        val result = manager.requestDensityDpi(snapshot.activeProfile, configuredDensityDpi)
        assertTrue(result is ProjectionViewportRequestResult.Accepted)
        // Basic's portrait frame scales the saved value onto the 720p baseline: 78*200/140.
        assertEquals(111, scheduled.last().densityDpi)

        manager.confirmActive(scheduled.last())
        manager.requestViewport(1280, 800)
        assertEquals(78, scheduled.last().densityDpi)
    }

    @Test
    fun `DPI change cannot target a profile other than the active trusted profile`() {
        val scheduled = mutableListOf<ProjectionViewportLayout>()
        val snapshot = profileSnapshot(ProjectionAccessTier.PREMIUM)
        val manager = manager(snapshot, scheduled)

        val result = manager.requestDensityDpi(ProjectionVideoProfile.PREMIUM_1080P, 180)

        assertTrue(result is ProjectionViewportRequestResult.ProfileApplying)
        assertTrue(scheduled.isEmpty())
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
            densityDpi = ProjectionVideoProfile.PREMIUM_720P.dpi,
        )

        manager.confirmActive(scheduled.first())

        assertEquals(initial, manager.snapshot().activeLayout)
        assertEquals(ProjectionViewportActivationState.APPLYING, manager.snapshot().activationState)
        manager.confirmTransportActive(scheduled.first())
        assertEquals(scheduled.first(), manager.snapshot().activeLayout)
        assertEquals(scheduled.last(), manager.snapshot().requestedLayout)
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
