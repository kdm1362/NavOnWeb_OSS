/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.eigenkodex.navonweb.billing

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The public tree must not carry a build switch that grants premium without a verified
 * entitlement. Released binaries were never affected by it, but publishing the switch would
 * advertise a one-flag path around Play ownership, so this guards against a sync reintroducing it.
 */
class PublicSourceEntitlementBoundaryTest {
    @Test
    fun `build script exposes no premium bench property`() {
        val script = repositoryFile("app/build.gradle.kts").readText()
        assertFalse(script.contains("enablePremiumProjectionBench"))
        assertTrue(
            script.contains(
                "buildConfigField(\"boolean\", \"ENABLE_PREMIUM_PROJECTION_BENCH\", \"false\")",
            ),
        )
    }

    @Test
    fun `debug entitlement factory grants nothing on its own`() {
        val factory = repositoryFile(
            "app/src/debug/java/com/eigenkodex/navonweb/openauto/" +
                "ProjectionEntitlementProviderFactory.kt",
        ).readText()
        assertFalse(factory.contains("ENABLE_PREMIUM_PROJECTION_BENCH"))
        assertFalse(factory.contains("serverVerifiedPremium"))
        assertTrue(factory.contains("PlayBillingProjectionEntitlementProvider(context)"))
    }

    private fun repositoryFile(relativePath: String): File =
        listOf(File(relativePath), File("../$relativePath"))
            .firstOrNull(File::isFile)
            ?: error("missing $relativePath")
}
