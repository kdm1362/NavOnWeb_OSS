/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.pebble.tecomheadunit.ui

import com.pebble.tecomheadunit.audio.AudioStreamAccessTier
import com.pebble.tecomheadunit.browser.audio.BrowserAudioTrack
import com.pebble.tecomheadunit.browser.audio.BrowserAudioTrackSnapshot
import com.pebble.tecomheadunit.browser.webrtc.codec.WebRtcOutboundCodecPolicy
import com.pebble.tecomheadunit.browser.BrowserWebRtcCapabilities
import com.pebble.tecomheadunit.openauto.ProjectionProfileSnapshot
import com.pebble.tecomheadunit.openauto.ProjectionAccessTier
import com.pebble.tecomheadunit.openauto.ProjectionVideoProfile
import com.pebble.tecomheadunit.openauto.sensor.NightModeDecision
import com.pebble.tecomheadunit.openauto.sensor.NightModeDecisionSource
import com.pebble.tecomheadunit.session.AndroidAutoConnectionState
import com.pebble.tecomheadunit.session.SessionPhase
import com.pebble.tecomheadunit.session.SessionUiState
import java.util.Locale

enum class WebRtcCodecPreferenceOption(
    val storageValue: String,
    val displayName: String,
    val description: String,
) {
    AUTO("auto", "자동", "기기와 상대 브라우저가 함께 지원하는 코덱을 자동 선택"),
    H264("h264", "H.264", "호환성을 우선하는 하드웨어 코덱"),
    VP8("vp8", "VP8", "WebRTC 기본 호환 코덱"),
    VP9("vp9", "VP9", "더 높은 압축 효율을 지원하는 기기에서 사용"),
    AV1("av1", "AV1", "지원되는 최신 기기에서 가장 높은 압축 효율 제공"),
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
)

