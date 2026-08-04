/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.pebble.tecomheadunit.session

/** Coarse, user-safe Android Auto connection state shared by every UI surface. */
enum class AndroidAutoConnectionState {
    CONNECTED,
    RECONNECTING,
    DISCONNECTED,
}

/**
 * Deliberately bounded reasons. Raw exception messages, endpoints and peer data
 * must never be copied into the browser status response.
 */
enum class AndroidAutoConnectionReason(
    val wireName: String,
    val userMessage: String,
) {
    STREAMING("streaming", "Android Auto 영상 수신 중"),
    LINK_READY("link_ready", "Android Auto 연결 협상 완료 · 영상 대기 중"),
    WAITING_FOR_PHONE("waiting_for_phone", "휴대전화 연결 대기 중"),
    RETRYING("retrying", "Android Auto 연결을 다시 시도하는 중"),
    DECODER_RECOVERY("decoder_recovery", "Android Auto 영상 디코더 복구 중"),
    PHONE_ENDED_SESSION("phone_ended_session", "휴대전화에서 Android Auto 연결을 종료했습니다"),
    HEAD_UNIT_SERVER_STOPPED(
        "head_unit_server_stopped",
        "이 휴대전화의 Android Auto Head Unit Server가 꺼져 있습니다",
    ),
    NETWORK_INTERRUPTED("network_interrupted", "휴대전화와의 연결이 중단되었습니다"),
    AUTHENTICATION_FAILED("authentication_failed", "Android Auto 인증에 실패했습니다"),
    INCOMPATIBLE_VERSION("incompatible_version", "Android Auto 버전이 호환되지 않습니다"),
    PROTOCOL_ERROR("protocol_error", "Android Auto 통신 오류가 발생했습니다"),
    DECODER_UNAVAILABLE("decoder_unavailable", "이 기기에서 Android Auto 영상 디코더를 사용할 수 없습니다"),
    DISPLAY_UNAVAILABLE("display_unavailable", "프로젝션 화면을 사용할 수 없습니다"),
    NOT_CONFIGURED("not_configured", "Android Auto 연결이 설정되지 않았습니다"),
    SESSION_STOPPED("session_stopped", "Android Auto 연결이 중지되었습니다"),
    UNKNOWN("unknown", "Android Auto 연결이 끊어졌습니다"),
}

data class AndroidAutoConnectionStatus(
    val state: AndroidAutoConnectionState = AndroidAutoConnectionState.DISCONNECTED,
    val reason: AndroidAutoConnectionReason = AndroidAutoConnectionReason.SESSION_STOPPED,
    val lastFrameAtEpochMillis: Long? = null,
    val lastFrameAtElapsedRealtimeMillis: Long? = null,
    val changedAtEpochMillis: Long = 0L,
) {
    fun lastFrameAgeMillis(nowElapsedRealtimeMillis: Long): Long? =
        lastFrameAtElapsedRealtimeMillis?.let {
            (nowElapsedRealtimeMillis - it).coerceAtLeast(0L)
        }
}

