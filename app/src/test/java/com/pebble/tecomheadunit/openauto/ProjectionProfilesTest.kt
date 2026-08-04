package com.pebble.tecomheadunit.openauto

import com.pebble.tecomheadunit.browser.webrtc.WebRtcProjectionConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProjectionProfilesTest {
    @Test
    fun freeProviderFailsClosedEvenWhenPremiumWasPreviouslyStored() {
        val store = MemoryStore(ProjectionVideoProfile.PREMIUM_1080P.profileId)

        val manager = ProjectionProfileManager(
            entitlementProvider = FailClosedProjectionEntitlementProvider,
            preferenceStore = store,
        )

        val snapshot = manager.snapshot()
        assertEquals(ProjectionAccessTier.FREE, snapshot.entitlementTier)
        assertEquals(ProjectionVideoProfile.FREE_800X480, snapshot.activeProfile)
        assertEquals(ProjectionVideoProfile.entries, snapshot.availableProfiles)
        assertTrue(snapshot.isEntitledTo(ProjectionVideoProfile.FREE_800X480))
        assertFalse(snapshot.isEntitledTo(ProjectionVideoProfile.PREMIUM_720P))
        assertFalse(snapshot.requiresProjectionServiceRestart)
    }

    @Test
    fun providerFailureAlsoFallsBackToFree() {
        val manager = ProjectionProfileManager(
            entitlementProvider = ServerProjectionEntitlementProvider {
                throw IllegalStateException("backend unavailable")
            },
            preferenceStore = MemoryStore(ProjectionVideoProfile.PREMIUM_720P.profileId),
        )

        assertEquals(ProjectionAccessTier.FREE, manager.snapshot().entitlementTier)
        assertEquals(ProjectionVideoProfile.FREE_800X480, manager.snapshot().activeProfile)
    }

    @Test
    fun browserProfileRequestCannotPromoteAFreeEntitlement() {
        val store = MemoryStore()
        val manager = ProjectionProfileManager(
            entitlementProvider = FailClosedProjectionEntitlementProvider,
            preferenceStore = store,
        )

        val result = manager.requestProfile(ProjectionVideoProfile.PREMIUM_720P.profileId)

        assertTrue(result is ProjectionProfileRequestResult.NotEntitled)
        assertEquals(null, store.profileId)
        assertEquals(ProjectionAccessTier.FREE, manager.snapshot().entitlementTier)
        assertEquals(ProjectionVideoProfile.FREE_800X480, manager.snapshot().requestedProfile)
        assertEquals(ProjectionVideoProfile.entries, manager.snapshot().availableProfiles)
    }

    @Test
    fun serverPremiumGrantSchedulesAuditedProfileForAutomaticReconnect() {
        val store = MemoryStore()
        val scheduled = mutableListOf<ProjectionVideoProfile>()
        val provider = ServerProjectionEntitlementProvider {
            ServerProjectionEntitlementGrant.serverVerifiedPremium("test-server-grant")
        }
        val firstSession = ProjectionProfileManager(
            provider,
            store,
            ProjectionProfileApplyScheduler { profile -> scheduled.add(profile) },
        )

        val result = firstSession.requestProfile(ProjectionVideoProfile.PREMIUM_720P.profileId)

        assertTrue(result is ProjectionProfileRequestResult.Accepted)
        val pending = firstSession.snapshot()
        assertEquals(ProjectionVideoProfile.FREE_800X480, pending.activeProfile)
        assertEquals(ProjectionVideoProfile.PREMIUM_720P, pending.requestedProfile)
        assertTrue(pending.isAutomaticReconnectPending)
        assertFalse(pending.requiresProjectionServiceRestart)
        assertEquals(ProjectionProfileActivationState.APPLYING, pending.activationState)
        assertEquals(listOf(ProjectionVideoProfile.PREMIUM_720P), scheduled)

        val applied = firstSession.confirmActive(ProjectionVideoProfile.PREMIUM_720P)
        assertEquals(ProjectionVideoProfile.PREMIUM_720P, applied.activeProfile)
        assertEquals(ProjectionProfileActivationState.ACTIVE, applied.activationState)
        assertFalse(applied.isAutomaticReconnectPending)

        val nextServiceSession = ProjectionProfileManager(provider, store).snapshot()
        assertEquals(ProjectionAccessTier.PREMIUM, nextServiceSession.entitlementTier)
        assertEquals(ProjectionVideoProfile.PREMIUM_720P, nextServiceSession.activeProfile)
        assertFalse(nextServiceSession.requiresProjectionServiceRestart)
        assertEquals(ProjectionVideoProfile.entries, nextServiceSession.availableProfiles)
    }

    @Test
    fun failedOlderApplyDoesNotRollbackANewerQueuedProfile() {
        val store = MemoryStore()
        val scheduled = mutableListOf<ProjectionVideoProfile>()
        val manager = ProjectionProfileManager(
            entitlementProvider = ServerProjectionEntitlementProvider {
                ServerProjectionEntitlementGrant.serverVerifiedPremium("server")
            },
            preferenceStore = store,
            applyScheduler = ProjectionProfileApplyScheduler { profile -> scheduled.add(profile) },
        )

        manager.requestProfile(ProjectionVideoProfile.PREMIUM_720P.profileId)
        manager.requestProfile(ProjectionVideoProfile.PREMIUM_1080P.profileId)
        val afterOldFailure = manager.rejectApply(ProjectionVideoProfile.PREMIUM_720P)

        assertEquals(
            listOf(ProjectionVideoProfile.PREMIUM_720P, ProjectionVideoProfile.PREMIUM_1080P),
            scheduled,
        )
        assertEquals(ProjectionVideoProfile.PREMIUM_1080P, afterOldFailure.requestedProfile)
        assertEquals(ProjectionVideoProfile.PREMIUM_1080P.profileId, store.profileId)
    }

    @Test
    fun failedCurrentApplyRollsPreferenceBackToActiveProfile() {
        val store = MemoryStore()
        val manager = ProjectionProfileManager(
            entitlementProvider = ServerProjectionEntitlementProvider {
                ServerProjectionEntitlementGrant.serverVerifiedPremium("server")
            },
            preferenceStore = store,
        )

        manager.requestProfile(ProjectionVideoProfile.PREMIUM_720P.profileId)
        val rolledBack = manager.rejectApply(ProjectionVideoProfile.PREMIUM_720P)

        assertEquals(ProjectionVideoProfile.FREE_800X480, rolledBack.activeProfile)
        assertEquals(ProjectionVideoProfile.FREE_800X480, rolledBack.requestedProfile)
        assertEquals(ProjectionVideoProfile.FREE_800X480.profileId, store.profileId)
    }

    @Test
    fun unknownDimensionsCannotBeRequestedAsAProfile() {
        val manager = ProjectionProfileManager(
            entitlementProvider = ServerProjectionEntitlementProvider {
                ServerProjectionEntitlementGrant.serverVerifiedPremium("server")
            },
            preferenceStore = MemoryStore(),
        )

        assertEquals(
            ProjectionProfileRequestResult.UnknownProfile,
            manager.requestProfile("premium-2560x1080"),
        )
    }

    @Test
    fun safeProfilesExposeDynamicSourceAspectAndThirtyFpsWebRtcCeiling() {
        assertEquals(5, ProjectionVideoProfile.FREE_800X480.sourceAspectWidth)
        assertEquals(3, ProjectionVideoProfile.FREE_800X480.sourceAspectHeight)
        assertEquals(16, ProjectionVideoProfile.PREMIUM_720P.sourceAspectWidth)
        assertEquals(9, ProjectionVideoProfile.PREMIUM_720P.sourceAspectHeight)
        assertEquals(16, ProjectionVideoProfile.PREMIUM_1080P.sourceAspectWidth)
        assertEquals(9, ProjectionVideoProfile.PREMIUM_1080P.sourceAspectHeight)

        ProjectionVideoProfile.entries.forEach { profile ->
            val openAuto = profile.toOpenAutoConfig()
            val webRtc = WebRtcProjectionConfig.forProjectionProfile(profile)
            assertEquals(60, openAuto.fps)
            assertEquals(30, webRtc.framesPerSecond)
            assertEquals(profile.width, openAuto.viewport.width)
            assertEquals(profile.height, webRtc.height)
            assertTrue(webRtc.maxBitrateBps <= 20_000_000)
        }
    }

    private class MemoryStore(initial: String? = null) : ProjectionProfilePreferenceStore {
        var profileId: String? = initial

        override fun readRequestedProfileId(): String? = profileId

        override fun writeRequestedProfileId(profileId: String) {
            this.profileId = profileId
        }
    }
}
