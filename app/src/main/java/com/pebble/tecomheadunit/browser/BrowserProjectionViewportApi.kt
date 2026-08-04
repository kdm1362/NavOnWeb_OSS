/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.pebble.tecomheadunit.browser

import com.pebble.tecomheadunit.openauto.ProjectionViewportLayout
import com.pebble.tecomheadunit.openauto.ProjectionViewportRequestResult
import com.pebble.tecomheadunit.openauto.ProjectionViewportSnapshot
import java.nio.charset.StandardCharsets

internal data class BrowserProjectionViewportHttpResponse(
    val status: Int,
    val body: ByteArray,
)

internal object BrowserProjectionViewportApi {
    fun snapshotJsonString(snapshot: ProjectionViewportSnapshot): String = buildString {
        append("{\"activeLayout\":")
        append(layoutJson(snapshot.activeLayout))
        append(",\"requestedLayout\":")
        append(layoutJson(snapshot.requestedLayout))
        append(",\"revision\":").append(snapshot.revision)
        append(",\"activationState\":\"")
        append(snapshot.activationState.wireName)
        append("\"}")
    }

    fun layoutJson(layout: ProjectionViewportLayout): String = buildString {
        val content = layout.contentRect
        append("{\"encodedWidth\":").append(layout.encodedViewport.width)
        append(",\"encodedHeight\":").append(layout.encodedViewport.height)
        append(",\"totalMarginWidth\":").append(layout.totalMarginWidth)
        append(",\"totalMarginHeight\":").append(layout.totalMarginHeight)
        append(",\"contentLeft\":").append(content.left)
        append(",\"contentTop\":").append(content.top)
        append(",\"contentWidth\":").append(content.width)
        append(",\"contentHeight\":").append(content.height)
        append(",\"densityDpi\":").append(layout.densityDpi)
        append('}')
    }

    fun response(result: ProjectionViewportRequestResult): BrowserProjectionViewportHttpResponse =
        when (result) {
            is ProjectionViewportRequestResult.Accepted -> BrowserProjectionViewportHttpResponse(
                status = if (result.changed) 202 else 200,
                body = snapshotBody(result.snapshot),
            )
            is ProjectionViewportRequestResult.NotEntitled -> errorResponse(
                status = 403,
                error = "premium_entitlement_required",
                snapshot = result.snapshot,
            )
            is ProjectionViewportRequestResult.ProfileApplying -> errorResponse(
                status = 409,
                error = "projection_profile_applying",
                snapshot = result.snapshot,
            )
            is ProjectionViewportRequestResult.Invalid -> errorResponse(
                status = 422,
                error = "invalid_projection_viewport",
                snapshot = result.snapshot,
            )
        }

    private fun snapshotBody(snapshot: ProjectionViewportSnapshot): ByteArray =
        snapshotJsonString(snapshot).toByteArray(StandardCharsets.UTF_8)

    private fun errorResponse(
        status: Int,
        error: String,
        snapshot: ProjectionViewportSnapshot,
    ): BrowserProjectionViewportHttpResponse = BrowserProjectionViewportHttpResponse(
        status = status,
        body = buildString {
            append("{\"error\":\"").append(error)
            append("\",\"viewport\":")
            append(snapshotJsonString(snapshot))
            append('}')
        }.toByteArray(StandardCharsets.UTF_8),
    )
}
