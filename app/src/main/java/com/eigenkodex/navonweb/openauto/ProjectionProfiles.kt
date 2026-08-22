/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.eigenkodex.navonweb.openauto

import android.content.Context
import com.eigenkodex.navonweb.core.VideoViewport

enum class ProjectionAccessTier(val wireName: String) {
    FREE("free"),
    PREMIUM("premium"),
}

/**
 * Closed set of projection sizes that are valid for AA ServiceDiscovery, MediaCodec and WebRTC.
 * Arbitrary browser-provided dimensions never cross this boundary.
 */
enum class ProjectionVideoProfile(
    val profileId: String,
    val requiredTier: ProjectionAccessTier,
    val width: Int,
    val height: Int,
    val androidAutoFramesPerSecond: Int,
    /** Bounded below AA's advertised rate until each hardware profile is measured under load. */
    val webRtcFramesPerSecond: Int,
    val dpi: Int,
    val webRtcMinBitrateBps: Int,
    val webRtcStartBitrateBps: Int,
    val webRtcMaxBitrateBps: Int,
) {
    FREE_800X480(
        profileId = "free-800x480",
        requiredTier = ProjectionAccessTier.FREE,
        width = 800,
        height = 480,
        androidAutoFramesPerSecond = 60,
        webRtcFramesPerSecond = 30,
        dpi = 140,
        webRtcMinBitrateBps = 500_000,
        webRtcStartBitrateBps = 2_500_000,
        webRtcMaxBitrateBps = 4_000_000,
    ),
    PREMIUM_720P(
        profileId = "premium-720p",
        requiredTier = ProjectionAccessTier.PREMIUM,
        width = 1280,
        height = 720,
        androidAutoFramesPerSecond = 60,
        webRtcFramesPerSecond = 30,
        dpi = 200,
        webRtcMinBitrateBps = 1_000_000,
        webRtcStartBitrateBps = 4_000_000,
        webRtcMaxBitrateBps = 8_000_000,
    ),
    PREMIUM_1080P(
        profileId = "premium-1080p",
        requiredTier = ProjectionAccessTier.PREMIUM,
        width = 1920,
        height = 1080,
        androidAutoFramesPerSecond = 60,
        webRtcFramesPerSecond = 30,
        dpi = 220,
        webRtcMinBitrateBps = 2_000_000,
        webRtcStartBitrateBps = 8_000_000,
        webRtcMaxBitrateBps = 14_000_000,
    ),
    ;

    val sourceAspectWidth: Int
        get() = width / greatestCommonDivisor(width, height)

    val sourceAspectHeight: Int
        get() = height / greatestCommonDivisor(width, height)

    /**
     * Android Auto exposes portrait resolutions as distinct protocol enum values. Keep the
     * persisted/profile catalogue dimensions above stable, and select only the live encoded
     * viewport from trusted browser orientation.
     */
    val landscapeViewport: VideoViewport
        get() = VideoViewport(width, height)

    val portraitViewport: VideoViewport
        get() = when (this) {
            FREE_800X480,
            PREMIUM_720P,
            -> VideoViewport(720, 1280)

            PREMIUM_1080P -> VideoViewport(1080, 1920)
        }

    fun encodedViewportForBrowser(browserWidth: Int, browserHeight: Int): VideoViewport {
        require(browserWidth > 0 && browserHeight > 0) {
            "browser dimensions must be positive"
        }
        return if (browserHeight > browserWidth) portraitViewport else landscapeViewport
    }

    fun supportsEncodedViewport(viewport: VideoViewport): Boolean =
        viewport == landscapeViewport || viewport == portraitViewport

    /**
     * Converts the profile-local saved DPI into the value advertised for an encoded viewport.
     *
     * The Basic profile stores its value against the nominal 800x480 frame, but Android Auto's
     * audited portrait enum is 720x1280. That is a 720p-class frame, so scaling this one
     * orientation onto the 720p profile's density keeps the apparent UI size stable without
     * coupling Basic to a Premium preference. The baseline is read from that profile rather than
     * duplicated, so the two cannot drift apart when either default changes. Other profiles use
     * the same resolution class in both orientations.
     */
    fun effectiveDensityDpi(
        configuredDensityDpi: Int,
        viewport: VideoViewport,
    ): Int {
        require(supportsEncodedViewport(viewport)) {
            "encoded viewport is not supported by projection profile $profileId"
        }
        require(isSupportedDensityDpi(configuredDensityDpi)) {
            "configured projection density is outside the supported range"
        }
        val viewportBaselineDpi = if (
            this == FREE_800X480 && viewport == portraitViewport
        ) {
            PREMIUM_720P.dpi
        } else {
            dpi
        }
        val rounded = (
            configuredDensityDpi.toLong() * viewportBaselineDpi + dpi / 2L
        ) / dpi
        return rounded.toInt().coerceIn(MIN_DENSITY_DPI, MAX_DENSITY_DPI)
    }

    fun toOpenAutoConfig(viewport: VideoViewport = landscapeViewport): OpenAutoConfig {
        return toOpenAutoConfig(
            viewport = viewport,
            densityDpi = effectiveDensityDpi(dpi, viewport),
        )
    }

    fun toOpenAutoConfig(
        viewport: VideoViewport = landscapeViewport,
        densityDpi: Int,
    ): OpenAutoConfig {
        require(supportsEncodedViewport(viewport)) {
            "encoded viewport is not supported by projection profile $profileId"
        }
        require(isSupportedDensityDpi(densityDpi)) {
            "projection density is outside the supported range"
        }
        return OpenAutoConfig(
            viewport = viewport,
            fps = androidAutoFramesPerSecond,
            dpi = densityDpi,
        )
    }

    companion object {
        fun fromProfileId(profileId: String?): ProjectionVideoProfile? =
            entries.firstOrNull { it.profileId == profileId?.trim()?.lowercase() }

        const val MIN_DENSITY_DPI = 72
        const val MAX_DENSITY_DPI = 320

        fun isSupportedDensityDpi(densityDpi: Int): Boolean =
            densityDpi in MIN_DENSITY_DPI..MAX_DENSITY_DPI

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
}

data class ProjectionProfileSnapshot(
    val entitlementTier: ProjectionAccessTier,
    /** Profile currently negotiated with Android Auto and the WebRTC source. */
    val activeProfile: ProjectionVideoProfile,
    /** Persisted target; a difference from [activeProfile] means automatic reconnect is in flight. */
    val requestedProfile: ProjectionVideoProfile,
    /**
     * Complete profile catalogue for the settings screen. Profiles stay visible even when the
     * current entitlement cannot activate them; [isEntitledTo] is the selection boundary.
     */
    val availableProfiles: List<ProjectionVideoProfile>,
) {
    val activationState: ProjectionProfileActivationState
        get() = if (activeProfile == requestedProfile) {
            ProjectionProfileActivationState.ACTIVE
        } else {
            ProjectionProfileActivationState.APPLYING
        }

    val isAutomaticReconnectPending: Boolean
        get() = activationState == ProjectionProfileActivationState.APPLYING

    /** Kept for wire compatibility; profile activation no longer needs a user service restart. */
    val requiresProjectionServiceRestart: Boolean
        get() = false

    fun isEntitledTo(profile: ProjectionVideoProfile): Boolean =
        profile.requiredTier == ProjectionAccessTier.FREE ||
            entitlementTier == ProjectionAccessTier.PREMIUM
}

enum class ProjectionProfileActivationState(val wireName: String) {
    ACTIVE("active"),
    APPLYING("applying"),
}

sealed interface ProjectionProfileRequestResult {
    data class Accepted(val snapshot: ProjectionProfileSnapshot) : ProjectionProfileRequestResult
    data class NotEntitled(val snapshot: ProjectionProfileSnapshot) : ProjectionProfileRequestResult
    data class PersistenceFailed(val snapshot: ProjectionProfileSnapshot) : ProjectionProfileRequestResult
    data object UnknownProfile : ProjectionProfileRequestResult
}

/** Browser-facing control surface. It deliberately contains no entitlement mutation method. */
interface ProjectionProfileControl {
    fun snapshot(): ProjectionProfileSnapshot
    fun requestProfile(profileId: String?): ProjectionProfileRequestResult
}

/**
 * Routes a profile request through the control whose trusted snapshot currently grants it while
 * keeping an active session's fixed entitlement unchanged.
 */
internal fun requestProjectionProfileWithCurrentEntitlement(
    activeControl: ProjectionProfileControl?,
    currentEntitlementControl: ProjectionProfileControl,
    profileId: String?,
): ProjectionProfileRequestResult {
    val active = activeControl ?: return currentEntitlementControl.requestProfile(profileId)
    val profile = ProjectionVideoProfile.fromProfileId(profileId)
        ?: return active.requestProfile(profileId)
    val activeEntitled = active.snapshot().isEntitledTo(profile)
    val currentEntitled = currentEntitlementControl.snapshot().isEntitledTo(profile)
    return if (!activeEntitled && currentEntitled) {
        currentEntitlementControl.requestProfile(profileId)
    } else {
        active.requestProfile(profileId)
    }
}

/**
 * Trusted native entitlement boundary. A browser request is never passed into this provider and
 * therefore cannot supply a tier, receipt, token or grant ID. Concrete grants retain provenance:
 * server verification, the local Play ownership query, and process-only review access are
 * distinct types.
 */
internal fun interface ServerProjectionEntitlementProvider {
    fun currentGrant(): ServerProjectionEntitlementGrant
}

internal sealed interface ServerProjectionEntitlementGrant {
    val tier: ProjectionAccessTier

    data object Free : ServerProjectionEntitlementGrant {
        override val tier: ProjectionAccessTier = ProjectionAccessTier.FREE
    }

    class Premium internal constructor(
        /** Opaque server-side evidence identifier; never exposed through the browser API. */
        internal val evidenceId: String,
    ) : ServerProjectionEntitlementGrant {
        init {
            require(evidenceId.isNotBlank() && evidenceId.length <= 256) {
                "invalid server entitlement evidence"
            }
        }

        override val tier: ProjectionAccessTier = ProjectionAccessTier.PREMIUM
    }

    class ClientPlayPurchase internal constructor(
        /** SHA-256 purchase-token digest from a successful local BillingClient ownership query. */
        internal val purchaseTokenDigest: String,
    ) : ServerProjectionEntitlementGrant {
        init {
            require(purchaseTokenDigest.matches(Regex("[0-9A-F]{64}"))) {
                "invalid cached Play purchase evidence"
            }
        }

        override val tier: ProjectionAccessTier = ProjectionAccessTier.PREMIUM
    }

    /** Process-only access entered locally by a Play reviewer; never persisted as ownership. */
    data object ReviewPromo : ServerProjectionEntitlementGrant {
        override val tier: ProjectionAccessTier = ProjectionAccessTier.PREMIUM
    }

    companion object {
        internal fun serverVerifiedPremium(evidenceId: String): ServerProjectionEntitlementGrant =
            Premium(evidenceId)

        internal fun clientPlayPurchase(purchaseTokenDigest: String): ServerProjectionEntitlementGrant =
            ClientPlayPurchase(purchaseTokenDigest)
    }
}

internal data object FailClosedProjectionEntitlementProvider :
    ServerProjectionEntitlementProvider {
    override fun currentGrant(): ServerProjectionEntitlementGrant =
        ServerProjectionEntitlementGrant.Free
}

internal interface ProjectionProfilePreferenceStore {
    fun readRequestedProfileId(): String?
    fun writeRequestedProfileId(profileId: String)
}

/** Schedules the AA/WebRTC transport-only reconnect required by a new profile. */
internal fun interface ProjectionProfileApplyScheduler {
    fun schedule(profile: ProjectionVideoProfile)
}

internal data object NoOpProjectionProfileApplyScheduler : ProjectionProfileApplyScheduler {
    override fun schedule(profile: ProjectionVideoProfile) = Unit
}

internal class AndroidProjectionProfilePreferenceStore(context: Context) :
    ProjectionProfilePreferenceStore {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE,
    )

    override fun readRequestedProfileId(): String? =
        preferences.getString(KEY_REQUESTED_PROFILE, null)

    override fun writeRequestedProfileId(profileId: String) {
        check(preferences.edit().putString(KEY_REQUESTED_PROFILE, profileId).commit()) {
            "projection profile preference was not committed"
        }
    }

    private companion object {
        const val PREFERENCES_NAME = "projection_profile"
        const val KEY_REQUESTED_PROFILE = "requested_profile_id"
    }
}

