/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.pebble.tecomheadunit.session

import androidx.annotation.StringRes
import com.pebble.tecomheadunit.R

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
    @param:StringRes @get:StringRes val messageRes: Int,
) {
    STREAMING("streaming", R.string.android_auto_reason_streaming),
    LINK_READY("link_ready", R.string.android_auto_reason_link_ready),
    WAITING_FOR_PHONE("waiting_for_phone", R.string.android_auto_reason_waiting_for_phone),
    RETRYING("retrying", R.string.android_auto_reason_retrying),
    DECODER_RECOVERY("decoder_recovery", R.string.android_auto_reason_decoder_recovery),
    PHONE_ENDED_SESSION("phone_ended_session", R.string.android_auto_reason_phone_ended_session),
    HEAD_UNIT_SERVER_STOPPED(
        "head_unit_server_stopped",
        R.string.android_auto_reason_head_unit_server_stopped,
    ),
    NETWORK_INTERRUPTED("network_interrupted", R.string.android_auto_reason_network_interrupted),
    AUTHENTICATION_FAILED("authentication_failed", R.string.android_auto_reason_authentication_failed),
    INCOMPATIBLE_VERSION("incompatible_version", R.string.android_auto_reason_incompatible_version),
    PROTOCOL_ERROR("protocol_error", R.string.android_auto_reason_protocol_error),
    DECODER_UNAVAILABLE("decoder_unavailable", R.string.android_auto_reason_decoder_unavailable),
    DISPLAY_UNAVAILABLE("display_unavailable", R.string.android_auto_reason_display_unavailable),
    NOT_CONFIGURED("not_configured", R.string.android_auto_reason_not_configured),
    SESSION_STOPPED("session_stopped", R.string.android_auto_reason_session_stopped),
    UNKNOWN("unknown", R.string.android_auto_reason_unknown),
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
