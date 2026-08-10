/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.pebble.tecomheadunit.openauto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProjectionEndpointModeTest {
    @Test
    fun onlySamePhoneModeRemains() {
        assertEquals(listOf(ProjectionEndpointMode.THIS_PHONE), ProjectionEndpointMode.entries)
    }

    @Test
    fun runtimeEndpointIsAlwaysPinnedToAndroidAutoLoopback() {
        val endpoint = ProjectionEndpointResolver.resolve()

        assertEquals(ProjectionEndpointMode.THIS_PHONE, endpoint.mode)
        assertEquals("127.0.0.1", endpoint.host)
        assertEquals(5277, endpoint.port)
        assertTrue(endpoint.isEnabled)
    }
}
