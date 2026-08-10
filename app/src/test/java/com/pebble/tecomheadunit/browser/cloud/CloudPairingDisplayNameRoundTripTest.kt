/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.pebble.tecomheadunit.browser.cloud

import com.pebble.tecomheadunit.browser.issuePairedBrowserDeviceFromPairingHeaders
import com.pebble.tecomheadunit.session.BrowserCredentialIssueResult
import com.pebble.tecomheadunit.session.BrowserTrustPersistence
import com.pebble.tecomheadunit.session.BrowserTrustStore
import com.pebble.tecomheadunit.session.StoredBrowserTrustState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudPairingDisplayNameRoundTripTest {
    @Test
    fun cloudDecodeAndPairIssuancePreserveBrowserDeviceName() {
        val request = decodeCloudRelayRequest(
            method = "post",
            target = "/api/pair",
            headers = mapOf(
                "X-Pairing-Code" to "12345678",
                "X-Browser-Device-Name" to "Chrome · Windows",
            ),
            encodedBody = "",
        )
        val store = BrowserTrustStore(
            persistence = MemoryBrowserTrustPersistence(),
            clock = { 1_000L },
        )

        val result = issuePairedBrowserDeviceFromPairingHeaders(store, request.headers)

        assertEquals("POST", request.method)
        assertEquals("12345678", request.headers["x-pairing-code"])
        assertEquals("Chrome · Windows", request.headers["x-browser-device-name"])
        assertTrue(result is BrowserCredentialIssueResult.Issued)
        val grant = (result as BrowserCredentialIssueResult.Issued).grant
        assertEquals("Chrome · Windows", grant.authenticatedDevice.device.displayName)
        assertEquals("Chrome · Windows", store.devices().single().displayName)
        assertNotNull(store.authenticate(grant.credential))
    }

    private class MemoryBrowserTrustPersistence : BrowserTrustPersistence {
        private var state = StoredBrowserTrustState(
            schemaVersion = 0,
            encodedDevices = null,
            preferredMainDeviceId = null,
            legacyCredentialDigest = null,
        )

        override fun load(): StoredBrowserTrustState = state

        override fun save(state: StoredBrowserTrustState): Boolean {
            this.state = state
            return true
        }
    }
}
