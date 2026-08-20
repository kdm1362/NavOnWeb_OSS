/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.eigenkodex.navonweb.ui

import android.content.Context
import androidx.annotation.StringRes
import com.eigenkodex.navonweb.R
import com.eigenkodex.navonweb.audio.AudioStreamAccessTier
import com.eigenkodex.navonweb.browser.audio.BrowserAudioTrack
import com.eigenkodex.navonweb.browser.audio.BrowserAudioTrackSnapshot
import com.eigenkodex.navonweb.browser.webrtc.codec.WebRtcOutboundCodecPolicy
import com.eigenkodex.navonweb.browser.BrowserWebRtcCapabilities
import com.eigenkodex.navonweb.openauto.ProjectionProfileSnapshot
import com.eigenkodex.navonweb.openauto.ProjectionAccessTier
import com.eigenkodex.navonweb.openauto.ProjectionDpiSettingsSnapshot
import com.eigenkodex.navonweb.openauto.ProjectionVideoProfile
import com.eigenkodex.navonweb.openauto.sensor.NightModeDecision
import com.eigenkodex.navonweb.openauto.sensor.NightModeDecisionSource
import com.eigenkodex.navonweb.session.AndroidAutoConnectionState
import com.eigenkodex.navonweb.session.SessionPhase
import com.eigenkodex.navonweb.session.SessionUiState
import java.text.NumberFormat
import java.util.Locale

enum class WebRtcCodecPreferenceOption(
    val storageValue: String,
    @param:StringRes @get:StringRes val displayNameRes: Int,
    @param:StringRes @get:StringRes val descriptionRes: Int,
) {
    AUTO("auto", R.string.codec_auto_name, R.string.codec_auto_description),
    H264("h264", R.string.codec_h264_name, R.string.codec_h264_description),
    VP8("vp8", R.string.codec_vp8_name, R.string.codec_vp8_description),
    VP9("vp9", R.string.codec_vp9_name, R.string.codec_vp9_description),
    AV1("av1", R.string.codec_av1_name, R.string.codec_av1_description),
    ;

    /** Mirrors the compile-time outbound encoder policy used by the WebRTC sender. */
    val isSelectable: Boolean
        get() = this != H264 || WebRtcOutboundCodecPolicy.H264_ENCODER_ENABLED

    companion object {
        val selectableOptions: List<WebRtcCodecPreferenceOption> = entries.filter { it.isSelectable }

        fun fromStorageValue(value: String?): WebRtcCodecPreferenceOption {
            val normalized = value?.trim()?.lowercase(Locale.ROOT).orEmpty()
            val parsed = entries.firstOrNull { it.storageValue == normalized } ?: AUTO
            return parsed.takeIf(WebRtcCodecPreferenceOption::isSelectable) ?: AUTO
        }
    }
}

data class ProjectionProfileOptionUi(
    val profileId: String,
    val title: String,
    val detail: String,
    val selected: Boolean,
    val active: Boolean,
    /** Locked rows stay interactive so selecting them can open the premium purchase prompt. */
    val premiumLocked: Boolean,
    val enabled: Boolean,
    val densityDpi: Int = ProjectionVideoProfile.FREE_800X480.dpi,
    val recommendedDensityDpi: Int = ProjectionVideoProfile.FREE_800X480.dpi,
    val appliedLandscapeDensityDpi: Int = ProjectionVideoProfile.FREE_800X480.dpi,
    val appliedPortraitDensityDpi: Int = ProjectionVideoProfile.FREE_800X480
        .effectiveDensityDpi(
            ProjectionVideoProfile.FREE_800X480.dpi,
            ProjectionVideoProfile.FREE_800X480.portraitViewport,
        ),
    val densityControlEnabled: Boolean = true,
)

