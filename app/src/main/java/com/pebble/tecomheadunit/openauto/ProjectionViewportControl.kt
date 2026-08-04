/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.pebble.tecomheadunit.openauto

import com.pebble.tecomheadunit.core.VideoViewport

enum class ProjectionViewportActivationState(val wireName: String) {
    ACTIVE("active"),
    APPLYING("applying"),
}

data class ProjectionViewportSnapshot(
    val activeLayout: ProjectionViewportLayout,
    val requestedLayout: ProjectionViewportLayout,
    val revision: Long,
) {
    val activationState: ProjectionViewportActivationState
        get() = if (activeLayout == requestedLayout) {
            ProjectionViewportActivationState.ACTIVE
        } else {
            ProjectionViewportActivationState.APPLYING
        }
}

sealed interface ProjectionViewportRequestResult {
    data class Accepted(
        val snapshot: ProjectionViewportSnapshot,
        val changed: Boolean,
    ) : ProjectionViewportRequestResult

    data class NotEntitled(val snapshot: ProjectionViewportSnapshot) :
        ProjectionViewportRequestResult

    data class ProfileApplying(val snapshot: ProjectionViewportSnapshot) :
        ProjectionViewportRequestResult

    data class Invalid(val snapshot: ProjectionViewportSnapshot) :
        ProjectionViewportRequestResult
}

/** Browser-facing viewport surface. It accepts dimensions only, never an entitlement claim. */
interface ProjectionViewportControl {
    fun snapshot(): ProjectionViewportSnapshot
    fun requestViewport(
        browserWidth: Int?,
        browserHeight: Int?,
        browserDevicePixelRatio: Double = 1.0,
    ): ProjectionViewportRequestResult
}

internal fun interface ProjectionViewportApplyScheduler {
    fun schedule(layout: ProjectionViewportLayout)
}

internal class ProjectionViewportManager(
    private val profileSnapshot: () -> ProjectionProfileSnapshot,
    initialProfile: ProjectionVideoProfile,
    private val applyScheduler: ProjectionViewportApplyScheduler,
) : ProjectionViewportControl {
    private val lock = Any()
    private var active = zeroLayout(initialProfile)
    private var requested = active
    private var revision = 0L

    override fun snapshot(): ProjectionViewportSnapshot = synchronized(lock) { currentSnapshot() }

    override fun requestViewport(
        browserWidth: Int?,
        browserHeight: Int?,
        browserDevicePixelRatio: Double,
    ): ProjectionViewportRequestResult {
        var scheduled: ProjectionViewportLayout? = null
        val result = synchronized(lock) {
            val profileState = profileSnapshot()
            if (profileState.activationState != ProjectionProfileActivationState.ACTIVE) {
                return ProjectionViewportRequestResult.ProfileApplying(currentSnapshot())
            }
            synchronizeEncodedViewport(profileState.activeProfile)
            val width = browserWidth ?: return ProjectionViewportRequestResult.Invalid(currentSnapshot())
            val height = browserHeight ?: return ProjectionViewportRequestResult.Invalid(currentSnapshot())
            val candidate = runCatching {
                ProjectionViewportResolver.resolve(
                    encodedViewport = active.encodedViewport,
                    browserWidth = width,
                    browserHeight = height,
                    baseDensityDpi = profileState.activeProfile.dpi,
                    browserDevicePixelRatio = browserDevicePixelRatio,
                )
            }.getOrNull() ?: return ProjectionViewportRequestResult.Invalid(currentSnapshot())
            val stabilized = ProjectionViewportResolver.stabilize(requested, candidate)
            if (stabilized == requested) {
                ProjectionViewportRequestResult.Accepted(currentSnapshot(), changed = false)
            } else {
                requested = stabilized
                revision += 1
                scheduled = stabilized
                ProjectionViewportRequestResult.Accepted(currentSnapshot(), changed = true)
            }
        }
        scheduled?.let(applyScheduler::schedule)
        return result
    }

    /** Called after a profile switch commits; browser geometry is renegotiated from a safe zero. */
    fun activateProfile(profile: ProjectionVideoProfile): ProjectionViewportSnapshot =
        synchronized(lock) {
            val zero = zeroLayout(profile)
            active = zero
            requested = zero
            revision += 1
            currentSnapshot()
        }

    fun confirmActive(layout: ProjectionViewportLayout): ProjectionViewportSnapshot =
        synchronized(lock) {
            if (layout == requested) active = layout
            currentSnapshot()
        }

    /** Rolls back only the layout that failed; a newer queued request remains authoritative. */
    fun rejectApply(layout: ProjectionViewportLayout): ProjectionViewportSnapshot =
        synchronized(lock) {
            if (requested == layout) requested = active
            currentSnapshot()
        }

    private fun synchronizeEncodedViewport(profile: ProjectionVideoProfile) {
        val expected = VideoViewport(profile.width, profile.height)
        if (active.encodedViewport == expected && requested.encodedViewport == expected) return
        val zero = ProjectionViewportLayout(
            encodedViewport = expected,
            totalMarginWidth = 0,
            totalMarginHeight = 0,
            densityDpi = profile.dpi,
        )
        active = zero
        requested = zero
        revision += 1
    }

    private fun currentSnapshot(): ProjectionViewportSnapshot = ProjectionViewportSnapshot(
        activeLayout = active,
        requestedLayout = requested,
        revision = revision,
    )

    private companion object {
        fun zeroLayout(profile: ProjectionVideoProfile): ProjectionViewportLayout =
            ProjectionViewportLayout(
                encodedViewport = VideoViewport(profile.width, profile.height),
                totalMarginWidth = 0,
                totalMarginHeight = 0,
                densityDpi = profile.dpi,
            )
    }
}

data object FreeProjectionViewportControl : ProjectionViewportControl {
    private val layout = ProjectionViewportLayout(
        encodedViewport = VideoViewport(800, 480),
        totalMarginWidth = 0,
        totalMarginHeight = 0,
        densityDpi = ProjectionVideoProfile.FREE_800X480.dpi,
    )
    private val current = ProjectionViewportSnapshot(layout, layout, revision = 0L)

    override fun snapshot(): ProjectionViewportSnapshot = current

    override fun requestViewport(
        browserWidth: Int?,
        browserHeight: Int?,
        browserDevicePixelRatio: Double,
    ): ProjectionViewportRequestResult = ProjectionViewportRequestResult.NotEntitled(current)
}
