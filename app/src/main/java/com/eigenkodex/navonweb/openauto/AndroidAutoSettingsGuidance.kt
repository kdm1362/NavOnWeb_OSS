/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.eigenkodex.navonweb.openauto

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import com.eigenkodex.navonweb.session.AndroidAutoConnectionReason
import com.eigenkodex.navonweb.session.AndroidAutoConnectionState
import com.eigenkodex.navonweb.session.AndroidAutoConnectionStatus

/**
 * Arms guidance only for an explicit start request and consumes the first matching failure.
 * Connection status changes are delivered by SessionController; no port or timer polling is used.
 */
internal class HeadUnitServerGuidanceGate {
    private var armed = false

    fun arm() {
        armed = true
    }

    fun disarm() {
        armed = false
    }

    fun consume(connection: AndroidAutoConnectionStatus): Boolean {
        if (!armed) return false
        if (connection.state == AndroidAutoConnectionState.CONNECTED) {
            armed = false
            return false
        }
        if (
            connection.state == AndroidAutoConnectionState.DISCONNECTED &&
            connection.reason == AndroidAutoConnectionReason.HEAD_UNIT_SERVER_STOPPED
        ) {
            armed = false
            return true
        }
        return false
    }
}

/** Opens only exported/public settings entry points and falls back to Android system settings. */
internal object AndroidAutoSettingsNavigator {
    private const val ANDROID_AUTO_PACKAGE = "com.google.android.projection.gearhead"
    private const val ANDROID_AUTO_SETTINGS_ACTION =
        "com.google.android.projection.gearhead.SETTINGS"

    fun open(context: Context): Boolean {
        val candidates = listOf(
            Intent(ANDROID_AUTO_SETTINGS_ACTION)
                .setPackage(ANDROID_AUTO_PACKAGE)
                .addCategory(Intent.CATEGORY_DEFAULT),
            Intent(Intent.ACTION_APPLICATION_PREFERENCES)
                .setPackage(ANDROID_AUTO_PACKAGE)
                .addCategory(Intent.CATEGORY_DEFAULT),
            Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", ANDROID_AUTO_PACKAGE, null),
            ),
            Intent(Settings.ACTION_SETTINGS),
        )
        return candidates.any { candidate ->
            if (context !is Activity) candidate.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try {
                context.startActivity(candidate)
                true
            } catch (_: ActivityNotFoundException) {
                false
            } catch (_: SecurityException) {
                false
            }
        }
    }
}