data class ProjectionControlUiState(
    val activeProfile: ProjectionVideoProfile = ProjectionVideoProfile.FREE_800X480,
    val profileOptions: List<ProjectionProfileOptionUi> = emptyList(),
    val profileStatus: String = "",
    val profileChangeInProgress: Boolean = false,
    val dpiStatus: String = "",
    val codecPreference: WebRtcCodecPreferenceOption = WebRtcCodecPreferenceOption.AUTO,
    val codecStatus: String = "",
) {
    companion object {
        fun from(
            snapshot: ProjectionProfileSnapshot?,
            serviceBound: Boolean,
            profileFeedback: String,
            dpiSettings: ProjectionDpiSettingsSnapshot = ProjectionDpiSettingsSnapshot.recommended(),
            dpiFeedback: String = "",
            codecPreference: WebRtcCodecPreferenceOption,
            codecFeedback: String,
            textResolver: ProjectionControlTextResolver,
            premiumEntitled: Boolean =
                snapshot?.entitlementTier == ProjectionAccessTier.PREMIUM,
        ): ProjectionControlUiState {
            val active = snapshot?.activeProfile ?: ProjectionVideoProfile.FREE_800X480
            val requested = snapshot?.requestedProfile ?: active
            val changing = snapshot?.isAutomaticReconnectPending == true
            val options = ProjectionVideoProfile.entries.map { profile ->
                val configuredDensityDpi = dpiSettings.densityDpi(profile)
                val premiumLocked =
                    profile.requiredTier == ProjectionAccessTier.PREMIUM &&
                        !premiumEntitled
                ProjectionProfileOptionUi(
                    profileId = profile.profileId,
                    title = textResolver.profileTitle(profile),
                    detail = textResolver.profileDetail(profile),
                    selected = profile == requested,
                    active = profile == active,
                    premiumLocked = premiumLocked,
                    enabled = !changing && (premiumLocked || serviceBound),
                    densityDpi = configuredDensityDpi,
                    recommendedDensityDpi = profile.dpi,
                    appliedLandscapeDensityDpi = profile.effectiveDensityDpi(
                        configuredDensityDpi,
                        profile.landscapeViewport,
                    ),
                    appliedPortraitDensityDpi = profile.effectiveDensityDpi(
                        configuredDensityDpi,
                        profile.portraitViewport,
                    ),
                    densityControlEnabled = !changing,
                )
            }
            val status = profileFeedback.ifBlank {
                when {
                    !serviceBound -> textResolver.serviceNotConnected
                    snapshot == null -> textResolver.profileChecking
                    changing ->
                        textResolver.profileReconnecting(textResolver.profileTitle(requested))
                    else -> textResolver.profileActive(textResolver.profileTitle(active))
                }
            }
            return ProjectionControlUiState(
                activeProfile = active,
                profileOptions = options,
                profileStatus = status,
                profileChangeInProgress = changing,
                dpiStatus = dpiFeedback,
                codecPreference = codecPreference.takeIf { it.isSelectable }
                    ?: WebRtcCodecPreferenceOption.AUTO,
                codecStatus = codecFeedback.ifBlank { textResolver.codecPolicyPending },
            )
        }
    }
}

interface ProjectionControlTextResolver {
    val serviceNotConnected: String
    val profileChecking: String
    val codecPolicyPending: String

    fun profileTitle(profile: ProjectionVideoProfile): String

    fun profileDetail(profile: ProjectionVideoProfile): String

    fun profileReconnecting(profileTitle: String): String

    fun profileActive(profileTitle: String): String
}

class AndroidProjectionControlTextResolver(context: Context) : ProjectionControlTextResolver {
    private val applicationContext = context.applicationContext

    override val serviceNotConnected: String
        get() = applicationContext.getString(R.string.projection_profile_service_not_connected)
    override val profileChecking: String
        get() = applicationContext.getString(R.string.projection_profile_checking)
    override val codecPolicyPending: String
        get() = applicationContext.getString(R.string.codec_policy_pending)

    override fun profileTitle(profile: ProjectionVideoProfile): String = applicationContext.getString(
        when (profile) {
            ProjectionVideoProfile.FREE_800X480 -> R.string.projection_profile_free_title
            ProjectionVideoProfile.PREMIUM_720P -> R.string.projection_profile_720p_title
            ProjectionVideoProfile.PREMIUM_1080P -> R.string.projection_profile_1080p_title
        },
    )

    override fun profileDetail(profile: ProjectionVideoProfile): String = applicationContext.getString(
        R.string.projection_profile_detail,
        formatBitrate(profile.webRtcStartBitrateBps),
        formatBitrate(profile.webRtcMaxBitrateBps),
    )

    override fun profileReconnecting(profileTitle: String): String = applicationContext.getString(
        R.string.projection_profile_reconnecting_status,
        profileTitle,
    )

