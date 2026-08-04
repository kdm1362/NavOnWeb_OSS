/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.pebble.tecomheadunit.ui

import android.content.Context

/** Stores only a closed codec enum. No SDP, ICE data, pairing credential or key is persisted. */
class WebRtcCodecPreferenceStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE,
    )

    fun load(): WebRtcCodecPreferenceOption =
        WebRtcCodecPreferenceOption.fromStorageValue(
            preferences.getString(KEY_CODEC_PREFERENCE, null),
        )

    fun save(preference: WebRtcCodecPreferenceOption): Boolean {
        val normalized = preference.takeIf { it.isSelectable } ?: WebRtcCodecPreferenceOption.AUTO
        return preferences.edit()
            .putString(KEY_CODEC_PREFERENCE, normalized.storageValue)
            .commit()
    }

    private companion object {
        const val PREFERENCES_NAME = "webrtc_projection_settings"
        const val KEY_CODEC_PREFERENCE = "preferred_codec"
    }
}