data class ProjectionControlUiState(
    val activeProfile: ProjectionVideoProfile = ProjectionVideoProfile.FREE_800X480,
    val profileOptions: List<ProjectionProfileOptionUi> = emptyList(),
    val profileStatus: String = "프로젝션 서비스 연결을 기다리는 중입니다.",
    val profileChangeInProgress: Boolean = false,
    val codecPreference: WebRtcCodecPreferenceOption = WebRtcCodecPreferenceOption.AUTO,
    val codecStatus: String = CODEC_POLICY_PENDING_MESSAGE,
) {
    companion object {
        const val CODEC_POLICY_PENDING_MESSAGE =
            "선택한 코덱은 WebRTC 협상에 적용되며 연결 중이면 자동으로 다시 협상합니다."

        fun from(
            snapshot: ProjectionProfileSnapshot?,
            serviceBound: Boolean,
            profileFeedback: String,
            codecPreference: WebRtcCodecPreferenceOption,
            codecFeedback: String,
            premiumEntitled: Boolean =
                snapshot?.entitlementTier == ProjectionAccessTier.PREMIUM,
        ): ProjectionControlUiState {
            val active = snapshot?.activeProfile ?: ProjectionVideoProfile.FREE_800X480
            val requested = snapshot?.requestedProfile ?: active
            val changing = snapshot?.isAutomaticReconnectPending == true
            val options = ProjectionVideoProfile.entries.map { profile ->
                val premiumLocked =
                    profile.requiredTier == ProjectionAccessTier.PREMIUM &&
                        !premiumEntitled
                ProjectionProfileOptionUi(
                    profileId = profile.profileId,
                    title = profile.displayTitle(),
                    detail = profile.displayDetail(),
                    selected = profile == requested,
                    active = profile == active,
                    premiumLocked = premiumLocked,
                    enabled = !changing && (premiumLocked || serviceBound),
                )
            }
            val status = profileFeedback.ifBlank {
                when {
                    !serviceBound -> "프로젝션 서비스에 연결되지 않았습니다."
                    snapshot == null -> "프로필 상태를 확인하는 중입니다."
                    changing ->
                        "${requested.displayTitle()} 프로필로 자동 재연결하여 적용하는 중입니다."
                    else -> "현재 ${active.displayTitle()} 프로필이 적용되어 있습니다."
                }
            }
            return ProjectionControlUiState(
                activeProfile = active,
                profileOptions = options,
                profileStatus = status,
                profileChangeInProgress = changing,
                codecPreference = codecPreference.takeIf { it.isSelectable }
                    ?: WebRtcCodecPreferenceOption.AUTO,
                codecStatus = codecFeedback.ifBlank { CODEC_POLICY_PENDING_MESSAGE },
            )
        }
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
                    NightModeDecisionSource.SOLAR_LOCATION -> "현재 위치의 일출·일몰"
                    NightModeDecisionSource.DEVICE_HINT_NO_LOCATION -> "기기 화면 모드(위치 없음)"
                    NightModeDecisionSource.DEVICE_HINT_UNUSABLE_LOCATION ->
                        "기기 화면 모드(위치 사용 불가)"
                    NightModeDecisionSource.FAIL_DARK_NO_LOCATION -> "안전 야간 기본값(위치 없음)"
                    NightModeDecisionSource.FAIL_DARK_UNUSABLE_LOCATION ->
                        "안전 야간 기본값(위치 사용 불가)"
                    null -> "판정 대기"
                },
                audioTier = audioTier,
                audioFormats = audioTracks.entries
                    .sortedBy { it.key.ordinal }
                    .joinToString(" / ") { (track, snapshot) ->
                        val channels = if (snapshot.format.channelCount == 1) "모노" else "스테레오"
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
                    ?.let { "실행 중 · ${safeDiagnosticText(it)}" }
                    ?: "실행 중 · 주소 확인 중"
                SessionPhase.STARTING -> "시작 중"
                SessionPhase.ERROR -> "오류 · ${safeDiagnosticText(session.message)}"
                SessionPhase.IDLE -> "중지됨"
            }
            val androidAutoText = when (connection.state) {
                AndroidAutoConnectionState.CONNECTED -> "연결됨"
                AndroidAutoConnectionState.RECONNECTING -> "다시 연결 중"
                AndroidAutoConnectionState.DISCONNECTED -> "연결 끊김"
            } + " · ${connection.reason.userMessage}"
            val lastFrameText = connection.lastFrameAgeMillis(nowElapsedRealtimeMillis)?.let { age ->
                when {
                    age < 1_000L -> "방금 수신"
                    age < 60_000L -> "${age / 1_000L}초 전 수신"
                    else -> "${age / 60_000L}분 전 수신"
                }
            } ?: "아직 수신된 영상 없음"
            val webRtcText = when {
                webRtcCapabilities == null -> "상태 수집 대기"
                !webRtcCapabilities.available ->
                    "사용 불가 · ${safeDiagnosticText(webRtcCapabilities.detail)}"
                else -> {
                    val codecs = webRtcCapabilities.codecs
                        .joinToString(" / ") { it.uppercase(Locale.ROOT) }
                        .ifBlank { "코덱 확인 중" }
                    "사용 가능 · $codecs · ${safeDiagnosticText(webRtcCapabilities.detail)}"
                }
            }
            val activeProfile = profileSnapshot?.activeProfile
                ?: ProjectionVideoProfile.FREE_800X480
            val touchText = session.lastTouch?.let { touch ->
                "${touch.phase} · x=${touch.x} · y=${touch.y} · pointer=${touch.pointerId}"
            } ?: "수집된 브라우저 입력 없음"
            val nightModeText = environment?.let {
                val mode = when (it.isNight) {
                    true -> "야간"
                    false -> "주간"
                    null -> "판정 대기"
                }
                "$mode · ${it.nightModeSource}"
            } ?: "판정 대기"
            val audioText = environment?.let {
                val tier = when (it.audioTier) {
                    AudioStreamAccessTier.FREE -> "무료 · 서버 강제 모노 16 kHz"
                    AudioStreamAccessTier.PREMIUM -> "유료 · 원본 채널/샘플레이트"
                    null -> "준비 대기"
                }
                val formats = it.audioFormats.ifBlank { "PCM 대기" }
                "$tier · $formats · 패킷 ${it.audioPacketsPublished} · " +
                    "브라우저 ${it.audioSubscribers} · 드롭 ${it.audioPacketsDropped}"
            } ?: "준비 대기"
            return ProjectionDiagnosticsUiState(
                rows = listOf(
                    DiagnosticRowUi("앱 서버", serverText),
                    DiagnosticRowUi("Android Auto", androidAutoText),
                    DiagnosticRowUi("마지막 영상", lastFrameText),
                    DiagnosticRowUi("WebRTC", webRtcText),
                    DiagnosticRowUi("주·야간 모드", nightModeText),
                    DiagnosticRowUi("브라우저 음성", audioText),
                    DiagnosticRowUi("활성 프로필", activeProfile.displayDiagnostic()),
                    DiagnosticRowUi("마지막 입력 수집", touchText),
                    DiagnosticRowUi("네이티브 상태", safeDiagnosticText(session.nativeStatus)),
                ),
            )
        }

        internal fun safeDiagnosticText(value: String): String {
            val trimmed = value.trim().take(MAX_DIAGNOSTIC_CHARACTERS)
            if (trimmed.isEmpty()) return "세부 정보 없음"
            val normalized = trimmed.lowercase(Locale.ROOT)
            return if (SENSITIVE_MARKERS.any(normalized::contains)) {
                "민감 정보 숨김"
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
    ProjectionVideoProfile.FREE_800X480 -> "기본 800×480"
    ProjectionVideoProfile.PREMIUM_720P -> "HD 1280×720"
    ProjectionVideoProfile.PREMIUM_1080P -> "Full HD 1920×1080"
}

private fun ProjectionVideoProfile.displayDetail(): String =
    "Android Auto ${androidAutoFramesPerSecond} FPS · " +
        "WebRTC ${webRtcFramesPerSecond} FPS · " +
        "${formatBitrate(webRtcStartBitrateBps)} 시작 / ${formatBitrate(webRtcMaxBitrateBps)} 최대"

private fun ProjectionVideoProfile.displayDiagnostic(): String =
    "${displayTitle()} · AA ${androidAutoFramesPerSecond} FPS · " +
        "WebRTC ${webRtcFramesPerSecond} FPS"

private fun formatBitrate(bitsPerSecond: Int): String =
    String.format(Locale.ROOT, "%.1f Mbps", bitsPerSecond / 1_000_000.0)
