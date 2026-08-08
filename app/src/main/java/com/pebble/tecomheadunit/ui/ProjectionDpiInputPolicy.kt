/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.pebble.tecomheadunit.ui

import com.pebble.tecomheadunit.openauto.ProjectionVideoProfile

/** Validation performed only when the user finishes or explicitly applies a DPI draft. */
internal enum class ProjectionDpiInputError {
    REQUIRED,
    NOT_AN_INTEGER,
    OUT_OF_RANGE,
}

internal sealed interface ProjectionDpiInputValidation {
    data class Valid(val densityDpi: Int) : ProjectionDpiInputValidation

    data class Invalid(val error: ProjectionDpiInputError) : ProjectionDpiInputValidation
}

internal fun validateProjectionDpiDraft(draft: String): ProjectionDpiInputValidation {
    val valueText = draft.trim()
    if (valueText.isEmpty()) {
        return ProjectionDpiInputValidation.Invalid(ProjectionDpiInputError.REQUIRED)
    }
    val densityDpi = valueText.toIntOrNull()
        ?: return ProjectionDpiInputValidation.Invalid(ProjectionDpiInputError.NOT_AN_INTEGER)
    return if (ProjectionVideoProfile.isSupportedDensityDpi(densityDpi)) {
        ProjectionDpiInputValidation.Valid(densityDpi)
    } else {
        ProjectionDpiInputValidation.Invalid(ProjectionDpiInputError.OUT_OF_RANGE)
    }
}
