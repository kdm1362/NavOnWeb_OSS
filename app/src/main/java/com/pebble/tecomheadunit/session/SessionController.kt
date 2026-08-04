/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.pebble.tecomheadunit.session

import android.os.SystemClock
import com.pebble.tecomheadunit.core.OpenAutoTouchEvent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

enum class SessionPhase {
    IDLE,
    STARTING,
    READY,
    ERROR,
}

data class SessionUiState(
    val phase: SessionPhase = SessionPhase.IDLE,
    val message: String = "서비스가 중지되어 있습니다.",
    val browserUrl: String? = null,
    val pairingCode: String? = null,
    val nativeStatus: String = "확인 중",
    val androidAutoConnection: AndroidAutoConnectionStatus = AndroidAutoConnectionStatus(),
    val lastTouch: OpenAutoTouchEvent? = null,
)

object SessionController {
    private val mutableState = MutableStateFlow(SessionUiState())
    val state: StateFlow<SessionUiState> = mutableState.asStateFlow()

    fun starting(nativeStatus: String) {
        val nowEpochMillis = System.currentTimeMillis()
        mutableState.value = SessionUiState(
            phase = SessionPhase.STARTING,
            message = "로컬 브라우저 게이트를 시작하는 중입니다.",
            nativeStatus = nativeStatus,
            androidAutoConnection = AndroidAutoConnectionStatus(
                state = AndroidAutoConnectionState.RECONNECTING,
                reason = AndroidAutoConnectionReason.WAITING_FOR_PHONE,
                changedAtEpochMillis = nowEpochMillis,
            ),
        )
    }

    fun ready(url: String, pairingCode: String?, nativeStatus: String) {
        val previousConnection = mutableState.value.androidAutoConnection
        mutableState.value = SessionUiState(
            phase = SessionPhase.READY,
            message = "브라우저 연결 준비가 완료되었습니다.",
            browserUrl = url,
            pairingCode = pairingCode,
            nativeStatus = nativeStatus,
            androidAutoConnection = previousConnection,
        )
    }

    /** Replaces only the one-time browser registration code. Existing credentials stay valid. */
    fun updatePairingCode(pairingCode: String?) {
        mutableState.update { current ->
            if (current.phase == SessionPhase.READY) {
                current.copy(pairingCode = pairingCode)
            } else {
                current
            }
        }
    }

    /** Switches between the cloud HTTPS entry point and the always-running LAN fallback. */
    fun updateBrowserUrl(browserUrl: String) {
        mutableState.update { current ->
            if (current.phase == SessionPhase.READY) current.copy(browserUrl = browserUrl) else current
        }
    }

    /** Updates projection transport state without losing browser readiness. */
    fun updateNativeStatus(nativeStatus: String, message: String? = null) {
        val nowEpochMillis = System.currentTimeMillis()
        val nowElapsedRealtimeMillis = SystemClock.elapsedRealtime()
        mutableState.update { current ->
            current.copy(
                nativeStatus = nativeStatus,
                message = message ?: current.message,
                androidAutoConnection = AndroidAutoConnectionStatusReducer.reduce(
                    previous = current.androidAutoConnection,
                    nativeStatus = nativeStatus,
                    nowEpochMillis = nowEpochMillis,
                    nowElapsedRealtimeMillis = nowElapsedRealtimeMillis,
                ),
            )
        }
    }

    fun touch(event: OpenAutoTouchEvent) {
        mutableState.update { it.copy(lastTouch = event) }
    }

    fun error(message: String, nativeStatus: String = mutableState.value.nativeStatus) {
        val nowEpochMillis = System.currentTimeMillis()
        mutableState.value = SessionUiState(
            phase = SessionPhase.ERROR,
            message = message,
            nativeStatus = nativeStatus,
            androidAutoConnection = AndroidAutoConnectionStatus(
                state = AndroidAutoConnectionState.DISCONNECTED,
                reason = AndroidAutoConnectionReason.UNKNOWN,
                lastFrameAtEpochMillis = mutableState.value.androidAutoConnection.lastFrameAtEpochMillis,
                lastFrameAtElapsedRealtimeMillis =
                    mutableState.value.androidAutoConnection.lastFrameAtElapsedRealtimeMillis,
                changedAtEpochMillis = nowEpochMillis,
            ),
        )
    }

    fun idle(message: String = "서비스가 중지되어 있습니다.") {
        val previous = mutableState.value
        mutableState.value = SessionUiState(
            phase = SessionPhase.IDLE,
            message = message,
            nativeStatus = previous.nativeStatus,
            androidAutoConnection = AndroidAutoConnectionStatus(
                state = AndroidAutoConnectionState.DISCONNECTED,
                reason = AndroidAutoConnectionReason.SESSION_STOPPED,
                lastFrameAtEpochMillis = previous.androidAutoConnection.lastFrameAtEpochMillis,
                lastFrameAtElapsedRealtimeMillis =
                    previous.androidAutoConnection.lastFrameAtElapsedRealtimeMillis,
                changedAtEpochMillis = System.currentTimeMillis(),
            ),
        )
    }
}
