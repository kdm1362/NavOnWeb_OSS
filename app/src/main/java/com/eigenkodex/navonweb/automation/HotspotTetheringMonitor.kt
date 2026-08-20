/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.eigenkodex.navonweb.automation

import android.content.Context
import android.net.TetheringInterface
import android.net.TetheringManager
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import java.util.concurrent.Executor

enum class HotspotMonitorStatus {
    STOPPED,
    WAITING_FOR_BASELINE,
    MONITORING,
    UNSUPPORTED,
    FAILED,
}

data class HotspotMonitorSnapshot(
    val status: HotspotMonitorStatus,
    /** Null until API 36 has delivered its immediate current-state callback. */
    val hotspotActive: Boolean?,
    /** Exception type only; platform messages can contain device-specific details. */
    val failureType: String? = null,
)

sealed interface HotspotMonitorStartResult {
    data class Started(val snapshot: HotspotMonitorSnapshot) : HotspotMonitorStartResult
    data class AlreadyStarted(val snapshot: HotspotMonitorSnapshot) : HotspotMonitorStartResult
    data class Unsupported(val snapshot: HotspotMonitorSnapshot) : HotspotMonitorStartResult
    data class Failed(val snapshot: HotspotMonitorSnapshot) : HotspotMonitorStartResult
}

sealed interface HotspotAutomationSourceStartResult {
    data class NotSelected(val state: AutomationControlState) : HotspotAutomationSourceStartResult

    data class Monitoring(
        val generation: Long,
        val monitorResult: HotspotMonitorStartResult,
    ) : HotspotAutomationSourceStartResult
}

/**
 * Event-driven Wi-Fi tethering detector for the currently selected HOTSPOT trigger mode.
 *
 * Android 16 immediately reports current tethering state after registration. That first event is
 * deliberately treated as a baseline and never starts or stops projection. Only a later real
 * transition reaches [onTransition]. Stopping this monitor also has no synthetic OFF side effect.
 * A generation token makes callbacks queued before stop/restart harmless.
 */