    override fun profileActive(profileTitle: String): String = applicationContext.getString(
        R.string.projection_profile_active_status,
        profileTitle,
    )

    private fun formatBitrate(bitsPerSecond: Int): String {
        val locale = applicationContext.resources.configuration.locales[0]
        val number = NumberFormat.getNumberInstance(locale).apply {
            minimumFractionDigits = 1
            maximumFractionDigits = 1
        }.format(bitsPerSecond / 1_000_000.0)
        return applicationContext.getString(R.string.projection_bitrate_mbps, number)
    }
}

data class DiagnosticRowUi(
    val label: String,
    val value: String,
)

/** Copy-only diagnostic view: ICE URLs, usernames and credentials are intentionally discarded. */
data class WebRtcDiagnosticsUiSnapshot(
    val available: Boolean,
    val codecs: List<String>,
    val detail: String,
) {
    companion object {
        fun from(capabilities: BrowserWebRtcCapabilities): WebRtcDiagnosticsUiSnapshot =
            WebRtcDiagnosticsUiSnapshot(
                available = capabilities.available,
                codecs = capabilities.codecs.map { it.wireName },
                detail = capabilities.detail,
            )
    }
}

/** Sanitized phone-only diagnostics; it deliberately contains no coordinates or credentials. */
data class ProjectionEnvironmentDiagnosticsUiSnapshot(
    val isNight: Boolean?,
    val nightModeSource: String,
    val audioTier: AudioStreamAccessTier?,
    val audioFormats: String,
    val audioPacketsPublished: Long,
    val audioPacketsDropped: Long,
    val audioSubscribers: Int,
) {
    companion object {
        fun from(
            nightModeDecision: NightModeDecision?,
            audioTier: AudioStreamAccessTier?,
            audioTracks: Map<BrowserAudioTrack, BrowserAudioTrackSnapshot>,
        ): ProjectionEnvironmentDiagnosticsUiSnapshot =
            ProjectionEnvironmentDiagnosticsUiSnapshot(
                isNight = nightModeDecision?.isNight,
                nightModeSource = when (nightModeDecision?.source) {
                    NightModeDecisionSource.SOLAR_LOCATION -> "local sunrise and sunset"
                    NightModeDecisionSource.DEVICE_HINT_NO_LOCATION -> "device theme (no location)"
                    NightModeDecisionSource.DEVICE_HINT_UNUSABLE_LOCATION ->
                        "device theme (location unavailable)"
                    NightModeDecisionSource.FAIL_DARK_NO_LOCATION -> "safe night default (no location)"
                    NightModeDecisionSource.FAIL_DARK_UNUSABLE_LOCATION ->
                        "safe night default (location unavailable)"
                    null -> "pending"
                },
                audioTier = audioTier,
                audioFormats = audioTracks.entries
                    .sortedBy { it.key.ordinal }
                    .joinToString(" / ") { (track, snapshot) ->
                        val channels = if (snapshot.format.channelCount == 1) "mono" else "stereo"
                        "${track.wireName} ${snapshot.format.sampleRateHz / 1_000} kHz $channels"
                    },
                audioPacketsPublished = audioTracks.values.sumOf { it.packetsPublished },
                audioPacketsDropped = audioTracks.values.sumOf { it.packetsDropped },
                audioSubscribers = audioTracks.values.sumOf { it.activeSubscribers },
            )
    }
}

/**
 * Deliberately accepts no pairing code, browser credential, ICE credential, certificate or key.
 * This keeps secrets outside both the Compose state and its diagnostic rendering path.
 */
