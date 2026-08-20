/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.eigenkodex.navonweb.browser

import com.eigenkodex.navonweb.core.VideoViewport
import com.eigenkodex.navonweb.openauto.ProjectionViewportLayout
import com.eigenkodex.navonweb.openauto.ProjectionViewportRequestResult
import com.eigenkodex.navonweb.openauto.ProjectionViewportSnapshot
import java.nio.charset.StandardCharsets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserProjectionViewportApiTest {
    @Test
    fun `active layout exposes encoded margin and content geometry`() {
        val json = BrowserProjectionViewportApi.snapshotJsonString(snapshot())

        assertTrue(json.contains("\"encodedWidth\":1280"))
        assertTrue(json.contains("\"totalMarginWidth\":320"))
        assertTrue(json.contains("\"contentLeft\":160"))
        assertTrue(json.contains("\"contentWidth\":960"))
        assertTrue(json.contains("\"densityDpi\":140"))
        assertTrue(json.contains("\"activationState\":\"active\""))
    }

    @Test
    fun `request policy maps entitlement invalid and accepted outcomes`() {
        val current = snapshot()
        val accepted = BrowserProjectionViewportApi.response(
            ProjectionViewportRequestResult.Accepted(current, changed = true),
        )
        val repeated = BrowserProjectionViewportApi.response(
            ProjectionViewportRequestResult.Accepted(current, changed = false),
        )
        val denied = BrowserProjectionViewportApi.response(
            ProjectionViewportRequestResult.NotEntitled(current),
        )
        val invalid = BrowserProjectionViewportApi.response(
            ProjectionViewportRequestResult.Invalid(current),
        )

        assertEquals(202, accepted.status)
        assertEquals(200, repeated.status)
        assertEquals(403, denied.status)
        assertEquals(422, invalid.status)
        assertTrue(denied.body.bodyText().contains("premium_entitlement_required"))
        assertTrue(invalid.body.bodyText().contains("invalid_projection_viewport"))
    }

    private fun snapshot(): ProjectionViewportSnapshot {
        val layout = ProjectionViewportLayout(
            encodedViewport = VideoViewport(1280, 720),
            totalMarginWidth = 320,
            totalMarginHeight = 0,
        )
        return ProjectionViewportSnapshot(layout, layout, revision = 4L)
    }

    private fun ByteArray.bodyText(): String = String(this, StandardCharsets.UTF_8)
}