/**
 * Resolves entitlement once when a projection service session starts. A profile request is
 * persisted and handed to [applyScheduler]. ProjectionService performs a transport-only reconnect
 * because AA resolution is negotiated during ServiceDiscovery; [confirmActive] moves the public
 * snapshot to ACTIVE only after the new decoder/WebRTC source and AA runtime have been installed.
 */
internal class ProjectionProfileManager(
    entitlementProvider: ServerProjectionEntitlementProvider,
    private val preferenceStore: ProjectionProfilePreferenceStore,
    private val applyScheduler: ProjectionProfileApplyScheduler = NoOpProjectionProfileApplyScheduler,
) : ProjectionProfileControl {
    private val lock = Any()
    private val entitlementTier = runCatching { entitlementProvider.currentGrant().tier }
        .getOrDefault(ProjectionAccessTier.FREE)
    private val available = ProjectionVideoProfile.entries
    private var active = runCatching {
        ProjectionVideoProfile.fromProfileId(preferenceStore.readRequestedProfileId())
    }.getOrNull()
        ?.takeIf(::isEntitledTo)
        ?: ProjectionVideoProfile.FREE_800X480
    private var requested = active

    override fun snapshot(): ProjectionProfileSnapshot = synchronized(lock) { currentSnapshot() }

    override fun requestProfile(profileId: String?): ProjectionProfileRequestResult {
        var schedule: ProjectionVideoProfile? = null
        val result = synchronized(lock) {
            val profile = ProjectionVideoProfile.fromProfileId(profileId)
                ?: return ProjectionProfileRequestResult.UnknownProfile
            if (!isEntitledTo(profile)) {
                return ProjectionProfileRequestResult.NotEntitled(currentSnapshot())
            }
            try {
                preferenceStore.writeRequestedProfileId(profile.profileId)
            } catch (_: Throwable) {
                return ProjectionProfileRequestResult.PersistenceFailed(currentSnapshot())
            }
            val previousRequested = requested
            requested = profile
            if (profile != active || previousRequested != profile) schedule = profile
            ProjectionProfileRequestResult.Accepted(currentSnapshot())
        }
        schedule?.let(applyScheduler::schedule)
        return result
    }

    /** Called by ProjectionService after its new controller and AA reconnect are committed. */
    internal fun confirmActive(profile: ProjectionVideoProfile): ProjectionProfileSnapshot =
        synchronized(lock) {
            if (isEntitledTo(profile)) active = profile
            currentSnapshot()
        }

    /** Rolls back only the request that failed; a newer queued request is left untouched. */
    internal fun rejectApply(profile: ProjectionVideoProfile): ProjectionProfileSnapshot =
        synchronized(lock) {
            if (requested == profile) {
                requested = active
                runCatching { preferenceStore.writeRequestedProfileId(active.profileId) }
            }
            currentSnapshot()
        }

    private fun currentSnapshot(): ProjectionProfileSnapshot = ProjectionProfileSnapshot(
        entitlementTier = entitlementTier,
        activeProfile = active,
        requestedProfile = requested,
        availableProfiles = available.toList(),
    )

    private fun isEntitledTo(profile: ProjectionVideoProfile): Boolean =
        profile.requiredTier == ProjectionAccessTier.FREE ||
            entitlementTier == ProjectionAccessTier.PREMIUM
}

data object FreeProjectionProfileControl : ProjectionProfileControl {
    private val snapshot = ProjectionProfileSnapshot(
        entitlementTier = ProjectionAccessTier.FREE,
        activeProfile = ProjectionVideoProfile.FREE_800X480,
        requestedProfile = ProjectionVideoProfile.FREE_800X480,
        availableProfiles = ProjectionVideoProfile.entries,
    )

    override fun snapshot(): ProjectionProfileSnapshot = snapshot

    override fun requestProfile(profileId: String?): ProjectionProfileRequestResult =
        when (ProjectionVideoProfile.fromProfileId(profileId)) {
            null -> ProjectionProfileRequestResult.UnknownProfile
            ProjectionVideoProfile.FREE_800X480 -> ProjectionProfileRequestResult.Accepted(snapshot)
            else -> ProjectionProfileRequestResult.NotEntitled(snapshot)
        }
}
