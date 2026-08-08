/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.pebble.tecomheadunit.openauto

import android.content.Context

/** Trusted, app-local DPI values. Browser input never reaches this preference boundary. */
data class ProjectionDpiSettingsSnapshot(
    val densityDpiByProfileId: Map<String, Int>,
) {
    fun densityDpi(profile: ProjectionVideoProfile): Int =
        densityDpiByProfileId[profile.profileId]
            ?.takeIf(ProjectionVideoProfile::isSupportedDensityDpi)
            ?: profile.dpi

    companion object {
        fun recommended(): ProjectionDpiSettingsSnapshot = ProjectionDpiSettingsSnapshot(
            ProjectionVideoProfile.entries.associate { it.profileId to it.dpi },
        )
    }
}

sealed interface ProjectionDpiSettingResult {
    data class Accepted(
        val snapshot: ProjectionDpiSettingsSnapshot,
        val changed: Boolean,
    ) : ProjectionDpiSettingResult

    data class PersistenceFailed(val snapshot: ProjectionDpiSettingsSnapshot) :
        ProjectionDpiSettingResult

    data object Invalid : ProjectionDpiSettingResult
    data object UnknownProfile : ProjectionDpiSettingResult
}

internal interface ProjectionDpiPreferenceStore {
    fun readDensityDpi(profileId: String): Int?
    fun writeDensityDpi(profileId: String, densityDpi: Int)
    fun clearDensityDpi(profileId: String)
}

internal class AndroidProjectionDpiPreferenceStore(context: Context) :
    ProjectionDpiPreferenceStore {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE,
    )

    override fun readDensityDpi(profileId: String): Int? =
        if (preferences.contains(key(profileId))) preferences.getInt(key(profileId), 0) else null

    override fun writeDensityDpi(profileId: String, densityDpi: Int) {
        check(preferences.edit().putInt(key(profileId), densityDpi).commit()) {
            "projection DPI preference was not committed"
        }
    }

    override fun clearDensityDpi(profileId: String) {
        check(preferences.edit().remove(key(profileId)).commit()) {
            "projection DPI preference was not cleared"
        }
    }

    private fun key(profileId: String): String = "$KEY_DENSITY_PREFIX$profileId"

    private companion object {
        const val PREFERENCES_NAME = "projection_profile"
        const val KEY_DENSITY_PREFIX = "density_dpi_"
    }
}

/**
 * Keeps each resolution profile independent. Invalid or legacy-corrupt persisted values fail
 * closed to that profile's recommended value instead of reaching ServiceDiscovery.
 */
internal class ProjectionDpiSettingsManager(
    private val preferenceStore: ProjectionDpiPreferenceStore,
) {
    fun snapshot(): ProjectionDpiSettingsSnapshot = ProjectionDpiSettingsSnapshot(
        ProjectionVideoProfile.entries.associate { profile ->
            profile.profileId to densityDpi(profile)
        },
    )

    fun densityDpi(profile: ProjectionVideoProfile): Int =
        rawDensityDpi(profile)
            ?.takeIf(ProjectionVideoProfile::isSupportedDensityDpi)
            ?: profile.dpi

    fun setDensityDpi(profileId: String?, densityDpi: Int?): ProjectionDpiSettingResult {
        val profile = ProjectionVideoProfile.fromProfileId(profileId)
            ?: return ProjectionDpiSettingResult.UnknownProfile
        val value = densityDpi
            ?.takeIf(ProjectionVideoProfile::isSupportedDensityDpi)
            ?: return ProjectionDpiSettingResult.Invalid
        val before = densityDpi(profile)
        if (value == profile.dpi) {
            if (rawDensityDpi(profile) == null) {
                return ProjectionDpiSettingResult.Accepted(snapshot(), changed = false)
            }
            return try {
                preferenceStore.clearDensityDpi(profile.profileId)
                ProjectionDpiSettingResult.Accepted(
                    snapshot = snapshot(),
                    changed = before != value,
                )
            } catch (_: Throwable) {
                ProjectionDpiSettingResult.PersistenceFailed(snapshot())
            }
        }
        if (before == value) {
            return ProjectionDpiSettingResult.Accepted(snapshot(), changed = false)
        }
        return try {
            preferenceStore.writeDensityDpi(profile.profileId, value)
            ProjectionDpiSettingResult.Accepted(snapshot(), changed = true)
        } catch (_: Throwable) {
            ProjectionDpiSettingResult.PersistenceFailed(snapshot())
        }
    }

    fun resetToRecommended(profileId: String?): ProjectionDpiSettingResult {
        val profile = ProjectionVideoProfile.fromProfileId(profileId)
            ?: return ProjectionDpiSettingResult.UnknownProfile
        return setDensityDpi(profile.profileId, profile.dpi)
    }

    private fun rawDensityDpi(profile: ProjectionVideoProfile): Int? =
        runCatching { preferenceStore.readDensityDpi(profile.profileId) }.getOrNull()
}
