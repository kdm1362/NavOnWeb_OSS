/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.pebble.tecomheadunit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PackageIdentityTest {
    @Test
    fun androidApplicationIdUsesEigenkodexNamespace() {
        assertEquals("com.eigenkodex.navonweb", BuildConfig.APPLICATION_ID)
    }

    @Test
    fun correspondingSourceLinksToPublicOssRepository() {
        val repositoryUrl = "https://github.com/kdm1362/NavOnWeb_OSS"
        val versionedSourceTagUrl = Regex(
            "${Regex.escape(repositoryUrl)}/tree/v[0-9][A-Za-z0-9._-]{0,119}-source",
        )
        val fullCommitUrl = Regex(
            "${Regex.escape(repositoryUrl)}/commit/[0-9a-fA-F]{40}",
        )
        assertTrue(
            BuildConfig.SOURCE_CODE_URL == repositoryUrl ||
                versionedSourceTagUrl.matches(BuildConfig.SOURCE_CODE_URL) ||
                fullCommitUrl.matches(BuildConfig.SOURCE_CODE_URL),
        )
    }
}
