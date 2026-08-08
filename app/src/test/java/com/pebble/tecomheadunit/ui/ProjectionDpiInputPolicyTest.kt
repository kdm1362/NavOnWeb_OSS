/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.pebble.tecomheadunit.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class ProjectionDpiInputPolicyTest {
    @Test
    fun `single digit draft is not rewritten while the user continues typing`() {
        val draft = "7"

        assertEquals(
            ProjectionDpiInputValidation.Invalid(ProjectionDpiInputError.OUT_OF_RANGE),
            validateProjectionDpiDraft(draft),
        )
        assertEquals("7", draft)
        assertEquals(
            ProjectionDpiInputValidation.Valid(78),
            validateProjectionDpiDraft("${draft}8"),
        )
    }

    @Test
    fun `all integer DPI values inside the inclusive range are accepted`() {
        listOf(72, 73, 78, 141, 219, 220, 319, 320).forEach { densityDpi ->
            assertEquals(
                ProjectionDpiInputValidation.Valid(densityDpi),
                validateProjectionDpiDraft(densityDpi.toString()),
            )
        }
    }

    @Test
    fun `blank non integer and out of range commits explain the failure`() {
        assertEquals(
            ProjectionDpiInputValidation.Invalid(ProjectionDpiInputError.REQUIRED),
            validateProjectionDpiDraft("  "),
        )
        assertEquals(
            ProjectionDpiInputValidation.Invalid(ProjectionDpiInputError.NOT_AN_INTEGER),
            validateProjectionDpiDraft("78.5"),
        )
        listOf("71", "321", "999999999999999999999").forEach { draft ->
            val expectedError = if (draft.length > 10) {
                ProjectionDpiInputError.NOT_AN_INTEGER
            } else {
                ProjectionDpiInputError.OUT_OF_RANGE
            }
            assertEquals(
                ProjectionDpiInputValidation.Invalid(expectedError),
                validateProjectionDpiDraft(draft),
            )
        }
    }
}
