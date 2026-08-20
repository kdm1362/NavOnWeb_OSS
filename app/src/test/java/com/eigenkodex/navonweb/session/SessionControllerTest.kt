package com.eigenkodex.navonweb.session

import com.eigenkodex.navonweb.browser.cloud.CloudPairingRegistrationStatus
import com.eigenkodex.navonweb.browser.cloud.CloudPairingRegistrationUpdate
import com.eigenkodex.navonweb.core.OpenAutoTouchEvent
import com.eigenkodex.navonweb.core.TouchPhase
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class SessionControllerTest {
    /** Deterministic reading returned by the replaced MOVE-throttle clock. */
    private var nowMillis = 0L

    @Before
    fun installDeterministicTouchClock() {
        nowMillis = 0L
        SessionController.touchThrottleClockMillis = { nowMillis }
    }

    @After
    fun restoreProductionTouchClock() {
        SessionController.touchThrottleClockMillis =
            SessionController.defaultTouchThrottleClockMillis
    }

    private fun touchEvent(phase: TouchPhase, x: Int): OpenAutoTouchEvent = OpenAutoTouchEvent(
        phase = phase,
        x = x,
        y = 0,
        pointerId = 0,
        timestampNanos = x.toLong(),
    )

    @Test
    fun selectedCloudAddressRetainsTheCurrentNumericLanDebugAddress() {
        SessionController.ready(
            url = "https://navonweb.com",
            localUrl = "http://192.168.0.231:8787",
            pairingCode = "01234567",
            nativeStatus = "AASDK READY",
        )

        assertEquals("https://navonweb.com", SessionController.state.value.browserUrl)
        assertEquals(
            "http://192.168.0.231:8787",
            SessionController.state.value.localBrowserUrl,
        )

        SessionController.updateBrowserAddresses(
            browserUrl = "https://navonweb.com",
            localBrowserUrl = "http://192.168.0.232:8787",
        )
        assertEquals(
            "http://192.168.0.232:8787",
            SessionController.state.value.localBrowserUrl,
        )
        SessionController.idle()
    }

    @Test
    fun pairingCodeRotationPreservesReadySessionState() {
        val touch = OpenAutoTouchEvent(
            phase = TouchPhase.UP,
            x = 200,
            y = 360,
            pointerId = 0,
            timestampNanos = 1L,
        )
        SessionController.ready(
            url = "http://10.0.0.231:8787",
            pairingCode = "01234567",
            nativeStatus = "AASDK VIDEO_MEDIA_ACCEPTED frames=1 bytes=100",
        )
        SessionController.touch(touch)
        val before = SessionController.state.value

        SessionController.updatePairingCode("65432109")

        assertEquals(before.copy(pairingCode = "65432109"), SessionController.state.value)
        SessionController.idle()
    }

    @Test
    fun pairingCodeRotationIsIgnoredOutsideReadyState() {
        SessionController.idle()

        SessionController.updatePairingCode("65432109")

        assertNull(SessionController.state.value.pairingCode)
    }

    @Test
    fun closingPairingPublicationPreservesRememberedBrowserSession() {
        SessionController.ready(
            url = "https://navonweb.com",
            pairingCode = "01234567",
            nativeStatus = "AASDK READY",
            cloudPairingRegistrationStatus = CloudPairingRegistrationStatus.READY,
        )
        val before = SessionController.state.value

        SessionController.updatePairingCode(null)

        assertEquals(
            before.copy(
                pairingCode = null,
                cloudPairingRegistrationStatus = null,
                cloudPairingPublicationEpoch = null,
            ),
            SessionController.state.value,
        )
        SessionController.idle()
    }

    @Test
    fun cloudRegistrationStatusChangesWithoutReplacingTheCurrentCode() {
        SessionController.ready(
            url = "https://navonweb.com",
            pairingCode = "01234567",
            nativeStatus = "AASDK READY",
            cloudPairingRegistrationStatus = CloudPairingRegistrationStatus.REGISTERING,
        )

        SessionController.updateCloudPairingRegistration(
            CloudPairingRegistrationUpdate(
                publicationEpoch = 7L,
                status = CloudPairingRegistrationStatus.READY,
            ),
        )

        assertEquals("01234567", SessionController.state.value.pairingCode)
        assertEquals(
            CloudPairingRegistrationStatus.READY,
            SessionController.state.value.cloudPairingRegistrationStatus,
        )
        SessionController.idle()
    }

    @Test
    fun cloudStatusCannotReopenAClosedPairingPublication() {
        SessionController.ready(
            url = "https://navonweb.com",
            pairingCode = null,
            nativeStatus = "AASDK READY",
        )

        SessionController.updateCloudPairingRegistration(
            CloudPairingRegistrationUpdate(
                publicationEpoch = 7L,
                status = CloudPairingRegistrationStatus.READY,
            ),
        )

        assertNull(SessionController.state.value.cloudPairingRegistrationStatus)
        SessionController.idle()
    }

    @Test
    fun staleAndTerminalCloudUpdatesCannotDowngradeTheCurrentPublication() {
        SessionController.ready(
            url = "https://navonweb.com",
            pairingCode = "01234567",
            nativeStatus = "AASDK READY",
            cloudPairingRegistrationStatus = CloudPairingRegistrationStatus.REGISTERING,
        )
        SessionController.updateCloudPairingRegistration(
            CloudPairingRegistrationUpdate(8L, CloudPairingRegistrationStatus.READY),
        )
        val ready = SessionController.state.value

        SessionController.updateCloudPairingRegistration(
            CloudPairingRegistrationUpdate(7L, CloudPairingRegistrationStatus.RETRY),
        )
        SessionController.updateCloudPairingRegistration(
            CloudPairingRegistrationUpdate(8L, CloudPairingRegistrationStatus.RETRY),
        )

        assertEquals(ready, SessionController.state.value)
        SessionController.idle()
    }

    @Test
    fun moveWithinTheThrottleWindowOfTheLastPublishedMoveIsSuppressed() {
        SessionController.touch(touchEvent(TouchPhase.DOWN, x = 1))
        nowMillis = 1_000L
        val publishedMove = touchEvent(TouchPhase.MOVE, x = 2)
        SessionController.touch(publishedMove)
        assertEquals(publishedMove, SessionController.state.value.lastTouch)

        nowMillis = 1_199L
        SessionController.touch(touchEvent(TouchPhase.MOVE, x = 3))

        assertEquals(publishedMove, SessionController.state.value.lastTouch)
        SessionController.idle()
    }

    @Test
    fun moveAfterTheThrottleWindowElapsesPublishes() {
        SessionController.touch(touchEvent(TouchPhase.DOWN, x = 1))
        nowMillis = 1_000L
        SessionController.touch(touchEvent(TouchPhase.MOVE, x = 2))

        nowMillis = 1_200L
        val laterMove = touchEvent(TouchPhase.MOVE, x = 3)
        SessionController.touch(laterMove)

        assertEquals(laterMove, SessionController.state.value.lastTouch)
        SessionController.idle()
    }

    @Test
    fun upImmediatelyAfterSuppressedMovesPublishesTheUpEvent() {
        SessionController.touch(touchEvent(TouchPhase.DOWN, x = 1))
        nowMillis = 1_000L
        SessionController.touch(touchEvent(TouchPhase.MOVE, x = 2))
        nowMillis = 1_050L
        SessionController.touch(touchEvent(TouchPhase.MOVE, x = 3))

        nowMillis = 1_060L
        val up = touchEvent(TouchPhase.UP, x = 4)
        SessionController.touch(up)

        assertEquals(up, SessionController.state.value.lastTouch)
        SessionController.idle()
    }

    @Test
    fun downResetsTheThrottleWindowForTheNextGesturesFirstMove() {
        SessionController.touch(touchEvent(TouchPhase.DOWN, x = 1))
        nowMillis = 1_000L
        SessionController.touch(touchEvent(TouchPhase.MOVE, x = 2))
        nowMillis = 1_100L
        SessionController.touch(touchEvent(TouchPhase.MOVE, x = 3))

        nowMillis = 1_110L
        SessionController.touch(touchEvent(TouchPhase.DOWN, x = 4))
        nowMillis = 1_120L
        val firstMoveOfNewGesture = touchEvent(TouchPhase.MOVE, x = 5)
        SessionController.touch(firstMoveOfNewGesture)

        assertEquals(firstMoveOfNewGesture, SessionController.state.value.lastTouch)
        SessionController.idle()
    }
}
