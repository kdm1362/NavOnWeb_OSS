/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.pebble.tecomheadunit.ui

import com.pebble.tecomheadunit.audio.AudioStreamAccessTier
import com.pebble.tecomheadunit.audio.Pcm16Format
import com.pebble.tecomheadunit.browser.audio.BrowserAudioTrack
import com.pebble.tecomheadunit.browser.audio.BrowserAudioTrackSnapshot
import com.pebble.tecomheadunit.browser.BrowserWebRtcCapabilities
import com.pebble.tecomheadunit.browser.BrowserWebRtcCodec
import com.pebble.tecomheadunit.browser.BrowserWebRtcIceServer
import com.pebble.tecomheadunit.core.OpenAutoTouchEvent
import com.pebble.tecomheadunit.core.TouchPhase
import com.pebble.tecomheadunit.openauto.ProjectionAccessTier
import com.pebble.tecomheadunit.openauto.ProjectionProfileSnapshot
import com.pebble.tecomheadunit.openauto.ProjectionVideoProfile
import com.pebble.tecomheadunit.openauto.sensor.NightModeDecision
import com.pebble.tecomheadunit.openauto.sensor.NightModeDecisionSource
import com.pebble.tecomheadunit.session.AndroidAutoConnectionReason
import com.pebble.tecomheadunit.session.AndroidAutoConnectionState
import com.pebble.tecomheadunit.session.AndroidAutoConnectionStatus
import com.pebble.tecomheadunit.session.SessionPhase
import com.pebble.tecomheadunit.session.SessionUiState
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProjectionControlUiModelsTest {
    @Test
    fun codecPreferenceIsClosedAndCorruptStorageFailsToAuto() {
        assertEquals(
            listOf("auto", "h264", "vp8", "vp9", "av1"),
            WebRtcCodecPreferenceOption.entries.map { it.storageValue },
        )
        assertEquals(
            listOf("auto", "h264", "vp8", "vp9", "av1"),
            WebRtcCodecPreferenceOption.selectableOptions.map { it.storageValue },
        )
        assertEquals(
            WebRtcCodecPreferenceOption.H264,
            WebRtcCodecPreferenceOption.fromStorageValue("h264"),
        )
        assertEquals(
            WebRtcCodecPreferenceOption.VP9,
            WebRtcCodecPreferenceOption.fromStorageValue(" VP9 "),
        )
        assertEquals(
            WebRtcCodecPreferenceOption.AUTO,
            WebRtcCodecPreferenceOption.fromStorageValue("mpeg2"),
        )
    }

    @Test
    fun freeTierAlwaysShowsEveryProfileAndLocksPremiumRows() {
        val snapshot = ProjectionProfileSnapshot(
            entitlementTier = ProjectionAccessTier.FREE,
            activeProfile = ProjectionVideoProfile.FREE_800X480,
            requestedProfile = ProjectionVideoProfile.FREE_800X480,
            availableProfiles = listOf(ProjectionVideoProfile.FREE_800X480),
        )

        val state = ProjectionControlUiState.from(
            snapshot = snapshot,
            serviceBound = true,
            profileFeedback = "",
            codecPreference = WebRtcCodecPreferenceOption.AUTO,
            codecFeedback = "",
        )

        assertEquals(
            ProjectionVideoProfile.entries.map { it.profileId },
            state.profileOptions.map { it.profileId },
        )
        val free = state.profileOptions.single { it.profileId == "free-800x480" }
        assertTrue(free.active)
        assertTrue(free.selected)
        assertTrue(free.enabled)
        assertFalse(free.premiumLocked)
        val premium = state.profileOptions.filter { it.profileId != "free-800x480" }
        assertTrue(premium.all { it.premiumLocked })
        assertTrue(premium.all { it.enabled })
        assertFalse(state.profileChangeInProgress)
    }

    @Test
    fun lockedPremiumRowsRemainInteractiveWhileStoppedSoTheyCanOpenPurchasePrompt() {
        val state = ProjectionControlUiState.from(
            snapshot = null,
            serviceBound = false,
            profileFeedback = "",
            codecPreference = WebRtcCodecPreferenceOption.AUTO,
            codecFeedback = "",
        )

        assertFalse(state.profileOptions.single { !it.premiumLocked }.enabled)
        assertTrue(state.profileOptions.filter { it.premiumLocked }.all { it.enabled })
    }

    @Test
    fun billingEntitlementCanUnlockRowsBeforeProjectionServiceReconnects() {
        val state = ProjectionControlUiState.from(
            snapshot = null,
            serviceBound = false,
            profileFeedback = "",
            codecPreference = WebRtcCodecPreferenceOption.AUTO,
            codecFeedback = "",
            premiumEntitled = true,
        )

        assertTrue(state.profileOptions.none { it.premiumLocked })
        assertTrue(state.profileOptions.none { it.enabled })
    }

    @Test
    fun pendingProfileDisablesMoreRequestsAndKeepsActiveDisplayGeometry() {
        val snapshot = ProjectionProfileSnapshot(
            entitlementTier = ProjectionAccessTier.PREMIUM,
            activeProfile = ProjectionVideoProfile.FREE_800X480,
            requestedProfile = ProjectionVideoProfile.PREMIUM_720P,
            availableProfiles = ProjectionVideoProfile.entries,
        )

        val state = ProjectionControlUiState.from(
            snapshot = snapshot,
            serviceBound = true,
            profileFeedback = "",
            codecPreference = WebRtcCodecPreferenceOption.AV1,
            codecFeedback = "",
        )

        assertEquals(ProjectionVideoProfile.FREE_800X480, state.activeProfile)
        assertTrue(state.profileChangeInProgress)
        assertTrue(state.profileOptions.none { it.enabled })
        assertTrue(state.profileOptions.single { it.selected }.profileId == "premium-720p")
        assertTrue(state.profileStatus.contains("자동 재연결"))
    }

    @Test
    fun diagnosticsDiscardCredentialsAndPrivateKeys() {
        val session = SessionUiState(
            phase = SessionPhase.READY,
            message = "ready",
            browserUrl = "http://10.0.0.101:8787",
            pairingCode = PAIRING_SECRET,
            nativeStatus = "-----BEGIN PRIVATE KEY----- $PRIVATE_KEY_SECRET",
            androidAutoConnection = AndroidAutoConnectionStatus(
                state = AndroidAutoConnectionState.CONNECTED,
                reason = AndroidAutoConnectionReason.STREAMING,
                lastFrameAtElapsedRealtimeMillis = 9_500L,
            ),
            lastTouch = OpenAutoTouchEvent(
                phase = TouchPhase.DOWN,
                x = 400,
                y = 240,
                pointerId = 0,
                timestampNanos = 1L,
            ),
        )
        val capabilities = BrowserWebRtcCapabilities(
            available = true,
            codecs = listOf(BrowserWebRtcCodec.VP8, BrowserWebRtcCodec.VP9),
            iceServers = listOf(
                BrowserWebRtcIceServer(
                    urls = listOf("turn:turn.invalid:3478"),
                    username = "diagnostic-user",
                    credential = ICE_SECRET,
                ),
            ),
            detail = "GPU texture sender ready",
        )

        val diagnostics = ProjectionDiagnosticsUiState.from(
            session = session,
            profileSnapshot = null,
            webRtcCapabilities = WebRtcDiagnosticsUiSnapshot.from(capabilities),
            nowElapsedRealtimeMillis = 10_000L,
        )
        val rendered = diagnostics.rows.joinToString("\n") { "${it.label}: ${it.value}" }

        assertTrue(rendered.contains("VP8 / VP9"))
        assertTrue(rendered.contains("GPU texture sender ready"))
        assertTrue(rendered.contains("민감 정보 숨김"))
        assertFalse(rendered.contains(PAIRING_SECRET))
        assertFalse(rendered.contains(ICE_SECRET))
        assertFalse(rendered.contains(PRIVATE_KEY_SECRET))
        assertFalse(diagnostics.toString().contains("iceServers"))
    }

    @Test
    fun explicitBrowserCredentialMarkersAreRedacted() {
        assertEquals(
            "민감 정보 숨김",
            ProjectionDiagnosticsUiState.safeDiagnosticText(
                "X-Browser-Credential: browser-secret-value",
            ),
        )
    }

    @Test
    fun environmentDiagnosticsExposePolicyAndCountersWithoutCoordinates() {
        val snapshot = ProjectionEnvironmentDiagnosticsUiSnapshot.from(
            nightModeDecision = NightModeDecision(
                isNight = true,
                source = NightModeDecisionSource.SOLAR_LOCATION,
                evaluatedAt = Instant.parse("2026-08-02T12:00:00Z"),
            ),
            audioTier = AudioStreamAccessTier.FREE,
            audioTracks = mapOf(
                BrowserAudioTrack.MEDIA to BrowserAudioTrackSnapshot(
                    format = Pcm16Format(16_000, 1),
                    packetsPublished = 7,
                    bytesPublished = 224,
                    packetsDropped = 2,
                    activeSubscribers = 1,
                    lastPacketAtNanos = 10L,
                ),
            ),
        )

        assertTrue(snapshot.isNight == true)
        assertEquals(AudioStreamAccessTier.FREE, snapshot.audioTier)
        assertEquals(7L, snapshot.audioPacketsPublished)
        assertEquals(2L, snapshot.audioPacketsDropped)
        assertEquals(1, snapshot.audioSubscribers)
        assertTrue(snapshot.audioFormats.contains("16 kHz 모노"))
        assertFalse(snapshot.toString().contains("latitude", ignoreCase = true))
        assertFalse(snapshot.toString().contains("longitude", ignoreCase = true))
    }

    private companion object {
        const val PAIRING_SECRET = "92845173"
        const val ICE_SECRET = "ice-password-do-not-display"
        const val PRIVATE_KEY_SECRET = "private-key-material"
    }
}