class HotspotTetheringMonitor internal constructor(
    private val backend: HotspotTetheringBackend,
    private val onStateObserved: (HotspotMonitorSnapshot) -> Unit = {},
    private val onTransition: (hotspotActive: Boolean) -> Unit,
) {
    constructor(
        context: Context,
        onStateObserved: (HotspotMonitorSnapshot) -> Unit = {},
        onTransition: (hotspotActive: Boolean) -> Unit,
    ) : this(
        backend = AndroidHotspotTetheringBackend(
            context = context.applicationContext,
            executor = ContextCompat.getMainExecutor(context.applicationContext),
        ),
        onStateObserved = onStateObserved,
        onTransition = onTransition,
    )

    private val lock = Any()
    private var generation = 0L
    private var running = false
    private var snapshot = HotspotMonitorSnapshot(
        status = HotspotMonitorStatus.STOPPED,
        hotspotActive = null,
    )

    fun snapshot(): HotspotMonitorSnapshot = synchronized(lock) { snapshot }

    fun start(): HotspotMonitorStartResult {
        val callbackGeneration: Long
        synchronized(lock) {
            if (running) return HotspotMonitorStartResult.AlreadyStarted(snapshot)
            if (!backend.isSupported) {
                snapshot = HotspotMonitorSnapshot(
                    status = HotspotMonitorStatus.UNSUPPORTED,
                    hotspotActive = null,
                )
                return HotspotMonitorStartResult.Unsupported(snapshot)
            }
            generation = nextGeneration(generation)
            callbackGeneration = generation
            running = true
            snapshot = HotspotMonitorSnapshot(
                status = HotspotMonitorStatus.WAITING_FOR_BASELINE,
                hotspotActive = null,
            )
        }

        return runCatching {
            backend.register { active -> acceptState(callbackGeneration, active) }
        }.fold(
            onSuccess = { HotspotMonitorStartResult.Started(snapshot()) },
            onFailure = { failure ->
                val failedSnapshot = synchronized(lock) {
                    if (running && generation == callbackGeneration) {
                        running = false
                        generation = nextGeneration(generation)
                        snapshot = HotspotMonitorSnapshot(
                            status = HotspotMonitorStatus.FAILED,
                            hotspotActive = null,
                            failureType = failure.safeTypeName(),
                        )
                    }
                    snapshot
                }
                HotspotMonitorStartResult.Failed(failedSnapshot)
            },
        )
    }

    /** Invalidates queued callbacks before unregistering and never synthesizes an OFF event. */
    fun stop() {
        val shouldUnregister = synchronized(lock) {
            if (!running) {
                snapshot = HotspotMonitorSnapshot(
                    status = HotspotMonitorStatus.STOPPED,
                    hotspotActive = null,
                )
                false
            } else {
                running = false
                generation = nextGeneration(generation)
                snapshot = HotspotMonitorSnapshot(
                    status = HotspotMonitorStatus.STOPPED,
                    hotspotActive = null,
                )
                true
            }
        }
        if (!shouldUnregister) return

        runCatching(backend::unregister).onFailure { failure ->
            synchronized(lock) {
                snapshot = snapshot.copy(failureType = failure.safeTypeName())
            }
        }
    }

    private fun acceptState(callbackGeneration: Long, active: Boolean) {
        var transition: Boolean? = null
        val observed = synchronized(lock) {
            if (!running || generation != callbackGeneration) return
            val previous = snapshot.hotspotActive
            snapshot = HotspotMonitorSnapshot(
                status = HotspotMonitorStatus.MONITORING,
                hotspotActive = active,
            )
            transition = when {
                previous == null -> null // API 36's immediate state is baseline only.
                previous == active -> null
                else -> active
            }
            snapshot
        }

        runCatching { onStateObserved(observed) }.onFailure { failure ->
            recordCallbackFailure(callbackGeneration, failure)
        }

        val dispatchedTransition = transition ?: return

        runCatching { onTransition(dispatchedTransition) }.onFailure { failure ->
            recordCallbackFailure(callbackGeneration, failure)
        }
    }

    private fun recordCallbackFailure(callbackGeneration: Long, failure: Throwable) {
        synchronized(lock) {
            if (running && generation == callbackGeneration) {
                snapshot = snapshot.copy(
                    status = HotspotMonitorStatus.FAILED,
                    failureType = failure.safeTypeName(),
                )
            }
        }
    }

    private companion object {
        fun nextGeneration(current: Long): Long = if (current == Long.MAX_VALUE) 0L else current + 1L

        fun Throwable.safeTypeName(): String =
            this::class.java.simpleName.takeIf(String::isNotBlank) ?: "RuntimeException"
    }
}

/**
 * Binds the hotspot event source to the single, mutually exclusive automation controller mode.
 * The selected mode generation is captured before callback registration; the controller and this
 * source both reject callbacks left over from an earlier registration.
 */
