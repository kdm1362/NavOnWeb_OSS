/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.eigenkodex.navonweb.session

import android.os.SystemClock
import androidx.annotation.StringRes
import com.eigenkodex.navonweb.R
import com.eigenkodex.navonweb.browser.cloud.CloudPairingRegistrationStatus
import com.eigenkodex.navonweb.browser.cloud.CloudPairingRegistrationUpdate
import com.eigenkodex.navonweb.browser.cloud.canTransitionTo
import com.eigenkodex.navonweb.core.OpenAutoTouchEvent
import com.eigenkodex.navonweb.core.TouchPhase
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
    val localBrowserUrl: String? = null,
    val pairingCode: String? = null,
    val cloudPairingRegistrationStatus: CloudPairingRegistrationStatus? = null,
    val cloudPairingPublicationEpoch: Long? = null,
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

    fun ready(
        url: String,
        localUrl: String? = null,
        pairingCode: String?,
        nativeStatus: String,
        cloudPairingRegistrationStatus: CloudPairingRegistrationStatus? = null,
    ) {
        val previousConnection = mutableState.value.androidAutoConnection
        mutableState.value = SessionUiState(
            phase = SessionPhase.READY,
            messageRes = R.string.session_browser_ready,
            browserUrl = url,
            localBrowserUrl = localUrl,
            pairingCode = pairingCode,
            cloudPairingRegistrationStatus = cloudPairingRegistrationStatus,
            nativeStatus = nativeStatus,
            androidAutoConnection = previousConnection,
        )
    }

    /** Replaces only the one-time browser registration code. Existing credentials stay valid. */
    fun updatePairingCode(pairingCode: String?) {
        mutableState.update { current ->
            if (current.phase == SessionPhase.READY) {
                current.copy(
                    pairingCode = pairingCode,
                    cloudPairingRegistrationStatus =
                        current.cloudPairingRegistrationStatus.takeIf {
                            pairingCode != null && pairingCode == current.pairingCode
                        },
                    cloudPairingPublicationEpoch = current.cloudPairingPublicationEpoch.takeIf {
                        pairingCode != null && pairingCode == current.pairingCode
                    },
                )
            } else {
                current
            }
        }
    }

    /** Replaces the code and its cloud readiness as one observable UI transition. */
    fun updatePairingCode(
        pairingCode: String?,
        cloudPairingRegistrationStatus: CloudPairingRegistrationStatus?,
    ) {
        mutableState.update { current ->
            if (current.phase == SessionPhase.READY) {
                current.copy(
                    pairingCode = pairingCode,
                    cloudPairingRegistrationStatus =
                        cloudPairingRegistrationStatus.takeIf { pairingCode != null },
                    cloudPairingPublicationEpoch = null,
                )
            } else {
                current
            }
        }
    }

    /** Updates only the current code's cloud readiness; LAN pairing remains independently usable. */
    fun updateCloudPairingRegistration(update: CloudPairingRegistrationUpdate) {
        mutableState.update { current ->
            if (current.phase != SessionPhase.READY || current.pairingCode == null) return@update current
            val currentEpoch = current.cloudPairingPublicationEpoch
            if (currentEpoch != null && update.publicationEpoch < currentEpoch) return@update current
            if (
                currentEpoch == update.publicationEpoch &&
                !current.cloudPairingRegistrationStatus.canTransitionTo(update.status)
            ) return@update current
            current.copy(
                cloudPairingRegistrationStatus = update.status,
                cloudPairingPublicationEpoch = update.publicationEpoch,
            )
        }
    }

    /** Updates the selected entry point while retaining the explicit numeric LAN debug address. */
    fun updateBrowserAddresses(browserUrl: String, localBrowserUrl: String) {
        mutableState.update { current ->
            if (current.phase == SessionPhase.READY) {
                current.copy(browserUrl = browserUrl, localBrowserUrl = localBrowserUrl)
            } else {
                current
            }
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

    /** Minimum spacing between MOVE publications; browsers send MOVE at up to roughly 41 Hz. */
    private const val MOVE_PUBLISH_INTERVAL_MILLIS = 200L

    /** Production default for [touchThrottleClockMillis]; tests restore it after replacing it. */
    internal val defaultTouchThrottleClockMillis: () -> Long = { System.nanoTime() / 1_000_000 }

    /**
     * Monotonic millisecond clock behind the MOVE throttle. A replaceable property because this
     * object is exercised by plain JVM unit tests, where android.os.SystemClock is unavailable
     * and System.currentTimeMillis() is not monotonic under wall-clock adjustments.
     */
    internal var touchThrottleClockMillis: () -> Long = defaultTouchThrottleClockMillis

    /** Clock reading of the last published MOVE, or null when the next MOVE must publish. */
    private var lastPublishedMoveAtMillis: Long? = null

    /**
     * Records the most recent accepted browser touch for the diagnostics panel.
     *
     * MOVE phases publish at most once per [MOVE_PUBLISH_INTERVAL_MILLIS]. Throttling is safe
     * here because the only consumer of [SessionUiState.lastTouch] is a display-only diagnostics
     * row, and AASDK input delivery happens upstream before this method is called — a suppressed
     * MOVE loses no input, it only spares the whole Compose tree from recomposing at the
     * browser's ~41 Hz drag rate. Non-MOVE phases (DOWN/UP/CANCEL) always publish and reset the
     * throttle window so a new gesture's first MOVE appears promptly.
     */
    fun touch(event: OpenAutoTouchEvent) {
        if (event.phase == TouchPhase.MOVE) {
            val nowMillis = touchThrottleClockMillis()
            val lastMillis = lastPublishedMoveAtMillis
            if (lastMillis != null && nowMillis - lastMillis < MOVE_PUBLISH_INTERVAL_MILLIS) return
            lastPublishedMoveAtMillis = nowMillis
        } else {
            lastPublishedMoveAtMillis = null
        }
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
