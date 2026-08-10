/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.pebble.tecomheadunit.ui

import com.pebble.tecomheadunit.browser.cloud.CloudPairingRegistrationStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PairingCodeFormattingTest {
    @Test
    fun eightDigitCodeIsGroupedInHalf() {
        assertEquals("1234 5678", formatPairingCodeForDisplay("12345678"))
    }

    @Test
    fun sixDigitCodeIsGroupedInHalf() {
        assertEquals("123 456", formatPairingCodeForDisplay("123456"))
    }

    @Test
    fun unexpectedLengthIsLeftUnchanged() {
        assertEquals("12345", formatPairingCodeForDisplay("12345"))
    }

    @Test
    fun cloudCodeIsHiddenUntilItsPublicationIsReady() {
        assertFalse(
            shouldDisplayPairingCode(
                browserUrl = "https://navonweb.com",
                pairingCode = "12345678",
                cloudPairingRegistrationStatus = CloudPairingRegistrationStatus.REGISTERING,
            ),
        )
        assertTrue(
            shouldDisplayPairingCode(
                browserUrl = "https://navonweb.com",
                pairingCode = "12345678",
                cloudPairingRegistrationStatus = CloudPairingRegistrationStatus.READY,
            ),
        )
    }

    @Test
    fun lanCodeRemainsVisibleWithoutCloudReadiness() {
        assertTrue(
            shouldDisplayPairingCode(
                browserUrl = "http://192.168.50.20:8787",
                pairingCode = "12345678",
                cloudPairingRegistrationStatus = CloudPairingRegistrationStatus.RETRY,
            ),
        )
    }
}
