/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.eigenkodex.navonweb.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProjectionStartupStoreTest {
    @Test
    fun `fresh installation does not auto start`() {
        val store = ProjectionStartupStore(
            readSuccessfulProjection = { false },
            writeSuccessfulProjection = { true },
        )

        assertFalse(store.shouldAutoStart())
    }

    @Test
    fun `successful projection enables future automatic starts`() {
        var connectedBefore = false
        var writes = 0
        val store = ProjectionStartupStore(
            readSuccessfulProjection = { connectedBefore },
            writeSuccessfulProjection = {
                writes += 1
                connectedBefore = true
                true
            },
        )

        assertTrue(store.recordSuccessfulProjection())
        assertTrue(store.shouldAutoStart())
        assertTrue(store.recordSuccessfulProjection())
        assertTrue(writes == 1)
    }

    @Test
    fun `failed persistence keeps automatic start disabled`() {
        val store = ProjectionStartupStore(
            readSuccessfulProjection = { false },
            writeSuccessfulProjection = { false },
        )

        assertFalse(store.recordSuccessfulProjection())
        assertFalse(store.shouldAutoStart())
    }

    @Test
    fun `first app launch auto starts after a successful projection`() {
        assertTrue(
            shouldRequestProjectionStartOnCreate(
                isAutomationRetry = false,
                hasSuccessfulProjection = true,
                isProjectionSessionActive = false,
                hasPremiumEntitlement = true,
            ),
        )
    }

    @Test
    fun `fresh installation without an automation retry does not request a start`() {
        assertFalse(
            shouldRequestProjectionStartOnCreate(
                isAutomationRetry = false,
                hasSuccessfulProjection = false,
                isProjectionSessionActive = false,
                hasPremiumEntitlement = true,
            ),
        )
    }

    @Test
    fun `restored activity restarts when the projection service is inactive`() {
        assertTrue(
            shouldRequestProjectionStartOnCreate(
                isAutomationRetry = false,
                hasSuccessfulProjection = true,
                isProjectionSessionActive = false,
                hasPremiumEntitlement = true,
            ),
        )
    }

    @Test
    fun `activity recreation does not duplicate an active projection start`() {
        assertFalse(
            shouldRequestProjectionStartOnCreate(
                isAutomationRetry = false,
                hasSuccessfulProjection = true,
                isProjectionSessionActive = true,
                hasPremiumEntitlement = true,
            ),
        )
    }

    @Test
    fun `automation retry still starts a fresh installation`() {
        assertTrue(
            shouldRequestProjectionStartOnCreate(
                isAutomationRetry = true,
                hasSuccessfulProjection = false,
                isProjectionSessionActive = false,
                hasPremiumEntitlement = true,
            ),
        )
    }

    @Test
    fun `automation retry does not duplicate an active projection session`() {
        assertFalse(
            shouldRequestProjectionStartOnCreate(
                isAutomationRetry = true,
                hasSuccessfulProjection = false,
                isProjectionSessionActive = true,
                hasPremiumEntitlement = true,
            ),
        )
    }

    @Test
    fun `free user never auto starts even with prior success or automation retry`() {
        assertFalse(
            shouldRequestProjectionStartOnCreate(
                isAutomationRetry = true,
                hasSuccessfulProjection = true,
                isProjectionSessionActive = false,
                hasPremiumEntitlement = false,
            ),
        )
    }
}
