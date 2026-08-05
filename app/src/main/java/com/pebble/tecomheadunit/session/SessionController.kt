/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.pebble.tecomheadunit.session

import android.os.SystemClock
import androidx.annotation.StringRes
import com.pebble.tecomheadunit.R
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
    val message: String = "",
    @param:StringRes @get:StringRes val messageRes: Int? = null,
    val browserUrl: String? = null,
    val pairingCode: String? = null,
    val nativeStatus: String = "CHECKING",
    val androidAutoConnection: AndroidAutoConnectionStatus = AndroidAutoConnectionStatus(),
    val lastTouch: OpenAutoTouchEvent? = null,
)

object SessionController {
    private val mutableState = MutableStateFlow(
        SessionUiState(messageRes = R.string.session_service_stopped),
    )
    val state: StateFlow<SessionUiState> = mutableState.asStateFlow()

    fun starting(nativeStatus: String) {
        val nowEpochMillis = System.currentTimeMillis()
        mutableState.value = SessionUiState(
            phase = SessionPhase.STARTING,
            messageRes = R.string.session_local_browser_starting,
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
            messageRes = R.string.session_browser_ready,
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
                messageRes = if (message == null) current.messageRes else null,
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
            messageRes = null,
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

    fun idle(message: String? = null) {
        val previous = mutableState.value
        mutableState.value = SessionUiState(
            phase = SessionPhase.IDLE,
            message = message.orEmpty(),
            messageRes = if (message == null) R.string.session_service_stopped else null,
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
