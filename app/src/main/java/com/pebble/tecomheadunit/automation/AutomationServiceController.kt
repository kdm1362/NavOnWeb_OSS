/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.pebble.tecomheadunit.automation

/** Exactly one event source may control automatic service start/stop at a time. */
enum class AutomationTriggerMode {
    NONE,
    BLUETOOTH,
    HOTSPOT,
}

/**
 * [generation] changes whenever [mode] changes. Long-lived callbacks must retain the generation
 * with which they were registered; this prevents a stale Bluetooth/hotspot event from controlling
 * a newly selected mode.
 *
 * A null [lastTriggerActive] means that no event has been accepted for this mode yet. Merely
 * enabling a mode or recreating the process must therefore never be interpreted as a disconnect.
 */
data class AutomationControlState(
    val mode: AutomationTriggerMode = AutomationTriggerMode.NONE,
    val generation: Long = 0L,
    val lastTriggerActive: Boolean? = null,
) {
    init {
        require(generation >= 0L)
        require(mode != AutomationTriggerMode.NONE || lastTriggerActive == null)
    }
}

interface AutomationControlStateStore {
    fun load(): AutomationControlState

    /** Returns false without partially updating the externally visible state. */
    fun save(state: AutomationControlState): Boolean
}

sealed interface AutomationRuntimeResult {
    data object Success : AutomationRuntimeResult

    data class Deferred(val reason: AutomationStartDeferredReason) : AutomationRuntimeResult

    data class Failed(val reason: AutomationRuntimeFailureReason) : AutomationRuntimeResult
}

enum class AutomationStartDeferredReason {
    BACKGROUND_FOREGROUND_SERVICE_START_RESTRICTED,
}

enum class AutomationRuntimeFailureReason {
    ENTITLEMENT_REQUIRED,
    SECURITY_RESTRICTION,
    PLATFORM_REJECTED,
}

interface AutomationServiceRuntime {
    fun isRunning(): Boolean

    fun start(): AutomationRuntimeResult

    /** Starts with an ownership token so a later explicit disable cannot stop a manual session. */
    fun startForAutomation(
        source: AutomationTriggerMode,
        generation: Long,
    ): AutomationRuntimeResult = start()

    fun stop(): AutomationRuntimeResult

    /** Cancels delayed user starts that have not reached the service yet. */
    fun cancelPendingStarts() = Unit
}

/** Optional user-visible recovery path, normally a notification that opens the app. */
fun interface AutomationStartFallback {
    fun show(reason: AutomationStartDeferredReason): Boolean
}

sealed interface AutomationDispatchResult {
    val state: AutomationControlState

    data class ModeChanged(override val state: AutomationControlState) : AutomationDispatchResult

    data class ModeDisabled(
        override val state: AutomationControlState,
        val disabledSource: AutomationTriggerMode,
        val disabledGeneration: Long,
    ) : AutomationDispatchResult

    data class Started(override val state: AutomationControlState) : AutomationDispatchResult

    data class Stopped(override val state: AutomationControlState) : AutomationDispatchResult

    data class NoChange(override val state: AutomationControlState) : AutomationDispatchResult

    data class IgnoredStaleSignal(override val state: AutomationControlState) : AutomationDispatchResult

    data class StartDeferred(
        override val state: AutomationControlState,
        val reason: AutomationStartDeferredReason,
        val fallbackShown: Boolean,
    ) : AutomationDispatchResult

    data class Failed(
        override val state: AutomationControlState,
        val operation: AutomationOperation,
        val reason: AutomationFailureReason,
    ) : AutomationDispatchResult
}

enum class AutomationOperation {
    LOAD_STATE,
    SAVE_STATE,
    QUERY_SERVICE,
    START_SERVICE,
    STOP_SERVICE,
}

enum class AutomationFailureReason {
    PERSISTENCE_FAILED,
    ENTITLEMENT_REQUIRED,
    SECURITY_RESTRICTION,
    PLATFORM_REJECTED,
}

interface AutomationServiceController {
    fun snapshot(): AutomationControlState

