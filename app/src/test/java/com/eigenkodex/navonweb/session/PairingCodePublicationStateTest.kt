package com.eigenkodex.navonweb.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PairingCodePublicationStateTest {
    @Test
    fun firstBrowserGetsAnInitialPublishedCode() {
        val state = PairingCodePublicationState(
            initialCode = "01234567",
            hasStoredBrowserCredential = false,
        )

        assertEquals("01234567", state.currentCode())
    }

    @Test
    fun rememberedBrowserStartsWithoutPublishingACloudCode() {
        val state = PairingCodePublicationState(
            initialCode = "01234567",
            hasStoredBrowserCredential = true,
            nowMillis = { 1_000L },
        )

        assertNull(state.currentCode())
        val request = state.request { "65432109" }
        assertEquals("65432109", request.code)
        assertTrue(request.shouldPublish)
        assertEquals("65432109", state.currentCode())
    }

    @Test
    fun activeCodeCanBeForceReplacedButRepeatedTapsAreCooledDown() {
        var now = 1_000L
        var generated = 0
        val state = PairingCodePublicationState(
            initialCode = "01234567",
            hasStoredBrowserCredential = false,
            nowMillis = { now },
            refreshCooldownMillis = 10_000L,
        )

        val firstRefresh = state.request {
            generated += 1
            "65432109"
        }
        assertEquals("65432109", firstRefresh.code)
        assertTrue(firstRefresh.shouldPublish)
        assertEquals(1, generated)

        now = 10_999L
        val cooledDownTap = state.request {
            generated += 1
            "11111111"
        }
        assertEquals("65432109", cooledDownTap.code)
        assertFalse(cooledDownTap.shouldPublish)
        assertEquals(1, generated)

        now = 11_000L
        val afterCooldown = state.request {
            generated += 1
            "11111111"
        }
        assertEquals("11111111", afterCooldown.code)
        assertTrue(afterCooldown.shouldPublish)
        assertEquals(2, generated)
        assertEquals("11111111", state.currentCode())
    }

    @Test
    fun closingAWindowAllowsImmediateRecoveryEvenInsideThePriorCooldown() {
        var now = 1_000L
        val state = PairingCodePublicationState(
            initialCode = "01234567",
            hasStoredBrowserCredential = true,
            nowMillis = { now },
        )

        assertEquals("65432109", state.request { "65432109" }.code)
        assertTrue(state.close())
        now += 1L
        val recovery = state.request { "11111111" }
        assertEquals("11111111", recovery.code)
        assertTrue(recovery.shouldPublish)
    }

    @Test
    fun everyPairingWindowClosesAtItsTenMinuteDeadline() {
        val onboarding = PairingCodePublicationState("01234567", hasStoredBrowserCredential = false)
        assertNull(onboarding.afterRotation("65432109", keepOpen = false))
        assertNull(onboarding.currentCode())

        val remembered = PairingCodePublicationState("01234567", hasStoredBrowserCredential = true)
        remembered.request { "65432109" }
        assertNull(remembered.afterRotation("11111111", keepOpen = false))
        assertNull(remembered.currentCode())
    }

    @Test
    fun successfulPairingWithdrawsOnlyAnActiveCode() {
        val state = PairingCodePublicationState("01234567", hasStoredBrowserCredential = false)

        assertTrue(state.close())
        assertNull(state.currentCode())
        assertFalse(state.close())
    }
}
