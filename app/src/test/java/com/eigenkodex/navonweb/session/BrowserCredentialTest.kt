package com.eigenkodex.navonweb.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserCredentialTest {
    @Test
    fun generatedCredentialHasUrlSafe256BitShape() {
        val credential = BrowserCredential.create()

        assertEquals(43, credential.length)
        assertTrue(credential.all { it.isLetterOrDigit() || it == '-' || it == '_' })
    }

    @Test
    fun validatesOnlyCredentialMatchingStoredDigest() {
        val credential = BrowserCredential.create()
        val other = BrowserCredential.create()
        val digest = BrowserCredential.digest(credential)

        assertNotEquals(credential, other)
        assertTrue(BrowserCredential.isValid(credential, digest))
        assertFalse(BrowserCredential.isValid(other, digest))
        assertFalse(BrowserCredential.isValid(null, digest))
        assertFalse(BrowserCredential.isValid(credential, null))
    }

    @Test
    fun rejectsMalformedCredentialBeforeDigestComparison() {
        val credential = BrowserCredential.create()
        val digest = BrowserCredential.digest(credential)

        assertFalse(BrowserCredential.isValid("short", digest))
        assertFalse(BrowserCredential.isValid("!".repeat(43), digest))
        assertFalse(BrowserCredential.isValid(credential, "not-a-digest"))
    }

    @Test
    fun recognizesOnlyACompleteStoredSha256Digest() {
        val digest = BrowserCredential.digest(BrowserCredential.create())

        assertTrue(BrowserCredential.isStoredDigest(digest))
        assertFalse(BrowserCredential.isStoredDigest(null))
        assertFalse(BrowserCredential.isStoredDigest("short"))
        assertFalse(BrowserCredential.isStoredDigest("!".repeat(43)))
    }
}
