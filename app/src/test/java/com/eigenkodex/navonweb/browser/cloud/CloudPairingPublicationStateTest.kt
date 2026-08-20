/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.eigenkodex.navonweb.browser.cloud

import com.eigenkodex.navonweb.session.PairingCodeLease
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudPairingPublicationStateTest {
    @Test
    fun sameCodeKeepsEpochAndSuccessfulReconnectDoesNotRegisterAgain() {
        var epoch = 40L
        val lease = pairingLease("12345678", 601_000L)
        val state = CloudPairingPublicationState(lease) { ++epoch }
        val first = requireNotNull(state.current())

        assertEquals(41L, first.epoch)
        assertEquals(first, state.publish(lease))
        assertTrue(state.shouldRegister(first))
        assertTrue(state.markRegistrationComplete(first))
        assertFalse(state.shouldRegister(first))

        // WebSocket onOpen reads the same publication after reconnect. A known
        // successful registration is terminal for this code and epoch.
        assertEquals(first, state.current())
        assertFalse(state.shouldRegister(requireNotNull(state.current())))
        assertEquals(41L, epoch)
    }

    @Test
    fun newCodeCommitsOneNewEpochAndAllRetriesReuseIt() {
        var epoch = 7L
        val state = CloudPairingPublicationState(null) { ++epoch }
        val first = state.publish(pairingLease("11111111", 601_000L))
        val secondLease = pairingLease("22222222", 602_000L)
        val second = state.publish(secondLease)

        assertEquals(8L, first.epoch)
        assertEquals(9L, second.epoch)
        assertNotEquals(first, second)
        assertEquals(second, state.publish(secondLease))
        assertEquals(9L, epoch)
        assertTrue(state.shouldRegister(second))
    }

    @Test
    fun staleConflictCannotCompleteOrRotateTheCurrentPublication() {
        var epoch = 0L
        val state = CloudPairingPublicationState(pairingLease("11111111", 601_000L)) { ++epoch }
        val stale = requireNotNull(state.current())
        val current = state.publish(pairingLease("22222222", 602_000L))

        // This Boolean gates the conflict callback in CloudBrowserRelayClient.
        assertFalse(state.markRegistrationComplete(stale))
        assertTrue(state.shouldRegister(current))
        assertEquals(current, state.current())
    }

    @Test
    fun reopeningAfterPublicationWasClearedUsesANewEpoch() {
        var epoch = 0L
        val state = CloudPairingPublicationState(pairingLease("12345678", 601_000L)) { ++epoch }
        val first = requireNotNull(state.current())
        state.clear()
        val reopened = state.publish(pairingLease("12345678", 602_000L))

        assertEquals(1L, first.epoch)
        assertEquals(2L, reopened.epoch)
    }

    @Test
    fun delayedRelayCreationKeepsTheOriginalGateDeadline() {
        var epoch = 0L
        val gateLease = pairingLease("12345678", 601_000L)

        // Constructing the cloud state at t=600000 does not create now+10m.
        val publication = requireNotNull(
            CloudPairingPublicationState(gateLease) { ++epoch }.current(),
        )
        assertEquals(gateLease.expiresAtMonotonicMillis, publication.expiresAtMonotonicMillis)
        assertEquals(1_000L, publication.remainingTtlMillis(600_000L))
        assertEquals(0L, publication.remainingTtlMillis(601_000L))
    }

    @Test
    fun registrationTtlReservesOneCallTimeoutForTransport() {
        val publication = CloudPairingPublication(
            pairingCode = "12345678",
            epoch = 1L,
            expiresAtMonotonicMillis = 100_000L,
        )

        assertEquals(1_000L, publication.registrationTtlMillis(79_000L))
        assertEquals(0L, publication.registrationTtlMillis(80_000L))
        assertEquals(0L, publication.registrationTtlMillis(90_000L))
    }

    @Test
    fun registrationStatusRejectsStaleEpochTransitions() {
        var epoch = 0L
        val state = CloudPairingPublicationState(pairingLease("11111111", 601_000L)) { ++epoch }
        val stale = requireNotNull(state.current())
        assertEquals(
            CloudPairingRegistrationStatus.REGISTERING,
            requireNotNull(state.currentUpdate()).status,
        )
        assertEquals(
            CloudPairingRegistrationStatus.RETRY,
            requireNotNull(
                state.transition(stale, CloudPairingRegistrationStatus.RETRY),
            ).status,
        )

        val current = state.publish(pairingLease("22222222", 602_000L))

        assertNull(state.transition(stale, CloudPairingRegistrationStatus.READY))
        assertFalse(state.isCurrentEpoch(stale.epoch))
        assertTrue(state.isCurrentEpoch(current.epoch))
        assertEquals(
            CloudPairingRegistrationStatus.REGISTERING,
            requireNotNull(state.currentUpdate()).status,
        )
    }

    @Test
    fun readyCodeRefreshesBeforeTheConservativeWorkerDeadline() {
        val publication = CloudPairingPublication(
            pairingCode = "12345678",
            epoch = 1L,
            expiresAtMonotonicMillis = 100_000L,
        )

        assertEquals(1_000L, publication.readyRefreshDelayMillis(78_000L))
        assertEquals(0L, publication.readyRefreshDelayMillis(79_000L))
    }

    @Test
    fun readyAndExpiredAreTerminalWithinOnePublicationEpoch() {
        var epoch = 0L
        val state = CloudPairingPublicationState(pairingLease("12345678", 601_000L)) { ++epoch }
        val publication = requireNotNull(state.current())

        assertTrue(state.markRegistrationComplete(publication))
        assertEquals(
            CloudPairingRegistrationStatus.READY,
            requireNotNull(
                state.transition(publication, CloudPairingRegistrationStatus.READY),
            ).status,
        )
        assertNull(state.transition(publication, CloudPairingRegistrationStatus.RETRY))
        assertEquals(
            CloudPairingRegistrationStatus.EXPIRED,
            requireNotNull(
                state.transition(publication, CloudPairingRegistrationStatus.EXPIRED),
            ).status,
        )
        assertNull(state.transition(publication, CloudPairingRegistrationStatus.READY))
    }

    private fun pairingLease(code: String, deadline: Long) = PairingCodeLease(
        code = code,
        expiresAtMonotonicMillis = deadline,
    )
}
