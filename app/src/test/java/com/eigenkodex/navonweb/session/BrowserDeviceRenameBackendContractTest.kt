/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.eigenkodex.navonweb.session

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserDeviceRenameBackendContractTest {
    @Test
    fun `server rename is admission serialized and never closes live sessions`() {
        val server = projectFile(
            "app/src/main/java/com/eigenkodex/navonweb/browser/BrowserProbeServer.kt",
        )
        val rename = functionBody(
            server,
            "fun renamePairedBrowserDevice(",
            "fun updatePairedBrowserAccess(",
        )

        assertTrue(rename.contains("synchronized(pairingCodeOperationLock)"))
        assertTrue(rename.contains("browserTrustStore.renameDevice(deviceId, displayName)"))
        assertTrue(rename.contains("result is PairedBrowserMutationResult.Updated"))
        assertTrue(rename.contains("webRtcSignaling.updateDeviceDisplayName"))
        assertFalse(rename.contains("closeSession"))
        assertFalse(rename.contains("closeSessions"))
    }

    @Test
    fun `binder exposes exact rename contract with locked serverless fallback`() {
        val service = projectFile(
            "app/src/main/java/com/eigenkodex/navonweb/service/ProjectionService.kt",
        )
        val rename = functionBody(
            service,
            "fun renamePairedBrowserDevice(",
            "fun updatePairedBrowserAccess(",
        )

        assertTrue(rename.contains("deviceId: String"))
        assertTrue(rename.contains("displayName: String"))
        assertTrue(rename.contains("): PairedBrowserMutationResult"))
        assertTrue(rename.contains("server?.let { return it.renamePairedBrowserDevice"))
        assertTrue(rename.contains("synchronized(browserSessionAdmissionLock)"))
        assertTrue(rename.contains("browserTrustStore.renameDevice(deviceId, displayName)"))
        assertTrue(rename.contains("result is PairedBrowserMutationResult.Updated"))
        assertTrue(rename.contains("webRtcSignaling?.updateDeviceDisplayName"))
    }

    @Test
    fun `live signaling rename mutates public metadata without closing transports`() {
        val controller = projectFile(
            "app/src/main/java/com/eigenkodex/navonweb/browser/webrtc/WebRtcProjectionController.kt",
        )
        val rename = functionBody(
            controller,
            "override fun updateDeviceDisplayName(",
            "override fun activeSessionMetadata(",
        )

        assertTrue(rename.contains("synchronized(lifecycleLock)"))
        assertTrue(rename.contains("activeSessionsLocked()"))
        assertTrue(rename.contains("withDeviceDisplayName(deviceId, displayName)"))
        assertTrue(rename.contains("runtime.snapshot = renamed"))
        assertFalse(rename.contains("closeRuntime"))
        assertFalse(rename.contains("peer.close"))
        assertFalse(rename.contains("DataChannel"))
    }

    @Test
    fun `next authenticated status and session poll render persisted display name`() {
        val server = projectFile(
            "app/src/main/java/com/eigenkodex/navonweb/browser/BrowserProbeServer.kt",
        )
        val statusSession = functionBody(
            server,
            "private fun browserSessionJson(",
            "private fun recordDeviceSeen(",
        )
        val webRtcSession = functionBody(
            server,
            "private fun webRtcSessionJson(",
            "private fun serveFrame(",
        )
        val sessionGet = functionBody(
            server,
            "private fun serveWebRtcSession(",
            "private fun serveWebRtcClose(",
        )

        assertTrue(statusSession.contains("appendJsonString(device.displayName)"))
        assertTrue(webRtcSession.contains("displayNameOverride ?: metadata.displayName"))
        assertTrue(sessionGet.contains("authenticated.device.displayName"))
    }

    private fun functionBody(source: String, startMarker: String, endMarker: String): String {
        val start = source.indexOf(startMarker)
        val end = source.indexOf(endMarker, start + startMarker.length)
        check(start >= 0) { "missing start marker $startMarker" }
        check(end > start) { "missing end marker $endMarker" }
        return source.substring(start, end)
    }

    private fun projectFile(relativePath: String): String {
        val candidates = listOf(File(relativePath), File(relativePath.removePrefix("app/")))
        return candidates.firstOrNull(File::isFile)?.readText(Charsets.UTF_8)
            ?: error("missing project file $relativePath")
    }
}
