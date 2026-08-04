/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.pebble.tecomheadunit.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FirstRunOnboardingStoreTest {
    @Test
    fun newInstallShowsCurrentGuide() {
        assertTrue(shouldShowFirstRunOnboarding(completedVersion = 0))
    }

    @Test
    fun completedCurrentGuideStaysDismissed() {
        assertFalse(
            shouldShowFirstRunOnboarding(
                completedVersion = CURRENT_ONBOARDING_VERSION,
            ),
        )
    }

    @Test
    fun newerGuideCanBeShownAfterAContentRevision() {
        assertTrue(shouldShowFirstRunOnboarding(completedVersion = 1, requiredVersion = 2))
        assertFalse(shouldShowFirstRunOnboarding(completedVersion = 2, requiredVersion = 2))
    }
}
