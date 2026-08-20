/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.eigenkodex.navonweb.billing

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReviewPromoSourceBoundaryTest {
    @Test
    fun `review client is public verification code and contains no operational secret`() {
        val client = sourceFile(
            "src/main/java/com/eigenkodex/navonweb/billing/ReviewPromoClient.kt",
        ).readText()
        val protocol = sourceFile(
            "src/main/java/com/eigenkodex/navonweb/billing/ReviewPromoProtocol.kt",
        ).readText()
        val session = sourceFile(
            "src/main/java/com/eigenkodex/navonweb/billing/ReviewPromoSession.kt",
        ).readText()
        val activity = sourceFile(
            "src/main/java/com/eigenkodex/navonweb/MainActivity.kt",
        ).readText()
        val build = sourceFile("build.gradle.kts").readText()
        val promoSource = listOf(client, protocol, session).joinToString("\n")

        assertTrue(client.contains("BuildConfig.REVIEW_PROMO_API_URL"))
        assertTrue(client.contains("/api/review-promo/challenge"))
        assertTrue(client.contains("/api/review-promo/verify"))
        assertTrue(client.contains("/api/review-promo/refresh"))
        assertTrue(client.contains("ReviewPromoRefreshResult"))
        assertTrue(client.contains(".put(\"challenge\", issuedChallenge.value)"))
        assertTrue(activity.contains("reviewPromoClient.refresh(currentGrant)"))
        assertTrue(activity.contains("REVIEW_PROMO_REFRESH_INTERVAL_SECONDS = 15L * 60L"))
        assertTrue(activity.contains("navonweb-review-periodic-online-refresh-v1"))
        assertTrue(activity.contains("ReviewPromoRefreshResult.Revoked"))
        assertTrue(
            activity.contains(
                "premiumBillingFeedback.value = premiumBillingStatusMessage(premiumBilling.state.value)",
            ),
        )
        assertTrue(protocol.contains("SHA256withECDSA"))
        assertTrue(protocol.contains("navonweb-review-entitlement-v1."))
        assertTrue(build.contains("Play-signed builds must retain remote review promotion"))
        assertTrue(build.contains("playInternalTestBuild || playReleaseCandidateBuild"))
        assertTrue(build.contains("playReleaseCandidateBuild"))
        assertEquals(1, Regex("implementation\\(libs\\.play\\.[^)]+\\)").findAll(build).count())
        assertTrue(build.contains("implementation(libs.play.billing)"))

        assertFalse(promoSource.contains("testOnly" + "PromoKodex"))
        assertFalse(promoSource.contains("service" + "_role"))
        assertFalse(promoSource.contains("-----BEGIN PRIVATE KEY-----"))
        assertFalse(session.contains("SharedPreferences"))
        assertTrue(session.contains("navonweb-review-process-memory-only-v1"))
        assertFalse(client.contains("BuildConfig.SUPABASE"))
    }

    private fun sourceFile(relativePath: String): File = listOf(
        File("app", relativePath),
        File(relativePath),
    ).first(File::exists)
}
