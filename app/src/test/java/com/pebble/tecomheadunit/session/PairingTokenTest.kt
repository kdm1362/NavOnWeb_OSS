package com.pebble.tecomheadunit.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PairingTokenTest {
    @Test
    fun generatedTokenIsEightDigits() {
        val token = PairingToken.create()
        assertEquals(8, token.length)
        assertTrue(token.all(Char::isDigit))
    }

    @Test
    fun validatesExactTokenOnly() {
        assertTrue(PairingToken.isValid("01234567", "01234567"))
        assertFalse(PairingToken.isValid("01234568", "01234567"))
        assertFalse(PairingToken.isValid("1234567", "01234567"))
        assertFalse(PairingToken.isValid(null, "01234567"))
    }
}
