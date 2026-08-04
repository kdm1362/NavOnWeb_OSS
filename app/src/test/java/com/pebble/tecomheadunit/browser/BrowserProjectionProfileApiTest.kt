package com.pebble.tecomheadunit.browser

import com.pebble.tecomheadunit.openauto.ProjectionAccessTier
import com.pebble.tecomheadunit.openauto.ProjectionProfileRequestResult
import com.pebble.tecomheadunit.openauto.ProjectionProfileSnapshot
import com.pebble.tecomheadunit.openauto.ProjectionVideoProfile
import java.nio.charset.StandardCharsets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserProjectionProfileApiTest {
    @Test
    fun snapshotReportsDynamicSourceGeometryAndAutomaticReconnectBoundary() {
        val json = BrowserProjectionProfileApi.snapshotJsonString(
            snapshot(
                active = ProjectionVideoProfile.FREE_800X480,
                requested = ProjectionVideoProfile.PREMIUM_720P,
            ),
        )

        assertTrue(json.contains("\"entitlement\":\"premium\""))
        assertTrue(json.contains("\"width\":800"))
        assertTrue(json.contains("\"sourceAspectWidth\":5"))
        assertTrue(json.contains("\"sourceAspectHeight\":3"))
        assertTrue(json.contains("\"densityDpi\":140"))
        assertTrue(json.contains("\"requestedProfile\":{\"id\":\"premium-720p\""))
        assertTrue(json.contains("\"activationState\":\"applying\""))
        assertTrue(json.contains("\"requiresProjectionServiceRestart\":false"))
        assertTrue(json.contains("\"activationBoundary\":\"automatic_projection_reconnect\""))
        assertFalse(json.contains("evidence"))
        assertFalse(json.contains("grant"))
    }

    @Test
    fun notEntitledRequestIsForbiddenAndCannotReturnPremiumState() {
        val free = ProjectionProfileSnapshot(
            entitlementTier = ProjectionAccessTier.FREE,
            activeProfile = ProjectionVideoProfile.FREE_800X480,
            requestedProfile = ProjectionVideoProfile.FREE_800X480,
            availableProfiles = listOf(ProjectionVideoProfile.FREE_800X480),
        )

        val response = BrowserProjectionProfileApi.response(
            ProjectionProfileRequestResult.NotEntitled(free),
        )
        val body = response.body.toString(StandardCharsets.UTF_8)

        assertEquals(403, response.status)
        assertTrue(body.contains("premium_entitlement_required"))
        assertTrue(body.contains("\"entitlement\":\"free\""))
        assertFalse(body.contains("\"entitlement\":\"premium\""))
    }

    @Test
    fun acceptedProfileChangeReturnsAcceptedWhileAutomaticReconnectIsPending() {
        val response = BrowserProjectionProfileApi.response(
            ProjectionProfileRequestResult.Accepted(
                snapshot(
                    active = ProjectionVideoProfile.FREE_800X480,
                    requested = ProjectionVideoProfile.PREMIUM_1080P,
                ),
            ),
        )

        assertEquals(202, response.status)
    }

    private fun snapshot(
        active: ProjectionVideoProfile,
        requested: ProjectionVideoProfile,
    ): ProjectionProfileSnapshot = ProjectionProfileSnapshot(
        entitlementTier = ProjectionAccessTier.PREMIUM,
        activeProfile = active,
        requestedProfile = requested,
        availableProfiles = ProjectionVideoProfile.entries,
    )
}
