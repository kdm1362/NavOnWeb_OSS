/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.eigenkodex.navonweb.automation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HotspotTetheringMonitorTest {
    @Test
    fun `initial callback establishes baseline without side effect`() {
        val backend = FakeBackend()
        val transitions = mutableListOf<Boolean>()
        val observations = mutableListOf<HotspotMonitorSnapshot>()
        val monitor = HotspotTetheringMonitor(
            backend = backend,
            onStateObserved = observations::add,
            onTransition = transitions::add,
        )

        val result = monitor.start()
        backend.emit(true)

        assertTrue(result is HotspotMonitorStartResult.Started)
        assertTrue(transitions.isEmpty())
        assertEquals(HotspotMonitorStatus.MONITORING, monitor.snapshot().status)
        assertEquals(true, monitor.snapshot().hotspotActive)
        assertEquals(listOf(true), observations.map(HotspotMonitorSnapshot::hotspotActive))
    }

    @Test
    fun `only real changes after baseline are dispatched`() {
        val backend = FakeBackend()
        val transitions = mutableListOf<Boolean>()
        val monitor = HotspotTetheringMonitor(
            backend = backend,
            onTransition = transitions::add,
        )
        monitor.start()

        backend.emit(false)
        backend.emit(false)
        backend.emit(true)
        backend.emit(true)
        backend.emit(false)

        assertEquals(listOf(true, false), transitions)
    }

    @Test
    fun `stop has no synthetic off and queued callback is stale`() {
        val backend = FakeBackend()
        val transitions = mutableListOf<Boolean>()
        val monitor = HotspotTetheringMonitor(
            backend = backend,
            onTransition = transitions::add,
        )
        monitor.start()
        backend.emit(true)
        val queued = backend.listener

        monitor.stop()
        queued?.invoke(false)

        assertTrue(transitions.isEmpty())
        assertEquals(1, backend.unregisterCount)
        assertEquals(HotspotMonitorStatus.STOPPED, monitor.snapshot().status)
        assertNull(monitor.snapshot().hotspotActive)
    }

    @Test
    fun `restart receives a new baseline and ignores old generation`() {
        val backend = FakeBackend()
        val transitions = mutableListOf<Boolean>()
        val monitor = HotspotTetheringMonitor(
            backend = backend,
            onTransition = transitions::add,
        )
        monitor.start()
        backend.emit(false)
        val oldListener = backend.listener
        monitor.stop()

        monitor.start()
        oldListener?.invoke(true)
        backend.emit(true)
        backend.emit(false)

        assertEquals(listOf(false), transitions)
    }

    @Test
    fun `unsupported and registration failure fail closed`() {
        val unsupported = HotspotTetheringMonitor(
            FakeBackend(supported = false),
            onTransition = { error("must not dispatch") },
        )
        assertTrue(unsupported.start() is HotspotMonitorStartResult.Unsupported)
        assertEquals(HotspotMonitorStatus.UNSUPPORTED, unsupported.snapshot().status)

        val failure = HotspotTetheringMonitor(
            FakeBackend(registerFailure = SecurityException("denied")),
            onTransition = { error("must not dispatch") },
        )
        assertTrue(failure.start() is HotspotMonitorStartResult.Failed)
        assertEquals(HotspotMonitorStatus.FAILED, failure.snapshot().status)
        assertEquals("SecurityException", failure.snapshot().failureType)
        assertNull(failure.snapshot().hotspotActive)
    }

    @Test
    fun `transition handler failure is contained without leaking message`() {
        val backend = FakeBackend()
        val monitor = HotspotTetheringMonitor(
            backend = backend,
            onTransition = { throw IllegalStateException("device-specific secret") },
        )
        monitor.start()
        backend.emit(false)

        backend.emit(true)

        assertEquals(HotspotMonitorStatus.FAILED, monitor.snapshot().status)
        assertEquals("IllegalStateException", monitor.snapshot().failureType)
    }

    @Test
    fun `selected hotspot mode dispatches post-baseline transitions with captured generation`() {
        val controller = FakeController(
            AutomationControlState(
                mode = AutomationTriggerMode.HOTSPOT,
                generation = 7L,
            ),
        )
        val backends = mutableListOf<FakeBackend>()
        val source = HotspotAutomationSource(
            controller = controller,
            monitorFactory = { transition ->
                FakeBackend().also(backends::add).let { backend ->
                    HotspotTetheringMonitor(
                        backend = backend,
                        onTransition = transition,
                    )
                }
            },
        )

        val start = source.startIfSelected()
        backends.single().emit(false)
        backends.single().emit(true)

        assertTrue(start is HotspotAutomationSourceStartResult.Monitoring)
        assertEquals(
            listOf(Triple(AutomationTriggerMode.HOTSPOT, 7L, true)),
            controller.signals,
        )
    }

    @Test
    fun `unselected mode does not register and mode change stops without synthetic signal`() {
        val controller = FakeController(
            AutomationControlState(
                mode = AutomationTriggerMode.HOTSPOT,
                generation = 2L,
            ),
        )
        val backends = mutableListOf<FakeBackend>()
        val source = HotspotAutomationSource(
            controller = controller,
            monitorFactory = { transition ->
                FakeBackend().also(backends::add).let { backend ->
                    HotspotTetheringMonitor(
                        backend = backend,
                        onTransition = transition,
                    )
                }
            },
        )
        source.startIfSelected()
        backends.single().emit(true)
        controller.state = AutomationControlState(
            mode = AutomationTriggerMode.BLUETOOTH,
            generation = 3L,
        )

        val result = source.startIfSelected()

        assertTrue(result is HotspotAutomationSourceStartResult.NotSelected)
        assertEquals(1, backends.single().unregisterCount)
        assertTrue(controller.signals.isEmpty())
        assertNull(source.snapshot())
    }

    @Test
    fun `new hotspot generation replaces monitor and old queued callback stays inert`() {
        val controller = FakeController(
            AutomationControlState(
                mode = AutomationTriggerMode.HOTSPOT,
                generation = 10L,
            ),
        )
        val backends = mutableListOf<FakeBackend>()
        val source = HotspotAutomationSource(
            controller = controller,
            monitorFactory = { transition ->
                FakeBackend().also(backends::add).let { backend ->
                    HotspotTetheringMonitor(
                        backend = backend,
                        onTransition = transition,
                    )
                }
            },
        )
        source.startIfSelected()
        backends.first().emit(false)
        val oldListener = backends.first().listener
        controller.state = AutomationControlState(
            mode = AutomationTriggerMode.HOTSPOT,
            generation = 11L,
        )

        source.startIfSelected()
        oldListener?.invoke(true)
        backends.last().emit(true)
        backends.last().emit(false)

        assertEquals(2, backends.size)
        assertEquals(
            listOf(Triple(AutomationTriggerMode.HOTSPOT, 11L, false)),
            controller.signals,
        )
    }

    private class FakeBackend(
        private val supported: Boolean = true,
        private val registerFailure: RuntimeException? = null,
    ) : HotspotTetheringBackend {
        override val isSupported: Boolean
            get() = supported

        var listener: ((Boolean) -> Unit)? = null
            private set
        var unregisterCount = 0
            private set

        override fun register(listener: (Boolean) -> Unit) {
            registerFailure?.let { throw it }
            this.listener = listener
        }

        override fun unregister() {
            unregisterCount += 1
            listener = null
        }

        fun emit(active: Boolean) {
            listener?.invoke(active)
        }
    }

    private class FakeController(
        var state: AutomationControlState,
    ) : AutomationServiceController {
        val signals = mutableListOf<Triple<AutomationTriggerMode, Long, Boolean>>()

        override fun snapshot(): AutomationControlState = state

        override fun selectMode(mode: AutomationTriggerMode): AutomationDispatchResult =
            AutomationDispatchResult.NoChange(state)

        override fun disableModeIfSelected(source: AutomationTriggerMode): AutomationDispatchResult =
            AutomationDispatchResult.NoChange(state)

        override fun onTriggerStateChanged(
            source: AutomationTriggerMode,
            generation: Long,
            active: Boolean,
        ): AutomationDispatchResult {
            signals += Triple(source, generation, active)
            return AutomationDispatchResult.NoChange(state)
        }
    }
}
