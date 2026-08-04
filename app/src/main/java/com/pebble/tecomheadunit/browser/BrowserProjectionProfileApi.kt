/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.pebble.tecomheadunit.browser

import com.pebble.tecomheadunit.openauto.ProjectionProfileRequestResult
import com.pebble.tecomheadunit.openauto.ProjectionProfileSnapshot
import com.pebble.tecomheadunit.openauto.ProjectionVideoProfile
import com.pebble.tecomheadunit.openauto.ProjectionViewportSnapshot
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
        append(profileJson(snapshot.activeProfile))
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

    fun profileJson(profile: ProjectionVideoProfile): String = buildString {
        append("{\"id\":")
        appendJsonString(profile.profileId)
        append(",\"width\":").append(profile.width)
        append(",\"height\":").append(profile.height)
        append(",\"androidAutoFramesPerSecond\":").append(profile.androidAutoFramesPerSecond)
        append(",\"webRtcFramesPerSecond\":").append(profile.webRtcFramesPerSecond)
        append(",\"densityDpi\":").append(profile.dpi)
        append(",\"sourceAspectWidth\":").append(profile.sourceAspectWidth)
        append(",\"sourceAspectHeight\":").append(profile.sourceAspectHeight)
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
}
