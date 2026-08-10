/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.pebble.tecomheadunit.automation

/** Identifies the exact automation event generation that created a projection session. */
data class AutomationProjectionOwner(
    val source: AutomationTriggerMode,
    val generation: Long,
) {
    init {
        require(source != AutomationTriggerMode.NONE)
        require(generation >= 0L)
    }
}

/**
 * Process-local ownership used only to distinguish an automation-started session from a manual
 * one. ProjectionService and the monitor service share a process; when that process dies, the
 * projection session dies with it, so this ownership must not be persisted.
 */
internal class ProjectionAutomationOwnership {
    private val lock = Any()
    private var owner: AutomationProjectionOwner? = null

    fun claim(owner: AutomationProjectionOwner) = synchronized(lock) {
        this.owner = owner
    }

    /** A manual START always supersedes automation ownership, even if a session already exists. */
    fun clearForManualStart() = clear()

    fun matches(expected: AutomationProjectionOwner): Boolean = synchronized(lock) {
        owner == expected
    }

    fun clear(expected: AutomationProjectionOwner? = null): Boolean = synchronized(lock) {
        if (expected != null && owner != expected) return@synchronized false
        val changed = owner != null
        owner = null
        changed
    }
}

internal object ProjectionAutomationSessionOwnership {
    private val ownership = ProjectionAutomationOwnership()

    fun claim(owner: AutomationProjectionOwner) = ownership.claim(owner)

    fun clearForManualStart() = ownership.clearForManualStart()

    fun matches(expected: AutomationProjectionOwner): Boolean = ownership.matches(expected)

    fun clear(expected: AutomationProjectionOwner? = null): Boolean = ownership.clear(expected)
}

internal fun isAutomationProjectionStartAuthorized(
    owner: AutomationProjectionOwner,
    state: AutomationControlState,
): Boolean =
    state.mode == owner.source &&
        state.generation == owner.generation &&
        state.lastTriggerActive == true
