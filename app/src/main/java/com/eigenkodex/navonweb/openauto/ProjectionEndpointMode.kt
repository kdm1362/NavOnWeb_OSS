/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.eigenkodex.navonweb.openauto

/** The Android Auto Head Unit Server is supported only on this phone. */
enum class ProjectionEndpointMode {
    THIS_PHONE,
}

data class ProjectionEndpoint(
    val mode: ProjectionEndpointMode,
    val host: String,
    val port: Int,
) {
    val isEnabled: Boolean
        get() = host.isNotBlank()
}

/** Keeps the runtime endpoint pinned to Android Auto's same-phone loopback server. */
object ProjectionEndpointResolver {
    const val THIS_PHONE_HOST = "127.0.0.1"
    const val ANDROID_AUTO_HEAD_UNIT_SERVER_PORT = 5277

    fun resolve(): ProjectionEndpoint = ProjectionEndpoint(
        mode = ProjectionEndpointMode.THIS_PHONE,
        host = THIS_PHONE_HOST,
        port = ANDROID_AUTO_HEAD_UNIT_SERVER_PORT,
    )
}
