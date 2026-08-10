/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.pebble.tecomheadunit.browser

import com.pebble.tecomheadunit.session.BrowserCredentialIssueResult
import com.pebble.tecomheadunit.session.BrowserDevicePermission
import com.pebble.tecomheadunit.session.BrowserTrustPersistence
import com.pebble.tecomheadunit.session.BrowserTrustStore
import com.pebble.tecomheadunit.session.IssuedBrowserCredential
import com.pebble.tecomheadunit.session.PairedBrowserMutationResult
import com.pebble.tecomheadunit.session.StoredBrowserTrustState
import java.util.ArrayDeque
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserPairingMultiDeviceIntegrationTest {
    @Test
    fun `second distinct pairing preserves first authentication and live session admission`() {
        val persistence = MemoryBrowserTrustPersistence()
        val credentials = ArrayDeque(listOf("A".repeat(43), "B".repeat(43)))
        var now = 1_000L
        val pairingStore = BrowserTrustStore(
            persistence = persistence,
            credentialFactory = credentials::removeFirst,
            clock = { now++ },
        )
        // ProjectionService and MainActivity intentionally own separate store instances in the
        // app. Their process-wide storage lock and reload-on-mutation contract must still append.
        val serviceStore = BrowserTrustStore(persistence = persistence, clock = { now++ })

        val first = issuePairedBrowserDeviceFromPairingHeaders(
            pairingStore,
            mapOf("x-browser-device-name" to "Tablet client"),
        ).issued()
        val firstLiveSessions = linkedMapOf("session_first" to first.authenticatedDevice.ownerKey)
        val firstLiveSnapshot = firstLiveSessions.toMap()

        val second = issuePairedBrowserDeviceFromPairingHeaders(
            pairingStore,
            mapOf("x-browser-device-name" to "Browser client"),
        ).issued()

        assertNotEquals(first.credential, second.credential)
        assertNotEquals(first.authenticatedDevice.ownerKey, second.authenticatedDevice.ownerKey)
        assertEquals(first.authenticatedDevice, serviceStore.authenticate(first.credential))
        assertEquals(second.authenticatedDevice, serviceStore.authenticate(second.credential))
        assertEquals(2, serviceStore.devices().size)
        assertEquals(
            BrowserWebRtcSessionAdmission.Open,
            browserWebRtcSessionAdmission(
                activeOwnersBySessionId = firstLiveSessions,
                requestedOwnerKey = second.authenticatedDevice.ownerKey,
                maximumSessions = MAX_PREMIUM_BROWSER_SESSIONS,
            ),
        )
        assertEquals(firstLiveSnapshot, firstLiveSessions)
    }

    @Test
    fun `additional pairing preserves existing rename access and owner identity`() {
        val persistence = MemoryBrowserTrustPersistence()
        val credentials = ArrayDeque(listOf("C".repeat(43), "D".repeat(43)))
        val pairingStore = BrowserTrustStore(
            persistence = persistence,
            credentialFactory = credentials::removeFirst,
            clock = { 2_000L },
        )
        val settingsStore = BrowserTrustStore(persistence = persistence, clock = { 2_000L })
        val first = issuePairedBrowserDeviceFromPairingHeaders(
            pairingStore,
            mapOf("x-browser-device-name" to "Tablet"),
        ).issued()
        val firstDeviceId = first.authenticatedDevice.device.deviceId

        assertTrue(
            settingsStore.renameDevice(firstDeviceId, "Front tablet") is
                PairedBrowserMutationResult.Updated,
        )
        assertTrue(
            settingsStore.updateAccess(firstDeviceId, BrowserDevicePermission.READ_ONLY) is
                PairedBrowserMutationResult.Updated,
        )
        val firstBeforeSecondPair = requireNotNull(settingsStore.authenticate(first.credential))

        val second = issuePairedBrowserDeviceFromPairingHeaders(
            pairingStore,
            mapOf("x-browser-device-name" to "Desktop browser"),
        ).issued()
        val firstAfterSecondPair = requireNotNull(settingsStore.authenticate(first.credential))

        assertEquals(firstBeforeSecondPair, firstAfterSecondPair)
        assertEquals("Front tablet", firstAfterSecondPair.device.displayName)
        assertEquals(BrowserDevicePermission.READ_ONLY, firstAfterSecondPair.device.permission)
        assertEquals(first.authenticatedDevice.ownerKey, firstAfterSecondPair.ownerKey)
        assertNotNull(settingsStore.authenticate(second.credential))

        val secondId = second.authenticatedDevice.device.deviceId
        assertTrue(
            settingsStore.renameDevice(secondId, "Rear browser") is
                PairedBrowserMutationResult.Updated,
        )
        assertTrue(
            settingsStore.updateAccess(secondId, BrowserDevicePermission.READ_ONLY) is
                PairedBrowserMutationResult.Updated,
        )
        assertEquals(firstAfterSecondPair, settingsStore.authenticate(first.credential))
        assertEquals(
            "Rear browser",
            settingsStore.authenticate(second.credential)?.device?.displayName,
        )
        assertEquals(
            BrowserDevicePermission.READ_ONLY,
            settingsStore.authenticate(second.credential)?.device?.permission,
        )
    }

    private fun BrowserCredentialIssueResult.issued(): IssuedBrowserCredential {
        assertTrue(this is BrowserCredentialIssueResult.Issued)
        return (this as BrowserCredentialIssueResult.Issued).grant
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
