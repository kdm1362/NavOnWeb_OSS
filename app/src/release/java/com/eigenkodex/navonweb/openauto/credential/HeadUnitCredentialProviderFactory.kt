/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.eigenkodex.navonweb.openauto.credential

import android.content.Context

object HeadUnitCredentialProviderFactory {
    fun create(@Suppress("UNUSED_PARAMETER") context: Context): HeadUnitCredentialProvider =
        BundledAasdkCredentialProvider()
}
