/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.pebble.tecomheadunit.browser

import org.junit.Assert.assertEquals
import org.junit.Test

class LocalAddressResolverTest {
    @Test
    fun `LAN address remains preferred when available`() {
        assertEquals(
            "10.0.0.231",
            LocalAddressResolver.advertisedAddress("10.0.0.231"),
        )
    }

    @Test
    fun `loopback keeps the server usable when LAN is temporarily unavailable`() {
        assertEquals("127.0.0.1", LocalAddressResolver.advertisedAddress(null))
        assertEquals("127.0.0.1", LocalAddressResolver.advertisedAddress(""))
    }

    @Test
    fun `network enumeration failure cannot tear down the browser server`() {
        assertEquals(
            "127.0.0.1",
            LocalAddressResolver.resolveOrLoopback { error("network changed during lookup") },
        )
    }
}
