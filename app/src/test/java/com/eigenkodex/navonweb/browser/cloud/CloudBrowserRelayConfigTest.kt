/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.eigenkodex.navonweb.browser.cloud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudBrowserRelayConfigTest {
    @Test
    fun `builds device websocket registration and bare browser urls`() {
        val config = CloudBrowserRelayConfig(
            browserPageOrigin = "https://navonweb.pages.dev",
            signalingWebSocketOrigin = "wss://navonweb-signaling.example.workers.dev",
        )
        val roomId = "Abcdefghijklmnopqrstu_"

        assertEquals(
            "wss://navonweb-signaling.example.workers.dev/ws/device/$roomId",
            config.deviceWebSocketUrl(roomId),
        )
        assertEquals(
            "https://navonweb.pages.dev",
            config.browserUrl(),
        )
        assertEquals(
            "https://navonweb-signaling.example.workers.dev/bootstrap/device",
            config.devicePairingRegistrationUrl(),
        )
    }

    @Test
    fun `cloud origins reject insecure and path-bearing endpoints`() {
        assertTrue(CloudBrowserRelayConfig.validOrigin("https://navonweb.pages.dev", "https"))
        assertTrue(CloudBrowserRelayConfig.validOrigin("wss://signal.example.com", "wss"))
        assertFalse(CloudBrowserRelayConfig.validOrigin("http://navonweb.pages.dev", "https"))
        assertFalse(CloudBrowserRelayConfig.validOrigin("ws://signal.example.com", "wss"))
        assertFalse(CloudBrowserRelayConfig.validOrigin("wss://signal.example.com/path", "wss"))
        assertFalse(CloudBrowserRelayConfig.validOrigin("wss://user@signal.example.com", "wss"))
    }

    @Test
    fun `registration conflict is distinguished from retryable failures`() {
        assertEquals(
            CloudPairingRegistrationResult.SUCCESS,
            CloudPairingRegistrationResult.fromHttpStatus(204),
        )
        assertEquals(
            CloudPairingRegistrationResult.CONFLICT,
            CloudPairingRegistrationResult.fromHttpStatus(409),
        )
        assertEquals(
            CloudPairingRegistrationResult.THROTTLED,
            CloudPairingRegistrationResult.fromHttpStatus(429),
        )
        assertEquals(
            CloudPairingRegistrationResult.RETRY,
            CloudPairingRegistrationResult.fromHttpStatus(503),
        )
    }

    @Test
    fun `registration throttle honors a bounded retry-after delay`() {
        assertEquals(1_000L, CloudPairingRetryAfter.delayMillis("1"))
        assertEquals(600_000L, CloudPairingRetryAfter.delayMillis("600"))
        assertEquals(600_000L, CloudPairingRetryAfter.delayMillis("999999"))
        assertEquals(null, CloudPairingRetryAfter.delayMillis("0"))
        assertEquals(null, CloudPairingRetryAfter.delayMillis("Wed, 21 Oct 2015 07:28:00 GMT"))
        assertEquals(
            600_000L,
            CloudPairingRegistrationOutcome.fromHttp(429, "600").retryAfterMillis,
        )
        assertEquals(
            60_000L,
            CloudPairingRegistrationOutcome.fromHttp(429, "invalid").retryAfterMillis,
        )
    }
}
