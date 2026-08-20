/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.eigenkodex.navonweb.session

import java.security.SecureRandom

object PairingToken {
    private val secureRandom = SecureRandom()

    fun create(): String = secureRandom.nextInt(100_000_000).toString().padStart(8, '0')

    fun isValid(candidate: String?, expected: String): Boolean {
        if (candidate == null || candidate.length != expected.length) return false
        var difference = 0
        candidate.indices.forEach { index ->
            difference = difference or (candidate[index].code xor expected[index].code)
        }
        return difference == 0
    }
}
