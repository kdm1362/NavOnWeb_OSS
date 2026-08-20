/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.eigenkodex.navonweb.browser

import com.eigenkodex.navonweb.core.VideoViewport
import com.eigenkodex.navonweb.openauto.ProjectionProfileRequestResult
import com.eigenkodex.navonweb.openauto.ProjectionProfileSnapshot
import com.eigenkodex.navonweb.openauto.ProjectionVideoProfile
import com.eigenkodex.navonweb.openauto.ProjectionViewportSnapshot
import java.nio.charset.StandardCharsets

internal data class BrowserProjectionProfileHttpResponse(
    val status: Int,
    val body: ByteArray,
)

internal object BrowserProjectionProfileApi {
    fun snapshotJson(snapshot: ProjectionProfileSnapshot): ByteArray =
        snapshotJsonString(snapshot).toByteArray(StandardCharsets.UTF_8)

    fun snapshotJsonString(
        snapshot: ProjectionProfileSnapshot,
        viewport: ProjectionViewportSnapshot? = null,
    ): String = buildString {
        append("{\"entitlement\":")
        appendJsonString(snapshot.entitlementTier.wireName)
        append(",\"activeProfile\":")
        val activeLayout = viewport?.activeLayout?.takeIf { layout ->
            snapshot.activeProfile.supportsEncodedViewport(layout.encodedViewport)
        }
        val activeViewport = activeLayout?.encodedViewport ?: snapshot.activeProfile.landscapeViewport
        append(
            profileJson(
                profile = snapshot.activeProfile,
                viewport = activeViewport,
                densityDpi = activeLayout?.densityDpi ?: snapshot.activeProfile.dpi,
            ),
        )
        append(",\"requestedProfile\":")
        append(profileJson(snapshot.requestedProfile))
        append(",\"availableProfiles\":[")
        snapshot.availableProfiles.forEachIndexed { index, profile ->
            if (index > 0) append(',')
            append(profileJson(profile))
        }
        append("]")
        append(",\"activationState\":")
        appendJsonString(snapshot.activationState.wireName)
        append(",\"requiresProjectionServiceRestart\":")
        append(snapshot.requiresProjectionServiceRestart)
        append(",\"activationBoundary\":\"automatic_projection_reconnect\"")
        if (viewport != null) {
            append(",\"viewport\":")
            append(BrowserProjectionViewportApi.snapshotJsonString(viewport))
        }
        append('}')
    }

    fun profileJson(
        profile: ProjectionVideoProfile,
        viewport: VideoViewport = profile.landscapeViewport,
        densityDpi: Int = profile.dpi,
    ): String = buildString {
        require(profile.supportsEncodedViewport(viewport)) {
            "encoded viewport is not supported by projection profile"
        }
        val divisor = greatestCommonDivisor(viewport.width, viewport.height)
        append("{\"id\":")
        appendJsonString(profile.profileId)
        append(",\"width\":").append(viewport.width)
        append(",\"height\":").append(viewport.height)
        append(",\"androidAutoFramesPerSecond\":").append(profile.androidAutoFramesPerSecond)
        append(",\"webRtcFramesPerSecond\":").append(profile.webRtcFramesPerSecond)
        require(ProjectionVideoProfile.isSupportedDensityDpi(densityDpi)) {
            "projection density is outside the supported range"
        }
        append(",\"densityDpi\":").append(densityDpi)
        append(",\"sourceAspectWidth\":").append(viewport.width / divisor)
        append(",\"sourceAspectHeight\":").append(viewport.height / divisor)
        append('}')
    }

    fun response(result: ProjectionProfileRequestResult): BrowserProjectionProfileHttpResponse =
        when (result) {
            is ProjectionProfileRequestResult.Accepted -> BrowserProjectionProfileHttpResponse(
                status = if (result.snapshot.isAutomaticReconnectPending) 202 else 200,
                body = snapshotJson(result.snapshot),
            )
            is ProjectionProfileRequestResult.NotEntitled -> BrowserProjectionProfileHttpResponse(
                status = 403,
                body = errorWithSnapshot("premium_entitlement_required", result.snapshot),
            )
            is ProjectionProfileRequestResult.PersistenceFailed -> BrowserProjectionProfileHttpResponse(
                status = 500,
                body = errorWithSnapshot("profile_persistence_failed", result.snapshot),
            )
            ProjectionProfileRequestResult.UnknownProfile -> BrowserProjectionProfileHttpResponse(
                status = 422,
                body = "{\"error\":\"unknown_projection_profile\"}"
                    .toByteArray(StandardCharsets.UTF_8),
            )
        }

    private fun errorWithSnapshot(
        error: String,
        snapshot: ProjectionProfileSnapshot,
    ): ByteArray = buildString {
        append("{\"error\":")
        appendJsonString(error)
        append(",\"projection\":")
        append(snapshotJsonString(snapshot))
        append('}')
    }.toByteArray(StandardCharsets.UTF_8)

    private fun StringBuilder.appendJsonString(value: String) {
        append('"')
        value.forEach { character ->
            when (character) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                in '\u0000'..'\u001f' -> append("\\u").append(
                    character.code.toString(16).padStart(4, '0'),
                )
                else -> append(character)
            }
        }
        append('"')
    }

    private fun greatestCommonDivisor(first: Int, second: Int): Int {
        var a = first
        var b = second
        while (b != 0) {
            val next = a % b
            a = b
            b = next
        }
        return a
    }
}
