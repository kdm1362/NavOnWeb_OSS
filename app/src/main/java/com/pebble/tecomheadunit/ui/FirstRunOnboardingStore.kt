/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.pebble.tecomheadunit.ui

import android.content.Context

/** Remembers that the current short first-run guide has been completed on this installation. */
internal class FirstRunOnboardingStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE,
    )

    fun shouldShow(): Boolean = shouldShowFirstRunOnboarding(
        completedVersion = preferences.getInt(KEY_COMPLETED_VERSION, 0),
    )

    fun markComplete() {
        preferences.edit()
            .putInt(KEY_COMPLETED_VERSION, CURRENT_ONBOARDING_VERSION)
            .apply()
    }

    private companion object {
        const val PREFERENCES_NAME = "first_run_onboarding"
        const val KEY_COMPLETED_VERSION = "completed_version"
    }
}

internal const val CURRENT_ONBOARDING_VERSION = 1

internal fun shouldShowFirstRunOnboarding(
    completedVersion: Int,
    requiredVersion: Int = CURRENT_ONBOARDING_VERSION,
): Boolean {
    require(requiredVersion > 0)
    return completedVersion < requiredVersion
}
