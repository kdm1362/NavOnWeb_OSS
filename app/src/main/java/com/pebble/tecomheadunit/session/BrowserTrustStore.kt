/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.pebble.tecomheadunit.session

import android.content.Context

class BrowserTrustStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    @Synchronized
    fun issue(): String {
        val token = BrowserCredential.create()
        val stored = preferences.edit()
            .putString(KEY_TOKEN_DIGEST, BrowserCredential.digest(token))
            .putInt(KEY_VERSION, CREDENTIAL_VERSION)
            .commit()
        check(stored) { "브라우저 연결 정보를 저장하지 못했습니다." }
        return token
    }

    fun isValid(candidate: String?): Boolean {
        if (preferences.getInt(KEY_VERSION, 0) != CREDENTIAL_VERSION) return false
        return BrowserCredential.isValid(
            candidate = candidate,
            expectedDigest = preferences.getString(KEY_TOKEN_DIGEST, null),
        )
    }

    /**
     * True after at least one browser credential was issued successfully.
     *
     * This does not claim that a browser is online, nor that Cloudflare's signed route cookie is
     * still present in that browser. It only controls whether a new public pairing window should
     * be opened automatically.
     */
    fun hasStoredCredential(): Boolean =
        preferences.getInt(KEY_VERSION, 0) == CREDENTIAL_VERSION &&
            BrowserCredential.isStoredDigest(preferences.getString(KEY_TOKEN_DIGEST, null))

    private companion object {
        const val PREFERENCES_NAME = "trusted_browser"
        const val KEY_TOKEN_DIGEST = "token_sha256"
        const val KEY_VERSION = "credential_version"
        const val CREDENTIAL_VERSION = 1
    }
}
