/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.eigenkodex.navonweb.diagnostics.upload

import java.util.Locale

internal object DiagnosticClientLocale {
    const val UNKNOWN = "und"
    const val MAX_CHARACTERS = 64
    const val MAX_UTF8_BYTES = 128

    fun current(): String = normalize(Locale.getDefault().toLanguageTag())

    fun normalize(value: String?): String {
        val candidate = value?.trim()?.replace('_', '-').orEmpty()
        if (candidate.equals(UNKNOWN, ignoreCase = true)) return UNKNOWN
        if (candidate.length !in 2..MAX_CHARACTERS || !LANGUAGE_TAG.matches(candidate)) {
            return UNKNOWN
        }
        val canonical = Locale.forLanguageTag(candidate).toLanguageTag()
        return canonical.takeIf { tag ->
            tag != UNKNOWN &&
                tag.length <= MAX_CHARACTERS &&
                tag.toByteArray(Charsets.UTF_8).size <= MAX_UTF8_BYTES &&
                LANGUAGE_TAG.matches(tag)
        } ?: UNKNOWN
    }

    private val LANGUAGE_TAG = Regex("[A-Za-z]{2,8}(?:-[A-Za-z0-9]{1,8})*")
}
