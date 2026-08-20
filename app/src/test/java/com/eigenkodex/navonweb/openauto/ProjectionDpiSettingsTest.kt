/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.eigenkodex.navonweb.openauto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProjectionDpiSettingsTest {
    @Test
    fun `each profile keeps an independent DPI override`() {
        val store = MemoryStore()
        val manager = ProjectionDpiSettingsManager(store)

        val free = manager.setDensityDpi("free-800x480", 120)
        val fullHd = manager.setDensityDpi("premium-1080p", 200)
        val snapshot = manager.snapshot()

        assertTrue((free as ProjectionDpiSettingResult.Accepted).changed)
        assertTrue((fullHd as ProjectionDpiSettingResult.Accepted).changed)
        assertEquals(120, snapshot.densityDpi(ProjectionVideoProfile.FREE_800X480))
        assertEquals(220, snapshot.densityDpi(ProjectionVideoProfile.PREMIUM_720P))
        assertEquals(200, snapshot.densityDpi(ProjectionVideoProfile.PREMIUM_1080P))
    }

    @Test
    fun `DPI range accepts every integer and fails closed outside its bounds`() {
        val manager = ProjectionDpiSettingsManager(MemoryStore())

        listOf(71, 321).forEach { invalid ->
            assertEquals(
                ProjectionDpiSettingResult.Invalid,
                manager.setDensityDpi("free-800x480", invalid),
            )
        }
        listOf(72, 73, 78, 141, 319, 320).forEach { valid ->
            assertTrue(
                manager.setDensityDpi("free-800x480", valid) is
                    ProjectionDpiSettingResult.Accepted,
            )
        }
        assertEquals(
            ProjectionDpiSettingResult.UnknownProfile,
            manager.setDensityDpi("browser-supplied", 140),
        )
    }

    @Test
    fun `corrupt persisted DPI falls back to recommendation`() {
        val store = MemoryStore(
            values = mutableMapOf(ProjectionVideoProfile.PREMIUM_720P.profileId to 321),
        )

        assertEquals(
            ProjectionVideoProfile.PREMIUM_720P.dpi,
            ProjectionDpiSettingsManager(store)
                .densityDpi(ProjectionVideoProfile.PREMIUM_720P),
        )
    }

    @Test
    fun `reset clears override so future recommendations remain authoritative`() {
        val store = MemoryStore()
        val manager = ProjectionDpiSettingsManager(store)
        manager.setDensityDpi("premium-720p", 180)

        val result = manager.resetToRecommended("premium-720p")

        assertTrue((result as ProjectionDpiSettingResult.Accepted).changed)
        assertNull(store.values["premium-720p"])
        assertEquals(220, result.snapshot.densityDpi(ProjectionVideoProfile.PREMIUM_720P))
    }

    @Test
    fun `manually selecting the recommendation also clears the override`() {
        val store = MemoryStore()
        val manager = ProjectionDpiSettingsManager(store)
        manager.setDensityDpi("free-800x480", 180)

        val result = manager.setDensityDpi("free-800x480", 140)

        assertTrue((result as ProjectionDpiSettingResult.Accepted).changed)
        assertNull(store.values["free-800x480"])
    }

    @Test
    fun `write and reset persistence failures retain prior value`() {
        val store = MemoryStore()
        val manager = ProjectionDpiSettingsManager(store)
        store.failWrites = true
        val write = manager.setDensityDpi("free-800x480", 120)
        assertTrue(write is ProjectionDpiSettingResult.PersistenceFailed)
        assertEquals(140, manager.densityDpi(ProjectionVideoProfile.FREE_800X480))

        store.failWrites = false
        manager.setDensityDpi("free-800x480", 120)
        store.failClears = true
        val reset = manager.resetToRecommended("free-800x480")
        assertTrue(reset is ProjectionDpiSettingResult.PersistenceFailed)
        assertEquals(120, manager.densityDpi(ProjectionVideoProfile.FREE_800X480))
        assertFalse((reset as ProjectionDpiSettingResult.PersistenceFailed).snapshot
            .densityDpiByProfileId.isEmpty())
    }

    private class MemoryStore(
        val values: MutableMap<String, Int> = mutableMapOf(),
    ) : ProjectionDpiPreferenceStore {
        var failWrites = false
        var failClears = false

        override fun readDensityDpi(profileId: String): Int? = values[profileId]

        override fun writeDensityDpi(profileId: String, densityDpi: Int) {
            if (failWrites) error("write failed")
            values[profileId] = densityDpi
        }

        override fun clearDensityDpi(profileId: String) {
            if (failClears) error("clear failed")
            values.remove(profileId)
        }
    }
}
