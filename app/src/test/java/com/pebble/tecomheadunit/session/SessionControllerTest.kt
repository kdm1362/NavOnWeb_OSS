package com.pebble.tecomheadunit.session

import com.pebble.tecomheadunit.browser.cloud.CloudPairingRegistrationStatus
import com.pebble.tecomheadunit.browser.cloud.CloudPairingRegistrationUpdate
import com.pebble.tecomheadunit.core.OpenAutoTouchEvent
import com.pebble.tecomheadunit.core.TouchPhase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SessionControllerTest {
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
}
