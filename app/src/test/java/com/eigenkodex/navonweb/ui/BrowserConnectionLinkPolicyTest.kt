/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.eigenkodex.navonweb.ui

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserConnectionLinkPolicyTest {
    @Test
    fun `only a bounded numeric http address can be revealed`() {
        assertEquals(
            "http://192.168.0.231:8787",
            numericLocalBrowserUrlOrNull(" http://192.168.0.231:8787 "),
        )
        assertEquals(
            "http://127.0.0.1:8787/",
            numericLocalBrowserUrlOrNull("http://127.0.0.1:8787/"),
        )
        assertNull(numericLocalBrowserUrlOrNull("https://navonweb.com"))
        assertNull(numericLocalBrowserUrlOrNull("http://local.test:8787"))
        assertNull(numericLocalBrowserUrlOrNull("http://256.1.1.1:8787"))
        assertNull(numericLocalBrowserUrlOrNull("http://192.168.1.2:0"))
        assertNull(numericLocalBrowserUrlOrNull("http://192.168.1.2:8787/admin"))
    }

    @Test
    fun `normal address remains selected until a continuous three second hold`() {
        val cloud = "https://navonweb.com"
        val local = "http://192.168.0.231:8787"

        assertEquals(3_000L, LOCAL_BROWSER_URL_REVEAL_HOLD_MILLIS)
        assertFalse(shouldRevealLocalBrowserUrl(true, local))
        assertFalse(shouldRevealLocalBrowserUrl(false, local))
        assertTrue(shouldRevealLocalBrowserUrl(null, local))
        assertFalse(shouldRevealLocalBrowserUrl(null, "http://local.test:8787"))

        assertEquals(
            BrowserLinkPresentation(cloud, isDebugLocal = false),
            browserLinkPresentation(cloud, local, revealLocalUrl = false),
        )
        assertEquals(
            BrowserLinkPresentation(local, isDebugLocal = true),
            browserLinkPresentation(cloud, local, revealLocalUrl = true),
        )
    }

    @Test
    fun `link keeps semantic and keyboard activation while hidden hold consumes release`() {
        val uiSource = sourceFile("ui/NavOnWebApp.kt").readText(Charsets.UTF_8)
        val activitySource = sourceFile("MainActivity.kt").readText(Charsets.UTF_8)
        val serviceSource = sourceFile("service/ProjectionService.kt").readText(Charsets.UTF_8)

        assertTrue(uiSource.contains("withTimeoutOrNull(LOCAL_BROWSER_URL_REVEAL_HOLD_MILLIS)"))
        assertTrue(uiSource.contains("tryAwaitRelease()"))
        assertTrue(uiSource.contains("Consume the release that ends the hidden hold."))
        assertTrue(uiSource.contains("independent activation opens the yellow IP link once."))
        assertTrue(uiSource.contains("Color(0xFFFFD54F)"))
        assertTrue(uiSource.contains(".focusable()"))
        assertTrue(uiSource.contains("Key.Enter"))
        assertTrue(uiSource.contains("Key.Spacebar"))
        assertTrue(uiSource.contains("onClick(label = openActionLabel)"))
        assertTrue(activitySource.contains("Intent(Intent.ACTION_VIEW, uri)"))
        assertTrue(activitySource.contains("Intent.CATEGORY_BROWSABLE"))
        assertTrue(serviceSource.contains("BrowserAddressPolicy.select("))
        assertTrue(serviceSource.contains("localBrowserUrl = localUrl"))
    }

    private fun sourceFile(relativePath: String): File = listOf(
        File("app/src/main/java/com/eigenkodex/navonweb/$relativePath"),
        File("src/main/java/com/eigenkodex/navonweb/$relativePath"),
    ).first(File::isFile)
}
