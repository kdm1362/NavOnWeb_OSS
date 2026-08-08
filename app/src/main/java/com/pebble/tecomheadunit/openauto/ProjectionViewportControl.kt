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
    private val densityDpiForProfile: (ProjectionVideoProfile) -> Int = { it.dpi },
) : ProjectionViewportControl {
    private val lock = Any()
    private var active = zeroLayout(initialProfile)
    private var requested = active
    private var revision = 0L
    private var portraitEncodedViewportsEnabled = true
    private var lastBrowserWidth: Int? = null
    private var lastBrowserHeight: Int? = null
    private var lastBrowserDevicePixelRatio = 1.0

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
                val encodedViewport = if (portraitEncodedViewportsEnabled) {
                    profileState.activeProfile.encodedViewportForBrowser(
                        browserWidth = width,
                        browserHeight = height,
                    )
                } else {
                    profileState.activeProfile.landscapeViewport
                }
                ProjectionViewportResolver.resolve(
                    encodedViewport = encodedViewport,
                    browserWidth = width,
                    browserHeight = height,
                    baseDensityDpi = effectiveDensityDpi(
                        profileState.activeProfile,
                        encodedViewport,
                    ),
                    browserDevicePixelRatio = browserDevicePixelRatio,
                )
            }.getOrNull() ?: return ProjectionViewportRequestResult.Invalid(currentSnapshot())
            lastBrowserWidth = width
            lastBrowserHeight = height
            lastBrowserDevicePixelRatio = browserDevicePixelRatio
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

    /**
     * Permanently disables portrait protocol enums for this manager/session and immediately
     * schedules the audited landscape+margin fallback for the last valid browser geometry.
     * Repeated calls are idempotent, so a browser resize cannot start a portrait retry loop.
     */
    internal fun disablePortraitEncodedViewportsForSession(): ProjectionViewportSnapshot {
        var scheduled: ProjectionViewportLayout? = null
        val snapshot = synchronized(lock) {
            if (!portraitEncodedViewportsEnabled) return@synchronized currentSnapshot()
            portraitEncodedViewportsEnabled = false
            val width = lastBrowserWidth ?: return@synchronized currentSnapshot()
            val height = lastBrowserHeight ?: return@synchronized currentSnapshot()
            val profile = profileSnapshot().activeProfile
            val candidate = runCatching {
                ProjectionViewportResolver.resolve(
                    encodedViewport = profile.landscapeViewport,
                    browserWidth = width,
                    browserHeight = height,
                    baseDensityDpi = effectiveDensityDpi(profile, profile.landscapeViewport),
                    browserDevicePixelRatio = lastBrowserDevicePixelRatio,
                )
            }.getOrNull() ?: return@synchronized currentSnapshot()
            if (candidate != requested) {
                requested = candidate
                revision += 1
                scheduled = candidate
            }
            currentSnapshot()
        }
        scheduled?.let(applyScheduler::schedule)
        return snapshot
    }

    internal fun portraitEncodedViewportsEnabledForSession(): Boolean =
        synchronized(lock) { portraitEncodedViewportsEnabled }

    /** Called after a profile switch commits; browser geometry is renegotiated from a safe zero. */
    fun activateProfile(
        profile: ProjectionVideoProfile,
        encodedViewport: VideoViewport = profile.landscapeViewport,
    ): ProjectionViewportSnapshot = activateProfile(
        profile = profile,
        layout = zeroLayout(profile, encodedViewport),
    )

    /** Commits a profile switch without discarding an already negotiated browser aspect. */
    fun activateProfile(
        profile: ProjectionVideoProfile,
        layout: ProjectionViewportLayout,
    ): ProjectionViewportSnapshot =
        synchronized(lock) {
            require(profile.supportsEncodedViewport(layout.encodedViewport)) {
                "encoded viewport is not supported by activated profile"
            }
            require(
                layout.densityDpi == effectiveDensityDpi(profile, layout.encodedViewport),
            ) {
                "activated layout must preserve the effective profile density"
            }
            active = layout
            requested = layout
            revision += 1
            currentSnapshot()
        }

    fun confirmActive(layout: ProjectionViewportLayout): ProjectionViewportSnapshot =
        synchronized(lock) {
            if (layout == requested) active = layout
            currentSnapshot()
        }

    /** Schedules a trusted app-setting density change without accepting density from browsers. */
    internal fun requestDensityDpi(
        profile: ProjectionVideoProfile,
        configuredDensityDpi: Int,
    ): ProjectionViewportRequestResult {
        var scheduled: ProjectionViewportLayout? = null
        val result = synchronized(lock) {
            val profileState = profileSnapshot()
            if (
                profileState.activationState != ProjectionProfileActivationState.ACTIVE ||
                profileState.activeProfile != profile
            ) {
                return ProjectionViewportRequestResult.ProfileApplying(currentSnapshot())
            }
            if (!ProjectionVideoProfile.isSupportedDensityDpi(configuredDensityDpi)) {
                return ProjectionViewportRequestResult.Invalid(currentSnapshot())
            }
            val candidate = requested.copy(
                densityDpi = profile.effectiveDensityDpi(
                    configuredDensityDpi,
                    requested.encodedViewport,
                ),
            )
            if (candidate == requested) {
                ProjectionViewportRequestResult.Accepted(currentSnapshot(), changed = false)
            } else {
                requested = candidate
                revision += 1
                scheduled = candidate
                ProjectionViewportRequestResult.Accepted(currentSnapshot(), changed = true)
            }
        }
        scheduled?.let(applyScheduler::schedule)
        return result
    }

    /** Records a transport that was committed after teardown began, preserving any newer request. */
    internal fun confirmTransportActive(
        layout: ProjectionViewportLayout,
    ): ProjectionViewportSnapshot =
        synchronized(lock) {
            active = layout
            currentSnapshot()
        }

    /** Rolls back only the layout that failed; a newer queued request remains authoritative. */
    fun rejectApply(layout: ProjectionViewportLayout): ProjectionViewportSnapshot =
        synchronized(lock) {
            if (requested == layout) requested = active
            currentSnapshot()
        }

    private fun synchronizeEncodedViewport(profile: ProjectionVideoProfile) {
        if (
            profile.supportsEncodedViewport(active.encodedViewport) &&
            profile.supportsEncodedViewport(requested.encodedViewport)
        ) {
            return
        }
        val expected = profile.landscapeViewport
        val zero = ProjectionViewportLayout(
            encodedViewport = expected,
            totalMarginWidth = 0,
            totalMarginHeight = 0,
            densityDpi = effectiveDensityDpi(profile, expected),
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

    private fun configuredDensityDpi(profile: ProjectionVideoProfile): Int =
        densityDpiForProfile(profile).also { densityDpi ->
            require(ProjectionVideoProfile.isSupportedDensityDpi(densityDpi)) {
                "saved projection density is outside the supported range"
            }
        }

    private fun effectiveDensityDpi(
        profile: ProjectionVideoProfile,
        encodedViewport: VideoViewport,
    ): Int = profile.effectiveDensityDpi(configuredDensityDpi(profile), encodedViewport)

    private fun zeroLayout(
        profile: ProjectionVideoProfile,
        encodedViewport: VideoViewport = profile.landscapeViewport,
    ): ProjectionViewportLayout = ProjectionViewportLayout(
        encodedViewport = encodedViewport,
        totalMarginWidth = 0,
        totalMarginHeight = 0,
        densityDpi = effectiveDensityDpi(profile, encodedViewport),
    )
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