/** Pure reducer so status classification remains deterministic and unit-testable. */
internal object AndroidAutoConnectionStatusReducer {
    fun reduce(
        previous: AndroidAutoConnectionStatus,
        nativeStatus: String,
        nowEpochMillis: Long,
        nowElapsedRealtimeMillis: Long,
    ): AndroidAutoConnectionStatus {
        val normalized = structuredAasdkEvent(nativeStatus) ?: return previous
        val transition = when {
            "VIDEO_MEDIA_ACCEPTED" in normalized ->
                AndroidAutoConnectionState.CONNECTED to AndroidAutoConnectionReason.STREAMING
            normalized.containsAny(
                "AUTHENTICATED",
                "SERVICE_DISCOVERY_RESPONSE_SENT",
                "CHANNEL_OPEN",
                "VIDEO_DECODER_READY",
                "AV_SETUP_READY",
                "AV_STREAM_STARTED",
                "VIDEO_FOCUSED",
                "INPUT_BINDING_READY",
            ) -> {
                if (previous.state == AndroidAutoConnectionState.CONNECTED) return previous
                AndroidAutoConnectionState.RECONNECTING to AndroidAutoConnectionReason.LINK_READY
            }
            normalized.containsAny("RECONNECTING", "RETRYING", "RETRY_SCHEDULED") ->
                AndroidAutoConnectionState.RECONNECTING to AndroidAutoConnectionReason.RETRYING
            "DECODER_RECOVERY" in normalized ->
                AndroidAutoConnectionState.RECONNECTING to AndroidAutoConnectionReason.DECODER_RECOVERY
            "CONNECTING" in normalized ->
                AndroidAutoConnectionState.RECONNECTING to AndroidAutoConnectionReason.WAITING_FOR_PHONE
            "PEER_SHUTDOWN" in normalized ->
                AndroidAutoConnectionState.DISCONNECTED to AndroidAutoConnectionReason.PHONE_ENDED_SESSION
            "HEAD_UNIT_SERVER_NOT_RUNNING" in normalized ->
                AndroidAutoConnectionState.DISCONNECTED to AndroidAutoConnectionReason.HEAD_UNIT_SERVER_STOPPED
            normalized.containsAny(
                "TRANSPORT_DISCONNECTED",
                "PEER_DISCONNECTED",
                "CONNECT_FAILED",
                "SOCKET_DISCONNECTED",
                "READ_TIMEOUT",
                "PING_TIMEOUT",
            ) ->
                AndroidAutoConnectionState.DISCONNECTED to AndroidAutoConnectionReason.NETWORK_INTERRUPTED
            normalized.containsAny("TLS_POLICY_REJECTED", "TLS_HANDSHAKE_FAILED", "CREDENTIAL_UNAVAILABLE") ->
                AndroidAutoConnectionState.DISCONNECTED to AndroidAutoConnectionReason.AUTHENTICATION_FAILED
            "VERSION_MISMATCH" in normalized ->
                AndroidAutoConnectionState.DISCONNECTED to AndroidAutoConnectionReason.INCOMPATIBLE_VERSION
            "PROTOCOL_ERROR" in normalized ->
                AndroidAutoConnectionState.DISCONNECTED to AndroidAutoConnectionReason.PROTOCOL_ERROR
            "DECODER_UNAVAILABLE" in normalized ->
                AndroidAutoConnectionState.DISCONNECTED to AndroidAutoConnectionReason.DECODER_UNAVAILABLE
            "SURFACE_LOST" in normalized ->
                AndroidAutoConnectionState.DISCONNECTED to AndroidAutoConnectionReason.DISPLAY_UNAVAILABLE
            normalized.containsAny("DISABLED", "SAFETY_OR_CREDENTIAL_REJECTED") ->
                AndroidAutoConnectionState.DISCONNECTED to AndroidAutoConnectionReason.NOT_CONFIGURED
            normalized.containsAny("SESSION_FAILED", "PREFLIGHT_FAILED") ->
                AndroidAutoConnectionState.DISCONNECTED to AndroidAutoConnectionReason.UNKNOWN
            else -> null
        } ?: return previous

        val receivedFrame = "VIDEO_MEDIA_ACCEPTED" in normalized
        return previous.copy(
            state = transition.first,
            reason = transition.second,
            lastFrameAtEpochMillis = if (receivedFrame) nowEpochMillis else previous.lastFrameAtEpochMillis,
            lastFrameAtElapsedRealtimeMillis =
                if (receivedFrame) nowElapsedRealtimeMillis else previous.lastFrameAtElapsedRealtimeMillis,
            changedAtEpochMillis = if (
                previous.state != transition.first || previous.reason != transition.second
            ) {
                nowEpochMillis
            } else {
                previous.changedAtEpochMillis
            },
        )
    }

    private fun structuredAasdkEvent(nativeStatus: String): String? {
        val normalized = nativeStatus.uppercase()
        val markerOffset = normalized.lastIndexOf(AASDK_EVENT_MARKER)
        if (markerOffset < 0) return null
        return normalized
            .substring(markerOffset + AASDK_EVENT_MARKER.length)
            .trim()
            .takeIf(String::isNotEmpty)
    }

    private fun String.containsAny(vararg needles: String): Boolean = needles.any(::contains)

    private const val AASDK_EVENT_MARKER = "AASDK "
}
