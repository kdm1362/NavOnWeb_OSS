/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.pebble.tecomheadunit.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserTrustStoreTest {
    @Test
    fun issuingAnotherDevicePreservesEveryPriorCredentialWithoutPersistingRawTokens() {
        val persistence = MemoryBrowserTrustPersistence()
        var now = 100L
        val store = BrowserTrustStore(persistence = persistence, clock = { now++ })

        val first = store.issueDevice("  Galaxy\nTab  ").issued()
        val second = store.issueDevice("Chrome on desktop").issued()

        assertNotEquals(first.credential, second.credential)
        assertEquals("Galaxy Tab", first.authenticatedDevice.device.displayName)
        assertTrue(store.isValid(first.credential))
        assertTrue(store.isValid(second.credential))
        assertEquals(2, store.devices().size)
        assertFalse(persistence.state.encodedDevices.orEmpty().contains(first.credential))
        assertFalse(persistence.state.encodedDevices.orEmpty().contains(second.credential))
        assertFalse(store.devices().toString().contains(first.credential))
        assertFalse(store.devices().toString().contains(first.authenticatedDevice.ownerKey))
    }

    @Test
    fun validV1DigestMigratesWithoutChangingOrRevokingTheCredential() {
        val credential = BrowserCredential.create()
        val persistence = MemoryBrowserTrustPersistence(
            StoredBrowserTrustState(
                schemaVersion = 1,
                encodedDevices = null,
                preferredMainDeviceId = null,
                legacyCredentialDigest = BrowserCredential.digest(credential),
            ),
        )
        val store = BrowserTrustStore(persistence = persistence, clock = { 500L })

        val authenticated = store.authenticate(credential)

        assertNotNull(authenticated)
        assertEquals(BrowserDevicePermission.CONTROL, authenticated?.device?.permission)
        assertTrue(authenticated?.device?.preferredMain == true)
        assertEquals(2, persistence.state.schemaVersion)
        assertNull(persistence.state.legacyCredentialDigest)
        assertTrue(store.isValid(credential))
    }

    @Test
    fun failedV1MigrationStillAuthenticatesLegacyCredential() {
        val credential = BrowserCredential.create()
        val initial = StoredBrowserTrustState(
            schemaVersion = 1,
            encodedDevices = null,
            preferredMainDeviceId = null,
            legacyCredentialDigest = BrowserCredential.digest(credential),
        )
        val persistence = MemoryBrowserTrustPersistence(initial).apply { allowSaves = false }
        val store = BrowserTrustStore(persistence = persistence, clock = { 500L })

        assertTrue(store.isValid(credential))
        assertEquals(initial, persistence.state)
    }

    @Test
    fun readOnlyDeviceCannotBecomeMainAndChangingMainToReadOnlyClearsPreference() {
        val store = BrowserTrustStore(MemoryBrowserTrustPersistence(), clock = { 1_000L })
        val first = store.issueDevice("A").issued().authenticatedDevice.device
        val second = store.issueDevice("B").issued().authenticatedDevice.device
        assertTrue(first.preferredMain)

        val selected = store.setPreferredMain(second.deviceId)
        assertTrue(selected is PairedBrowserMutationResult.Updated)
        assertEquals(second.deviceId, store.preferredMainDeviceId())

        val accessUpdated = store.updateAccess(second.deviceId, BrowserDevicePermission.READ_ONLY)
        assertTrue(accessUpdated is PairedBrowserMutationResult.Updated)
        assertNull(store.preferredMainDeviceId())
        assertEquals(
            PairedBrowserMutationResult.NotAllowed,
            store.setPreferredMain(second.deviceId),
        )
        assertEquals(
            BrowserDevicePermission.READ_ONLY,
            store.devices().single { it.deviceId == second.deviceId }.permission,
        )
    }

    @Test
    fun preferredMainSurvivesARegistryReopenWhileDisconnected() {
        val persistence = MemoryBrowserTrustPersistence()
        val firstStore = BrowserTrustStore(persistence, clock = { 2_000L })
        firstStore.issueDevice("A").issued()
        val selected = firstStore.issueDevice("B").issued().authenticatedDevice.device
        firstStore.setPreferredMain(selected.deviceId)

        val reopened = BrowserTrustStore(persistence, clock = { 3_000L })

        assertEquals(selected.deviceId, reopened.preferredMainDeviceId())
        assertTrue(reopened.devices().single { it.deviceId == selected.deviceId }.preferredMain)
    }

    @Test
    fun automaticMainClaimNeverOverwritesAnExistingExplicitSelection() {
        val store = BrowserTrustStore(MemoryBrowserTrustPersistence(), clock = { 2_000L })
        val first = store.issueDevice("A").issued().authenticatedDevice.device
        val explicit = store.issueDevice("B").issued().authenticatedDevice.device
        val later = store.issueDevice("C").issued().authenticatedDevice.device
        assertTrue(store.setPreferredMain(explicit.deviceId) is PairedBrowserMutationResult.Updated)

        val result = store.setPreferredMainIfUnset(later.deviceId)

        assertTrue(result is PairedBrowserMutationResult.Updated)
        assertEquals(explicit.deviceId, store.preferredMainDeviceId())
        assertFalse(store.devices().single { it.deviceId == first.deviceId }.preferredMain)
        assertFalse(store.devices().single { it.deviceId == later.deviceId }.preferredMain)
    }

    @Test
    fun removingOneDeviceRevokesOnlyItsCredentialAndReturnsServerOwnerKey() {
        val store = BrowserTrustStore(MemoryBrowserTrustPersistence(), clock = { 2_000L })
        val removedGrant = store.issueDevice("A").issued()
        val keptGrant = store.issueDevice("B").issued()

        val result = store.remove(removedGrant.authenticatedDevice.device.deviceId)

        assertTrue(result is PairedBrowserRemovalResult.Removed)
        result as PairedBrowserRemovalResult.Removed
        assertEquals(43, result.ownerKey.length)
        assertEquals(removedGrant.authenticatedDevice.ownerKey, result.ownerKey)
        assertFalse(store.isValid(removedGrant.credential))
        assertTrue(store.isValid(keptGrant.credential))
    }

    @Test
    fun storeFailureDoesNotAdmitADeviceOrRevokeExistingDevice() {
        val persistence = MemoryBrowserTrustPersistence()
        val store = BrowserTrustStore(persistence, clock = { 2_000L })
        val existing = store.issueDevice("A").issued()
        persistence.allowSaves = false

        val result = store.issueDevice("B")

        assertEquals(BrowserCredentialIssueResult.PersistenceFailed, result)
        assertTrue(store.isValid(existing.credential))
        assertEquals(1, store.devices().size)
    }

    @Test
    fun capacityIsReportedWithoutSilentlyEvictingAnyCredential() {
        val store = BrowserTrustStore(MemoryBrowserTrustPersistence(), clock = { 3_000L })
        val first = store.issueDevice("Device 1").issued()
        repeat(MAX_PAIRED_BROWSER_DEVICES - 1) { index ->
            store.issueDevice("Device ${index + 2}").issued()
        }

        assertEquals(BrowserCredentialIssueResult.CapacityReached, store.issueDevice("Overflow"))
        assertEquals(MAX_PAIRED_BROWSER_DEVICES, store.devices().size)
        assertTrue(store.isValid(first.credential))
    }

    @Test
    fun stableColorHintsUseThreeSlotsWithoutChangingAfterReload() {
        val persistence = MemoryBrowserTrustPersistence()
        var now = 4_000L
        val store = BrowserTrustStore(persistence, clock = { now++ })
        repeat(4) { store.issueDevice("Device $it").issued() }
        val before = store.devices().associate { it.deviceId to it.colorSlot }

        val after = BrowserTrustStore(persistence, clock = { 5_000L })
            .devices()
            .associate { it.deviceId to it.colorSlot }

        assertEquals(listOf(0, 1, 2, 0), store.devices().map(PairedBrowserDevice::colorSlot))
        assertEquals(before, after)
    }

    @Test
    fun displayNameIsBoundedByUnicodeCodePointsWithoutSplittingSurrogatePairs() {
        val store = BrowserTrustStore(MemoryBrowserTrustPersistence(), clock = { 5_000L })
        val requested = "📱".repeat(70)

        val device = store.issueDevice(requested).issued().authenticatedDevice.device

        assertEquals(64, device.displayName.codePointCount(0, device.displayName.length))
        assertTrue(device.displayName.endsWith("📱"))
    }

    @Test
    fun renamePersistsNormalizedNameAndPreservesEveryOtherDeviceField() {
        val persistence = MemoryBrowserTrustPersistence()
        val store = BrowserTrustStore(persistence, clock = { 5_500L })
        val grant = store.issueDevice("Old name").issued()
        val deviceId = grant.authenticatedDevice.device.deviceId
        assertTrue(store.recordSeen(deviceId, 6_000L))
        val before = store.devices().single()
        val ownerKeyBefore = requireNotNull(store.authenticate(grant.credential)).ownerKey

        val result = store.renameDevice(deviceId, "  Family   Car  Tablet  ")

        assertTrue(result is PairedBrowserMutationResult.Updated)
        result as PairedBrowserMutationResult.Updated
        val expected = before.copy(displayName = "Family Car Tablet")
        assertEquals(expected, result.device)
        assertEquals(before.deviceId, result.preferredMainDeviceId)
        assertEquals(expected, store.devices().single())
        val authenticatedAfter = requireNotNull(store.authenticate(grant.credential))
        assertEquals(expected, authenticatedAfter.device)
        assertEquals(ownerKeyBefore, authenticatedAfter.ownerKey)
        assertEquals(grant.authenticatedDevice.ownerKey, authenticatedAfter.ownerKey)
        assertEquals(expected, BrowserTrustStore(persistence).devices().single())
    }

    @Test
    fun manualRenameRejectsBlankControlAndOver64CodePointsWithoutMutation() {
        val persistence = MemoryBrowserTrustPersistence()
        val store = BrowserTrustStore(persistence, clock = { 6_500L })
        val before = store.issueDevice("Keep me").issued().authenticatedDevice.device
        val overLimit = "\uD83D\uDCF1".repeat(65)

        listOf("   ", "bad\u0000name", "line\nbreak", overLimit).forEach { invalid ->
            assertEquals(
                PairedBrowserMutationResult.InvalidDisplayName,
                store.renameDevice(before.deviceId, invalid),
            )
            assertEquals(before, store.devices().single())
        }
    }

    @Test
    fun manualRenameAcceptsExactly64UnicodeCodePointsWithoutSplittingEmoji() {
        val store = BrowserTrustStore(MemoryBrowserTrustPersistence(), clock = { 7_000L })
        val device = store.issueDevice("Old").issued().authenticatedDevice.device
        val requested = "\uD83D\uDCF1".repeat(64)

        val result = store.renameDevice(device.deviceId, requested)

        assertTrue(result is PairedBrowserMutationResult.Updated)
        result as PairedBrowserMutationResult.Updated
        val renamed = requireNotNull(result.device).displayName
        assertEquals(64, renamed.codePointCount(0, renamed.length))
        assertEquals(requested, renamed)
    }

    @Test
    fun renameFailureAndUnknownDeviceLeavePersistedRegistryUntouched() {
        val persistence = MemoryBrowserTrustPersistence()
        val store = BrowserTrustStore(persistence, clock = { 7_500L })
        val grant = store.issueDevice("Original").issued()
        val beforeState = persistence.state
        persistence.allowSaves = false

        assertEquals(
            PairedBrowserMutationResult.PersistenceFailed,
            store.renameDevice(grant.authenticatedDevice.device.deviceId, "Replacement"),
        )
        assertEquals(
            PairedBrowserMutationResult.NotFound,
            store.renameDevice("browser_missing000000", "Replacement"),
        )
        assertEquals(beforeState, persistence.state)
        assertEquals("Original", store.devices().single().displayName)
        assertTrue(store.isValid(grant.credential))
    }

    @Test
    fun renameAfterDeletionIsNotFoundAndCannotResurrectDeviceOrCredential() {
        val persistence = MemoryBrowserTrustPersistence()
        val store = BrowserTrustStore(persistence, clock = { 8_000L })
        val grant = store.issueDevice("Delete me").issued()
        val deviceId = grant.authenticatedDevice.device.deviceId
        assertTrue(store.remove(deviceId) is PairedBrowserRemovalResult.Removed)

        assertEquals(
            PairedBrowserMutationResult.NotFound,
            store.renameDevice(deviceId, "Resurrected"),
        )
        assertTrue(store.devices().isEmpty())
        assertFalse(store.isValid(grant.credential))
        assertTrue(BrowserTrustStore(persistence).devices().isEmpty())
    }

    @Test
    fun recordSeenAdvancesOnlyKnownDeviceAndNeverMovesTimestampBackward() {
        val store = BrowserTrustStore(MemoryBrowserTrustPersistence(), clock = { 10L })
        val device = store.issueDevice("A").issued().authenticatedDevice.device

        assertTrue(store.recordSeen(device.deviceId, 20L))
        assertTrue(store.recordSeen(device.deviceId, 15L))
        assertFalse(store.recordSeen("browser_missing000000", 30L))
        assertEquals(20L, store.devices().single().lastSeenAtEpochMillis)
    }

    private fun BrowserCredentialIssueResult.issued(): IssuedBrowserCredential {
        assertTrue(this is BrowserCredentialIssueResult.Issued)
        return (this as BrowserCredentialIssueResult.Issued).grant
    }

    private class MemoryBrowserTrustPersistence(
        initial: StoredBrowserTrustState = StoredBrowserTrustState(
            schemaVersion = 0,
            encodedDevices = null,
            preferredMainDeviceId = null,
            legacyCredentialDigest = null,
        ),
    ) : BrowserTrustPersistence {
        var state: StoredBrowserTrustState = initial
        var allowSaves: Boolean = true

        override fun load(): StoredBrowserTrustState = state

        override fun save(state: StoredBrowserTrustState): Boolean {
            if (!allowSaves) return false
            this.state = state
            return true
        }
    }
}
