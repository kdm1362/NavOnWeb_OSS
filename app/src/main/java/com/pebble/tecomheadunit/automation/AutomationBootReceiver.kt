/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.pebble.tecomheadunit.automation

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Restores only the explicitly selected hotspot event monitor; it never starts projection. */
class AutomationBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (
            intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != Intent.ACTION_MY_PACKAGE_REPLACED
        ) {
            return
        }
        HotspotAutomationMonitorService.refresh(context)
    }
}
