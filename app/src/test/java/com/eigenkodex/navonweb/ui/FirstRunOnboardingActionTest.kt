/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.eigenkodex.navonweb.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FirstRunOnboardingActionTest {
    @Test
    fun `second page opens Android Auto settings`() {
        assertEquals(
            FirstRunOnboardingAction.OPEN_ANDROID_AUTO_SETTINGS,
            firstRunOnboardingAction(pageIndex = 1),
        )
    }

    @Test
    fun `other onboarding pages do not show the settings action`() {
        listOf(0, 2, 3, 4).forEach { pageIndex ->
            assertNull(firstRunOnboardingAction(pageIndex))
        }
    }

    @Test
    fun `invalid pages do not expose an action`() {
        assertNull(firstRunOnboardingAction(pageIndex = -1))
        assertNull(firstRunOnboardingAction(pageIndex = 5))
    }

    @Test
    fun `settings close highlight is requested only when onboarding reveals settings`() {
        assertTrue(shouldHighlightSettingsCloseAfterOnboarding(settingsVisible = true))
        assertFalse(shouldHighlightSettingsCloseAfterOnboarding(settingsVisible = false))
        assertEquals(1_000L, SETTINGS_CLOSE_HIGHLIGHT_INTERVAL_MILLIS)
    }
}
