/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.pebble.tecomheadunit.browser

import java.net.Inet4Address
import java.net.NetworkInterface

object LocalAddressResolver {
    private const val LOOPBACK_IPV4 = "127.0.0.1"

    fun resolve(): String? = resolveAll().firstOrNull()

    /** All active private IPv4 addresses, ordered by likely browser-facing interfaces. */
    fun resolveAll(): List<String> {
        val candidates = mutableListOf<Pair<String, Inet4Address>>()
        val interfaces = NetworkInterface.getNetworkInterfaces() ?: return emptyList()

        while (interfaces.hasMoreElements()) {
            val network = interfaces.nextElement()
            if (!network.isUp || network.isLoopback) continue

            val addresses = network.inetAddresses
            while (addresses.hasMoreElements()) {
                val address = addresses.nextElement()
                if (address is Inet4Address && address.isSiteLocalAddress) {
                    candidates += network.name to address
                }
            }
        }

        return candidates
            .sortedBy { (name, _) ->
                when {
                    name.contains("ap", ignoreCase = true) -> 0
                    name.contains("wlan", ignoreCase = true) -> 1
                    name.contains("wifi", ignoreCase = true) -> 2
                    else -> 3
                }
            }
            .mapNotNull { (_, address) -> address.hostAddress }
            .distinct()
    }

    /**
     * The browser server binds to all interfaces, so a temporarily missing LAN address must not
     * tear the service down. Loopback keeps on-device access and ADB port forwarding available;
     * the next normal service restart advertises a LAN address once one exists again.
     */
    fun resolveOrLoopback(): String = resolveOrLoopback(::resolve)

    internal fun resolveOrLoopback(resolver: () -> String?): String =
        advertisedAddress(runCatching(resolver).getOrNull())

    internal fun advertisedAddress(lanAddress: String?): String =
        lanAddress?.takeIf(String::isNotBlank) ?: LOOPBACK_IPV4
}
