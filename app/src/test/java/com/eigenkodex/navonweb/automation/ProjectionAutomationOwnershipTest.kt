/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.eigenkodex.navonweb.automation

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProjectionAutomationOwnershipTest {
    @Test
    fun `matching automation generation owns the session`() {
        val ownership = ProjectionAutomationOwnership()
        val owner = AutomationProjectionOwner(AutomationTriggerMode.HOTSPOT, 8L)

        ownership.claim(owner)

        assertTrue(ownership.matches(owner))
        assertFalse(ownership.matches(owner.copy(generation = 9L)))
    }

    @Test
    fun `manual start clears automation ownership before a conditional stop`() {
        val ownership = ProjectionAutomationOwnership()
        val owner = AutomationProjectionOwner(AutomationTriggerMode.HOTSPOT, 3L)
        ownership.claim(owner)

        ownership.clearForManualStart()

        assertFalse(ownership.matches(owner))
    }

    @Test
    fun `stale conditional clear cannot erase a newer owner`() {
        val ownership = ProjectionAutomationOwnership()
        val oldOwner = AutomationProjectionOwner(AutomationTriggerMode.HOTSPOT, 3L)
        val currentOwner = AutomationProjectionOwner(AutomationTriggerMode.HOTSPOT, 4L)
        ownership.claim(currentOwner)

        assertFalse(ownership.clear(oldOwner))
        assertTrue(ownership.matches(currentOwner))
    }

    @Test
    fun `automatic start requires the active persisted source generation`() {
        val owner = AutomationProjectionOwner(AutomationTriggerMode.HOTSPOT, 11L)

        assertTrue(
            isAutomationProjectionStartAuthorized(
                owner,
                AutomationControlState(
                    mode = AutomationTriggerMode.HOTSPOT,
                    generation = 11L,
                    lastTriggerActive = true,
                ),
            ),
        )
        assertFalse(
            isAutomationProjectionStartAuthorized(
                owner,
                AutomationControlState(
                    mode = AutomationTriggerMode.NONE,
                    generation = 12L,
                ),
            ),
        )
        assertFalse(
            isAutomationProjectionStartAuthorized(
                owner,
                AutomationControlState(
                    mode = AutomationTriggerMode.HOTSPOT,
                    generation = 11L,
                    lastTriggerActive = null,
                ),
            ),
        )
    }
}
