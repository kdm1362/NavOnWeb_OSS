/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.pebble.tecomheadunit.automation

/**
 * Process-local generation gate for delayed user start requests.
 *
 * A real inactive automation event invalidates requests that were already waiting for a runtime
 * permission or a location callback. A manual start requested after that event receives the new
 * generation and remains allowed until a later real inactive transition.
 */
object ProjectionStartCancellationSignal {
    private val lock = Any()
    private var generation = 0L

    fun snapshot(): Long = synchronized(lock) { generation }

    fun cancelPendingStarts(): Long = synchronized(lock) {
        generation = if (generation == Long.MAX_VALUE) 0L else generation + 1L
        generation
    }

    fun isCurrent(candidate: Long): Boolean = synchronized(lock) { generation == candidate }

    /**
     * Serializes the final token check with cancellation. The action must publish its STARTING
     * state before returning so a cancellation that follows can observe and stop it.
     */
    fun runIfCurrent(candidate: Long, action: () -> Unit): Boolean = synchronized(lock) {
        if (generation != candidate) return@synchronized false
        action()
        true
    }
}
