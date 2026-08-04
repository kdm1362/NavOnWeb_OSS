package com.pebble.tecomheadunit.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class AndroidAutoConnectionStatusTest {
    @Test
    fun classifiesConnectionLifecycleAndPreservesLastFrameAcrossRetry() {
        val initial = AndroidAutoConnectionStatus()
        val connecting = reduce(
            initial,
            "NDK runtime disabled • AASDK CONNECTING",
            epoch = 1_000L,
            elapsed = 100L,
        )
        assertEquals(AndroidAutoConnectionState.RECONNECTING, connecting.state)
        assertEquals(AndroidAutoConnectionReason.WAITING_FOR_PHONE, connecting.reason)
        assertNull(connecting.lastFrameAtEpochMillis)

        val authenticated = reduce(connecting, "AASDK AUTHENTICATED peer=1.7", 2_000L, 200L)
        assertEquals(AndroidAutoConnectionState.RECONNECTING, authenticated.state)
        assertEquals(AndroidAutoConnectionReason.LINK_READY, authenticated.reason)

        val streaming = reduce(
            authenticated,
            "AASDK VIDEO_MEDIA_ACCEPTED frames=60 bytes=123456",
            3_000L,
            300L,
        )
        assertEquals(AndroidAutoConnectionReason.STREAMING, streaming.reason)
        assertEquals(3_000L, streaming.lastFrameAtEpochMillis)
        assertEquals(250L, streaming.lastFrameAgeMillis(550L))

        val disconnected = reduce(
            streaming,
            "AASDK TRANSPORT_DISCONNECTED endpoint=10.0.0.231 secret=do-not-expose",
            4_000L,
            400L,
        )
        assertEquals(AndroidAutoConnectionState.DISCONNECTED, disconnected.state)
        assertEquals(AndroidAutoConnectionReason.NETWORK_INTERRUPTED, disconnected.reason)
        assertEquals("network_interrupted", disconnected.reason.wireName)
        assertEquals(3_000L, disconnected.lastFrameAtEpochMillis)

        val retrying = reduce(disconnected, "AASDK RECONNECTING attempt=3", 5_000L, 500L)
        assertEquals(AndroidAutoConnectionState.RECONNECTING, retrying.state)
        assertEquals(AndroidAutoConnectionReason.RETRYING, retrying.reason)
        assertEquals(3_000L, retrying.lastFrameAtEpochMillis)
    }

    @Test
    fun classifiesOnlyBoundedSanitizedFailureReasons() {
        val initial = AndroidAutoConnectionStatus()
        assertEquals(
            AndroidAutoConnectionReason.PHONE_ENDED_SESSION,
            reduce(initial, "preflight • AASDK PEER_SHUTDOWN internal detail", 1L, 1L).reason,
        )
        assertEquals(
            AndroidAutoConnectionReason.AUTHENTICATION_FAILED,
            reduce(initial, "preflight • AASDK TLS_HANDSHAKE_FAILED certificate=/private/path", 1L, 1L).reason,
        )
        assertEquals(
            AndroidAutoConnectionReason.INCOMPATIBLE_VERSION,
            reduce(initial, "preflight • AASDK VERSION_MISMATCH peer=99.1", 1L, 1L).reason,
        )
        assertEquals(
            AndroidAutoConnectionReason.PROTOCOL_ERROR,
            reduce(initial, "preflight • AASDK PROTOCOL_ERROR frame dump", 1L, 1L).reason,
        )
        listOf(
            "PEER_DISCONNECTED",
            "CONNECT_FAILED",
            "SOCKET_DISCONNECTED",
            "READ_TIMEOUT",
            "PING_TIMEOUT",
        ).forEach { status ->
            assertEquals(
                AndroidAutoConnectionReason.NETWORK_INTERRUPTED,
                reduce(initial, "preflight • AASDK $status sensitive-internal-detail", 1L, 1L).reason,
            )
        }
    }

    @Test
    fun samePhoneHeadUnitServerAbsenceHasAnActionableReason() {
        val result = reduce(
            AndroidAutoConnectionStatus(),
            "preflight • AASDK DISCONNECTED reason=HEAD_UNIT_SERVER_NOT_RUNNING retry=1",
            1L,
            1L,
        )

        assertEquals(AndroidAutoConnectionState.DISCONNECTED, result.state)
        assertEquals(AndroidAutoConnectionReason.HEAD_UNIT_SERVER_STOPPED, result.reason)
        assertEquals("head_unit_server_stopped", result.reason.wireName)
    }

    @Test
    fun unrelatedRuntimeUpdatesDoNotOverwriteConnectionState() {
        val streaming = AndroidAutoConnectionStatus(
            state = AndroidAutoConnectionState.CONNECTED,
            reason = AndroidAutoConnectionReason.STREAMING,
            lastFrameAtEpochMillis = 10L,
            lastFrameAtElapsedRealtimeMillis = 20L,
            changedAtEpochMillis = 10L,
        )

        assertEquals(streaming, reduce(streaming, "preflight • AASDK SENSOR_STARTED type=1", 30L, 40L))
    }

    @Test
    fun ignoresPreflightTextWithoutAStructuredAasdkEvent() {
        val previous = AndroidAutoConnectionStatus(
            state = AndroidAutoConnectionState.RECONNECTING,
            reason = AndroidAutoConnectionReason.WAITING_FOR_PHONE,
            changedAtEpochMillis = 10L,
        )

        assertEquals(
            previous,
            reduce(previous, "NDK runtime disabled", epoch = 20L, elapsed = 30L),
        )
        assertEquals(
            previous,
            reduce(previous, "AASDK diagnostics unavailable but no structured marker", 20L, 30L),
        )
    }

    @Test
    fun remainsReconnectingUntilFirstAcceptedVideoFrame() {
        val initial = AndroidAutoConnectionStatus()
        val authenticated = reduce(initial, "summary • AASDK AUTHENTICATED peer=1.7", 1L, 1L)
        val channelReady = reduce(authenticated, "summary • AASDK CHANNEL_OPEN channel=3", 2L, 2L)
        val decoderReady = reduce(channelReady, "summary • AASDK VIDEO_DECODER_READY 800x480@60", 3L, 3L)

        assertEquals(AndroidAutoConnectionState.RECONNECTING, authenticated.state)
        assertEquals(AndroidAutoConnectionState.RECONNECTING, channelReady.state)
        assertEquals(AndroidAutoConnectionState.RECONNECTING, decoderReady.state)

        val streaming = reduce(decoderReady, "summary • AASDK VIDEO_MEDIA_ACCEPTED frames=1 bytes=100", 4L, 4L)
        assertEquals(AndroidAutoConnectionState.CONNECTED, streaming.state)

        // Late setup events from another channel must not demote a healthy video stream.
        assertEquals(
            streaming,
            reduce(streaming, "summary • AASDK CHANNEL_OPEN channel=4", 5L, 5L),
        )
    }

    @Test
    fun decoderRecoveryIsAUserSafeReconnectingState() {
        val streaming = AndroidAutoConnectionStatus(
            state = AndroidAutoConnectionState.CONNECTED,
            reason = AndroidAutoConnectionReason.STREAMING,
            lastFrameAtEpochMillis = 1_000L,
            lastFrameAtElapsedRealtimeMillis = 100L,
            changedAtEpochMillis = 1_000L,
        )

        val recovering = reduce(
            streaming,
            "summary • AASDK DISCONNECTED reason=DECODER_RECOVERY " +
                "codec=c2.vendor.secret.decoder path=/private/internal",
            epoch = 2_000L,
            elapsed = 200L,
        )

        assertEquals(AndroidAutoConnectionState.RECONNECTING, recovering.state)
        assertEquals(AndroidAutoConnectionReason.DECODER_RECOVERY, recovering.reason)
        assertEquals("decoder_recovery", recovering.reason.wireName)
        assertEquals("Android Auto 영상 디코더 복구 중", recovering.reason.userMessage)
        assertEquals(1_000L, recovering.lastFrameAtEpochMillis)
    }

    @Test
    fun decoderUnavailableIsTerminalAndDoesNotExposeRawDetails() {
        val unavailable = reduce(
            AndroidAutoConnectionStatus(),
            "summary • AASDK DECODER_UNAVAILABLE decoder=c2.vendor.secret path=/private/internal",
            epoch = 2_000L,
            elapsed = 200L,
        )

        assertEquals(AndroidAutoConnectionState.DISCONNECTED, unavailable.state)
        assertEquals(AndroidAutoConnectionReason.DECODER_UNAVAILABLE, unavailable.reason)
        assertEquals("decoder_unavailable", unavailable.reason.wireName)
        assertEquals(
            "이 기기에서 Android Auto 영상 디코더를 사용할 수 없습니다",
            unavailable.reason.userMessage,
        )
        assertFalse(unavailable.reason.userMessage.contains("c2.vendor.secret"))
        assertFalse(unavailable.reason.userMessage.contains("/private/internal"))
    }

    private fun reduce(
        previous: AndroidAutoConnectionStatus,
        status: String,
        epoch: Long,
        elapsed: Long,
    ): AndroidAutoConnectionStatus = AndroidAutoConnectionStatusReducer.reduce(
        previous = previous,
        nativeStatus = status,
        nowEpochMillis = epoch,
        nowElapsedRealtimeMillis = elapsed,
    )
}
