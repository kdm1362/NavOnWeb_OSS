/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.eigenkodex.navonweb.ui

import com.eigenkodex.navonweb.browser.cloud.CloudPairingRegistrationStatus
import java.text.Bidi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PairingCodeFormattingTest {
    @Test
    fun eightDigitCodeIsGroupedInHalf() {
        assertEquals(leftToRight("1234 5678"), formatPairingCodeForDisplay("12345678"))
    }

    @Test
    fun sixDigitCodeIsGroupedInHalf() {
        assertEquals(leftToRight("123 456"), formatPairingCodeForDisplay("123456"))
    }

    @Test
    fun unexpectedLengthIsNotGrouped() {
        assertEquals(leftToRight("12345"), formatPairingCodeForDisplay("12345"))
    }

    @Test
    fun bareGroupedDigitsFlipInsideRightToLeftText() {
        // The defect this guards against: Arabic showed the code 27441712 as "1712 2744".
        assertEquals("1712 2744", visualOrderInRightToLeftParagraph("2744 1712"))
    }

    @Test
    fun displayedCodeKeepsItsOrderInsideRightToLeftText() {
        assertEquals(
            "2744 1712",
            visualOrderInRightToLeftParagraph(formatPairingCodeForDisplay("27441712")),
        )
        assertEquals(
            "123 456",
            visualOrderInRightToLeftParagraph(formatPairingCodeForDisplay("123456")),
        )
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
                browserUrl = "http://192.168.1.20:8787",
                pairingCode = "12345678",
                cloudPairingRegistrationStatus = CloudPairingRegistrationStatus.RETRY,
            ),
        )
    }

    private fun leftToRight(text: String): String = "${Char(0x2066)}$text${Char(0x2069)}"

    /** Lays [text] out as an RTL paragraph, the way Arabic does, and reads it left to right. */
    private fun visualOrderInRightToLeftParagraph(text: String): String {
        val bidi = Bidi(text, Bidi.DIRECTION_RIGHT_TO_LEFT)
        val levels = ByteArray(text.length) { bidi.getLevelAt(it).toByte() }
        val glyphs: Array<Any> = text.map { it as Any }.toTypedArray()
        Bidi.reorderVisually(levels, 0, glyphs, 0, glyphs.size)
        return glyphs.joinToString("").filterNot { it == Char(0x2066) || it == Char(0x2069) }
    }
}