class HotspotAutomationSource internal constructor(
    private val controller: AutomationServiceController,
    private val monitorFactory: ((Boolean) -> Unit) -> HotspotTetheringMonitor,
    private val onDispatch: (AutomationDispatchResult) -> Unit = {},
) {
    constructor(
        context: Context,
        controller: AutomationServiceController = AutomationServiceControllerProvider.get(context),
        onDispatch: (AutomationDispatchResult) -> Unit = {},
        onMonitorStateChanged: (HotspotMonitorSnapshot) -> Unit = {},
    ) : this(
        controller = controller,
        monitorFactory = { transition ->
            HotspotTetheringMonitor(
                context = context,
                onStateObserved = onMonitorStateChanged,
                onTransition = transition,
            )
        },
        onDispatch = onDispatch,
    )

    private data class Registration(
        val generation: Long,
        val monitor: HotspotTetheringMonitor,
    )

    private val lock = Any()
    private var registration: Registration? = null

    fun startIfSelected(): HotspotAutomationSourceStartResult {
        val selected = controller.snapshot()
        if (selected.mode != AutomationTriggerMode.HOTSPOT) {
            stop()
            return HotspotAutomationSourceStartResult.NotSelected(selected)
        }

        val activeRegistration = synchronized(lock) {
            registration?.takeIf { it.generation == selected.generation } ?: run {
                registration?.monitor?.stop()
                Registration(
                    generation = selected.generation,
                    monitor = monitorFactory { active ->
                        onDispatch(
                            controller.onTriggerStateChanged(
                                source = AutomationTriggerMode.HOTSPOT,
                                generation = selected.generation,
                                active = active,
                            ),
                        )
                    },
                ).also { registration = it }
            }
        }
        val result = activeRegistration.monitor.start()

        // A concurrent mode change after the first snapshot must not leave needless callbacks up.
        val current = controller.snapshot()
        if (
            current.mode != AutomationTriggerMode.HOTSPOT ||
            current.generation != activeRegistration.generation
        ) {
            synchronized(lock) {
                if (registration === activeRegistration) registration = null
            }
            activeRegistration.monitor.stop()
            return HotspotAutomationSourceStartResult.NotSelected(current)
        }
        return HotspotAutomationSourceStartResult.Monitoring(
            generation = activeRegistration.generation,
            monitorResult = result,
        )
    }

    /** Mode changes call this to unregister without manufacturing a hotspot-OFF transition. */
    fun stop() {
        val stopped = synchronized(lock) {
            registration.also { registration = null }
        }
        stopped?.monitor?.stop()
    }

    fun snapshot(): HotspotMonitorSnapshot? = synchronized(lock) {
        registration?.monitor?.snapshot()
    }
}

internal interface HotspotTetheringBackend {
    val isSupported: Boolean
    fun register(listener: (hotspotActive: Boolean) -> Unit)
    fun unregister()
}

/** Public API 36 adapter. No hidden APIs, polling, location, or privileged permissions. */
private class AndroidHotspotTetheringBackend(
    context: Context,
    private val executor: Executor,
) : HotspotTetheringBackend {
    private val applicationContext = context.applicationContext
    private var callback: TetheringManager.TetheringEventCallback? = null

    override val isSupported: Boolean
        get() = Build.VERSION.SDK_INT >= 36 && tetheringManagerOrNull() != null

    override fun register(listener: (hotspotActive: Boolean) -> Unit) {
        if (Build.VERSION.SDK_INT >= 36) {
            registerApi36(listener)
        } else {
            throw UnsupportedOperationException("public tethering callbacks require API 36")
        }
    }

    override fun unregister() {
        if (Build.VERSION.SDK_INT < 36) return
        unregisterApi36()
    }

    @RequiresApi(36)
    private fun registerApi36(listener: (hotspotActive: Boolean) -> Unit) {
        check(callback == null) { "tethering callback is already registered" }
        val manager = tetheringManagerOrNull()
            ?: throw UnsupportedOperationException("tethering service unavailable")
        val registered = object : TetheringManager.TetheringEventCallback {
            override fun onTetheredInterfacesChanged(interfaces: Set<TetheringInterface>) {
                listener(interfaces.any { it.type == TetheringManager.TETHERING_WIFI })
            }
        }
        callback = registered
        try {
            manager.registerTetheringEventCallback(executor, registered)
        } catch (failure: RuntimeException) {
            callback = null
            throw failure
        }
    }

    @RequiresApi(36)
    private fun unregisterApi36() {
        val registered = callback ?: return
        callback = null
        tetheringManagerOrNull()?.unregisterTetheringEventCallback(registered)
    }

    @RequiresApi(36)
    private fun tetheringManagerOrNull(): TetheringManager? =
        applicationContext.getSystemService(TetheringManager::class.java)
}