    /** Selects one source and invalidates callbacks registered for the previous generation. */
    fun selectMode(mode: AutomationTriggerMode): AutomationDispatchResult

    /** Atomically disables [source] only if that source is still selected. */
    fun disableModeIfSelected(source: AutomationTriggerMode): AutomationDispatchResult

    /**
     * Invalidates the event baseline after changing configuration inside the currently selected
     * mode (for example, choosing a different Bluetooth device). No service action is dispatched.
     */
    fun resetSelectedModeSignal(mode: AutomationTriggerMode): AutomationDispatchResult = selectMode(mode)

    /**
     * Handles an actual source event. Inactive events stop even a manually started session.
     * No dispatch is performed merely from a stored/current inactive value.
     */
    fun onTriggerStateChanged(
        source: AutomationTriggerMode,
        generation: Long,
        active: Boolean,
    ): AutomationDispatchResult
}

/** Thread-safe event-driven controller shared by Bluetooth and hotspot sources. */
class CoordinatingAutomationServiceController(
    private val stateStore: AutomationControlStateStore,
    private val runtime: AutomationServiceRuntime,
    private val startFallback: AutomationStartFallback = AutomationStartFallback { false },
) : AutomationServiceController {
    private val lock = Any()

    override fun snapshot(): AutomationControlState = synchronized(lock) { safeLoad() }

    override fun selectMode(mode: AutomationTriggerMode): AutomationDispatchResult = synchronized(lock) {
        val current = safeLoad()
        if (current.mode == mode) return@synchronized AutomationDispatchResult.NoChange(current)
        val next = AutomationControlState(
            mode = mode,
            generation = if (current.generation == Long.MAX_VALUE) 1L else current.generation + 1L,
            lastTriggerActive = null,
        )
        if (!stateStore.save(next)) {
            return@synchronized AutomationDispatchResult.Failed(
                state = current,
                operation = AutomationOperation.SAVE_STATE,
                reason = AutomationFailureReason.PERSISTENCE_FAILED,
            )
        }
        AutomationDispatchResult.ModeChanged(next)
    }

    override fun disableModeIfSelected(source: AutomationTriggerMode): AutomationDispatchResult =
        synchronized(lock) {
            val current = safeLoad()
            if (source == AutomationTriggerMode.NONE || current.mode != source) {
                return@synchronized AutomationDispatchResult.IgnoredStaleSignal(current)
            }
            val next = AutomationControlState(
                mode = AutomationTriggerMode.NONE,
                generation = if (current.generation == Long.MAX_VALUE) 1L else current.generation + 1L,
                lastTriggerActive = null,
            )
            if (!stateStore.save(next)) {
                return@synchronized AutomationDispatchResult.Failed(
                    state = current,
                    operation = AutomationOperation.SAVE_STATE,
                    reason = AutomationFailureReason.PERSISTENCE_FAILED,
                )
            }
            AutomationDispatchResult.ModeDisabled(
                state = next,
                disabledSource = source,
                disabledGeneration = current.generation,
            )
        }

    override fun resetSelectedModeSignal(mode: AutomationTriggerMode): AutomationDispatchResult = synchronized(lock) {
        val current = safeLoad()
        if (current.mode != mode || mode == AutomationTriggerMode.NONE) {
            return@synchronized AutomationDispatchResult.IgnoredStaleSignal(current)
        }
        val next = current.copy(
            generation = if (current.generation == Long.MAX_VALUE) 1L else current.generation + 1L,
            lastTriggerActive = null,
        )
        if (!stateStore.save(next)) {
            return@synchronized AutomationDispatchResult.Failed(
                state = current,
                operation = AutomationOperation.SAVE_STATE,
                reason = AutomationFailureReason.PERSISTENCE_FAILED,
            )
        }
        AutomationDispatchResult.ModeChanged(next)
    }

    override fun onTriggerStateChanged(
        source: AutomationTriggerMode,
        generation: Long,
        active: Boolean,
    ): AutomationDispatchResult = synchronized(lock) {
        val current = safeLoad()
        if (
            source == AutomationTriggerMode.NONE ||
            source != current.mode ||
            generation != current.generation
        ) {
            return@synchronized AutomationDispatchResult.IgnoredStaleSignal(current)
        }
        if (current.lastTriggerActive == active) {
            return@synchronized AutomationDispatchResult.NoChange(current)
        }

        val eventState = current.copy(lastTriggerActive = active)
        if (!active) {
            runCatching(runtime::cancelPendingStarts).getOrElse { failure ->
                return@synchronized AutomationDispatchResult.Failed(
                    state = current,
                    operation = AutomationOperation.STOP_SERVICE,
                    reason = failure.toFailureReason(),
                )
            }
        }

        val eventPersisted = stateStore.save(eventState)
        if (active && !eventPersisted) {
            return@synchronized persistenceFailure(current)
        }

        val running = runCatching(runtime::isRunning).getOrElse {
            return@synchronized AutomationDispatchResult.Failed(
                state = failureState(current, eventState, eventPersisted),
                operation = AutomationOperation.QUERY_SERVICE,
                reason = it.toFailureReason(),
            )
        }
        if (active && running || !active && !running) {
            if (!eventPersisted) return@synchronized persistenceFailure(current)
            return@synchronized AutomationDispatchResult.NoChange(eventState)
        }

        when (
            val result = if (active) {
                runtime.startForAutomation(source, generation)
            } else {
                runtime.stop()
            }
        ) {
            AutomationRuntimeResult.Success -> {
                if (!eventPersisted) {
                    persistenceFailure(current)
                } else if (active) {
                    AutomationDispatchResult.Started(eventState)
                } else {
                    AutomationDispatchResult.Stopped(eventState)
                }
            }
            is AutomationRuntimeResult.Deferred -> if (active) {
                AutomationDispatchResult.StartDeferred(
                    state = failureState(current, eventState, eventPersisted),
                    reason = result.reason,
                    fallbackShown = startFallback.show(result.reason),
                )
            } else {
                AutomationDispatchResult.Failed(
                    state = failureState(current, eventState, eventPersisted),
                    operation = AutomationOperation.STOP_SERVICE,
                    reason = AutomationFailureReason.PLATFORM_REJECTED,
                )
            }
            is AutomationRuntimeResult.Failed -> AutomationDispatchResult.Failed(
                state = failureState(current, eventState, eventPersisted),
                operation = if (active) AutomationOperation.START_SERVICE else AutomationOperation.STOP_SERVICE,
                reason = when (result.reason) {
                    AutomationRuntimeFailureReason.ENTITLEMENT_REQUIRED ->
                        AutomationFailureReason.ENTITLEMENT_REQUIRED
                    AutomationRuntimeFailureReason.SECURITY_RESTRICTION ->
                        AutomationFailureReason.SECURITY_RESTRICTION
                    AutomationRuntimeFailureReason.PLATFORM_REJECTED ->
                        AutomationFailureReason.PLATFORM_REJECTED
                },
            )
        }
    }

    private fun persistenceFailure(state: AutomationControlState): AutomationDispatchResult.Failed =
        AutomationDispatchResult.Failed(
            state = state,
            operation = AutomationOperation.SAVE_STATE,
            reason = AutomationFailureReason.PERSISTENCE_FAILED,
        )

    private fun failureState(
        previous: AutomationControlState,
        accepted: AutomationControlState,
        acceptedWasPersisted: Boolean,
    ): AutomationControlState = if (acceptedWasPersisted) {
        rollbackAcceptedEvent(previous, accepted)
    } else {
        previous
    }

    private fun rollbackAcceptedEvent(
        previous: AutomationControlState,
        accepted: AutomationControlState,
    ): AutomationControlState = if (stateStore.save(previous)) previous else accepted

    private fun safeLoad(): AutomationControlState = runCatching(stateStore::load)
        .getOrDefault(AutomationControlState())

    private fun Throwable.toFailureReason(): AutomationFailureReason =
        if (this is SecurityException) {
            AutomationFailureReason.SECURITY_RESTRICTION
        } else {
            AutomationFailureReason.PLATFORM_REJECTED
        }
}
