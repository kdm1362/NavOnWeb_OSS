/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.pebble.tecomheadunit.browser

import java.io.Closeable
import java.net.Socket

/** Owns accepted browser sockets so service shutdown can unblock every active client handler. */
internal class ActiveClientSocketRegistry : Closeable {
    private val lock = Any()
    private val sockets = linkedSetOf<Socket>()
    private var closed = false

    fun register(socket: Socket): Boolean {
        val accepted = synchronized(lock) {
            if (closed) false else sockets.add(socket)
        }
        if (!accepted) runCatching(socket::close)
        return accepted
    }

    fun release(socket: Socket) {
        synchronized(lock) { sockets.remove(socket) }
        runCatching(socket::close)
    }

    fun activeCount(): Int = synchronized(lock) { sockets.size }

    fun isClosed(): Boolean = synchronized(lock) { closed }

    override fun close() {
        val active = synchronized(lock) {
            if (closed) return
            closed = true
            sockets.toList().also { sockets.clear() }
        }
        active.forEach { socket -> runCatching(socket::close) }
    }
}
