/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.eigenkodex.navonweb.service

import com.eigenkodex.navonweb.core.VideoViewport
import com.eigenkodex.navonweb.openauto.OpenAutoConfig
import com.eigenkodex.navonweb.openauto.ProjectionVideoProfile
import com.eigenkodex.navonweb.openauto.ProjectionViewportLayout
import com.eigenkodex.navonweb.openauto.ProjectionViewportResolver

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
