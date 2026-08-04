/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.pebble.tecomheadunit.service

import android.content.Context

/**
 * Remembers only whether this installation has received a real Android Auto video frame.
 *
 * Browser pairing, endpoint details and credentials are deliberately not stored here. A failed
 * synchronous write leaves automatic start disabled so a successful connection is never inferred.
 */
class ProjectionStartupStore internal constructor(
    private val readSuccessfulProjection: () -> Boolean,
    private val writeSuccessfulProjection: () -> Boolean,
) {
    constructor(context: Context) : this(
        readSuccessfulProjection = {
            context.applicationContext
                .getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
                .getBoolean(KEY_HAS_SUCCESSFUL_PROJECTION, false)
        },
        writeSuccessfulProjection = {
            context.applicationContext
                .getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_HAS_SUCCESSFUL_PROJECTION, true)
                .commit()
        },
    )

    fun shouldAutoStart(): Boolean = readSuccessfulProjection()

    fun recordSuccessfulProjection(): Boolean =
        shouldAutoStart() || writeSuccessfulProjection()

    private companion object {
        const val PREFERENCES_NAME = "projection_startup"
        const val KEY_HAS_SUCCESSFUL_PROJECTION = "has_successful_projection_v1"
    }
}

/**
 * Restarts a previously successful projection whenever no live session exists. This deliberately
 * uses runtime session state instead of Activity saved state: Android can restore an Activity after
 * a package/process restart even though its foreground service no longer exists.
 */
internal fun shouldRequestProjectionStartOnCreate(
    isAutomationRetry: Boolean,
    hasSuccessfulProjection: Boolean,
    isProjectionSessionActive: Boolean,
    hasPremiumEntitlement: Boolean,
): Boolean = hasPremiumEntitlement &&
    !isProjectionSessionActive &&
    (isAutomationRetry || hasSuccessfulProjection)
