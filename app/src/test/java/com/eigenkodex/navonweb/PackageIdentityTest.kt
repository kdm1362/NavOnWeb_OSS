/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.eigenkodex.navonweb

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PackageIdentityTest {
    @Test
    fun applicationIdMatchesTheUnitTestBuildVariant() {
        val expectedApplicationId = if (BuildConfig.DEBUG) {
            "com.eigenkodex.navonweb.qa"
        } else {
            "com.eigenkodex.navonweb"
        }

        assertEquals(expectedApplicationId, BuildConfig.APPLICATION_ID)
    }

    @Test
    fun releaseApplicationIdRemainsThePlayPackage() {
        val buildFile = listOf(
            File("app/build.gradle.kts"),
            File("build.gradle.kts"),
        ).first(File::isFile).readText()

        assertTrue(buildFile.contains("applicationId = \"com.eigenkodex.navonweb\""))
        assertTrue(buildFile.contains("namespace = \"com.eigenkodex.navonweb\""))
        assertTrue(buildFile.contains("applicationIdSuffix = \".qa\""))
    }

    @Test
    fun activeApplicationSourcesUseOnlyTheOfficialProjectName() {
        val sourceRoot = listOf(
            File("app/src"),
            File("src"),
        ).first(File::isDirectory)
        val retiredName = listOf("te", "com").joinToString("")
        val searchableExtensions = setOf("kt", "kts", "java", "cpp", "hpp", "h", "js", "xml")
        val matches = sourceRoot.walkTopDown()
            .filter(File::isFile)
            .filter { file -> file.extension in searchableExtensions }
            .filter { file -> file.readText(Charsets.UTF_8).contains(retiredName, ignoreCase = true) }
            .map { file -> file.relativeTo(sourceRoot).invariantSeparatorsPath }
            .toList()

        assertTrue("retired project name remains in active sources: $matches", matches.isEmpty())
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
