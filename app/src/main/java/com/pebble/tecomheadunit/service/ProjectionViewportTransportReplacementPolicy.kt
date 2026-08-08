/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.pebble.tecomheadunit.service

import com.pebble.tecomheadunit.core.VideoViewport
import com.pebble.tecomheadunit.openauto.OpenAutoConfig
import com.pebble.tecomheadunit.openauto.ProjectionVideoProfile
import com.pebble.tecomheadunit.openauto.ProjectionViewportLayout
import com.pebble.tecomheadunit.openauto.ProjectionViewportResolver

/** Commit boundary for an encoded-orientation change in the projection media pipeline. */
internal object ProjectionViewportTransportReplacementPolicy {
    /** Keeps browser orientation and content aspect stable while changing the quality profile. */
    fun replacementLayoutForProfile(
        profile: ProjectionVideoProfile,
        currentConfig: OpenAutoConfig,
        portraitEncodedViewportsEnabled: Boolean = true,
        configuredDensityDpi: Int = profile.dpi,
    ): ProjectionViewportLayout {
        val replacementViewport = if (
            portraitEncodedViewportsEnabled &&
            currentConfig.viewport.height > currentConfig.viewport.width
        ) {
            profile.portraitViewport
        } else {
            profile.landscapeViewport
        }
        return ProjectionViewportResolver.resolve(
            encodedViewport = replacementViewport,
            browserWidth = currentConfig.contentViewport.width,
            browserHeight = currentConfig.contentViewport.height,
            baseDensityDpi = profile.effectiveDensityDpi(
                configuredDensityDpi,
                replacementViewport,
            ),
        )
    }

    fun requiresMediaPipelineReplacement(
        currentViewport: VideoViewport,
        requestedViewport: VideoViewport,
    ): Boolean = currentViewport != requestedViewport

    fun canCommit(
        currentWebRtcAvailable: Boolean,
        replacementWebRtcAvailable: Boolean,
    ): Boolean = !currentWebRtcAvailable || replacementWebRtcAvailable
}