data class ProjectionDiagnosticsUiState(
    val rows: List<DiagnosticRowUi>,
) {
    companion object {
        fun from(
            session: SessionUiState,
            profileSnapshot: ProjectionProfileSnapshot?,
            webRtcCapabilities: WebRtcDiagnosticsUiSnapshot?,
            nowElapsedRealtimeMillis: Long,
            environment: ProjectionEnvironmentDiagnosticsUiSnapshot? = null,
        ): ProjectionDiagnosticsUiState {
            val connection = session.androidAutoConnection
            val serverText = when (session.phase) {
                SessionPhase.READY -> session.browserUrl
                    ?.takeIf(String::isNotBlank)
                    ?.let { "running · ${safeDiagnosticText(it)}" }
                    ?: "running · checking address"
                SessionPhase.STARTING -> "starting"
                SessionPhase.ERROR -> "error · ${safeDiagnosticText(session.message)}"
                SessionPhase.IDLE -> "stopped"
            }
            val androidAutoText = when (connection.state) {
                AndroidAutoConnectionState.CONNECTED -> "connected"
                AndroidAutoConnectionState.RECONNECTING -> "reconnecting"
                AndroidAutoConnectionState.DISCONNECTED -> "disconnected"
            } + " · ${connection.reason.wireName}"
            val lastFrameText = connection.lastFrameAgeMillis(nowElapsedRealtimeMillis)?.let { age ->
                when {
                    age < 1_000L -> "received now"
                    age < 60_000L -> "received ${age / 1_000L} seconds ago"
                    else -> "received ${age / 60_000L} minutes ago"
                }
            } ?: "no video received"
            val webRtcText = when {
                webRtcCapabilities == null -> "status pending"
                !webRtcCapabilities.available ->
                    "unavailable · ${safeDiagnosticText(webRtcCapabilities.detail)}"
                else -> {
                    val codecs = webRtcCapabilities.codecs
                        .joinToString(" / ") { it.uppercase(Locale.ROOT) }
                        .ifBlank { "checking codecs" }
                    "available · $codecs · ${safeDiagnosticText(webRtcCapabilities.detail)}"
                }
            }
            val activeProfile = profileSnapshot?.activeProfile
                ?: ProjectionVideoProfile.FREE_800X480
            val touchText = session.lastTouch?.let { touch ->
                "${touch.phase} · x=${touch.x} · y=${touch.y} · pointer=${touch.pointerId}"
            } ?: "no browser input collected"
            val nightModeText = environment?.let {
                val mode = when (it.isNight) {
                    true -> "night"
                    false -> "day"
                    null -> "pending"
                }
                "$mode · ${it.nightModeSource}"
            } ?: "pending"
            val audioText = environment?.let {
                val tier = when (it.audioTier) {
                    AudioStreamAccessTier.FREE -> "free · server-forced mono 16 kHz"
                    AudioStreamAccessTier.PREMIUM -> "premium · source channels/sample rate"
                    null -> "pending"
                }
                val formats = it.audioFormats.ifBlank { "PCM pending" }
                "$tier · $formats · packets ${it.audioPacketsPublished} · " +
                    "browsers ${it.audioSubscribers} · dropped ${it.audioPacketsDropped}"
            } ?: "pending"
            return ProjectionDiagnosticsUiState(
                rows = listOf(
                    DiagnosticRowUi("App server", serverText),
                    DiagnosticRowUi("Android Auto", androidAutoText),
                    DiagnosticRowUi("Last video", lastFrameText),
                    DiagnosticRowUi("WebRTC", webRtcText),
                    DiagnosticRowUi("Day/night mode", nightModeText),
                    DiagnosticRowUi("Browser audio", audioText),
                    DiagnosticRowUi("Active profile", activeProfile.displayDiagnostic()),
                    DiagnosticRowUi("Last input", touchText),
                    DiagnosticRowUi("Native status", safeDiagnosticText(session.nativeStatus)),
                ),
            )
        }

        internal fun safeDiagnosticText(value: String): String {
            val trimmed = value.trim().take(MAX_DIAGNOSTIC_CHARACTERS)
            if (trimmed.isEmpty()) return "no detail"
            val normalized = trimmed.lowercase(Locale.ROOT)
            return if (SENSITIVE_MARKERS.any(normalized::contains)) {
                "sensitive detail hidden"
            } else {
                trimmed
            }
        }

        private const val MAX_DIAGNOSTIC_CHARACTERS = 240
        private val SENSITIVE_MARKERS = listOf(
            "private key-----",
            "x-browser-credential",
            "browsercredential",
            "browser credential",
            "pairing_credential",
            "pairing credential",
            "authorization: bearer",
        )
    }
}

private fun ProjectionVideoProfile.displayTitle(): String = when (this) {
    ProjectionVideoProfile.FREE_800X480 -> "Basic 800×480"
    ProjectionVideoProfile.PREMIUM_720P -> "HD 1280×720"
    ProjectionVideoProfile.PREMIUM_1080P -> "Full HD 1920×1080"
}

private fun ProjectionVideoProfile.displayDiagnostic(): String = displayTitle()
