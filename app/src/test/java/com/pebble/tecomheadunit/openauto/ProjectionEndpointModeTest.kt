/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.pebble.tecomheadunit.openauto

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProjectionEndpointModeTest {
    @Test
    fun onlySamePhoneModeRemains() {
        assertEquals(listOf(ProjectionEndpointMode.THIS_PHONE), ProjectionEndpointMode.entries)
    }

    @Test
    fun runtimeEndpointIsAlwaysPinnedToAndroidAutoLoopback() {
        val endpoint = ProjectionEndpointResolver.resolve()

        assertEquals(ProjectionEndpointMode.THIS_PHONE, endpoint.mode)
        assertEquals("127.0.0.1", endpoint.host)
        assertEquals(5277, endpoint.port)
        assertTrue(endpoint.isEnabled)
    }

    @Test
    fun appDoesNotExposeOrPersistRemoteEndpointSelection() {
        val activity = readSource("main/java/com/pebble/tecomheadunit/MainActivity.kt")
        val service = readSource("main/java/com/pebble/tecomheadunit/service/ProjectionService.kt")
        val bridge = readSource("main/java/com/pebble/tecomheadunit/openauto/NativeOpenAutoBridge.kt")
        val ui = readSource("main/java/com/pebble/tecomheadunit/ui/TecomHeadUnitApp.kt")

        assertFalse(ui.contains("Android Auto 연결 위치"))
        assertFalse(ui.contains("EndpointModeCard"))
        assertFalse(activity.contains("ProjectionEndpointModeStore"))
        assertFalse(service.contains("EXTRA_ENDPOINT_MODE"))
        assertFalse(bridge.contains("BuildConfig.AASDK_TCP_HOST"))
        assertFalse(bridge.contains("BuildConfig.AASDK_TCP_PORT"))
        assertTrue(bridge.contains("ProjectionEndpointResolver.resolve()"))
    }

    private fun readSource(relativePath: String): String {
        val candidates = listOf(
            File("app/src/$relativePath"),
            File("src/$relativePath"),
        )
        val source = candidates.firstOrNull(File::isFile)
            ?: error("missing source $relativePath")
        return source.readText(Charsets.UTF_8)
    }
}
