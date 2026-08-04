/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.pebble.tecomheadunit.automation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AutomationServiceControllerTest {
    @Test
    fun `mode selection is exclusive and never dispatches service action`() {
        val store = FakeStateStore()
        val runtime = FakeRuntime(running = true)
        val controller = controller(store, runtime)

        val bluetooth = controller.selectMode(AutomationTriggerMode.BLUETOOTH)
        val hotspot = controller.selectMode(AutomationTriggerMode.HOTSPOT)

        assertTrue(bluetooth is AutomationDispatchResult.ModeChanged)
        assertTrue(hotspot is AutomationDispatchResult.ModeChanged)
        assertEquals(AutomationTriggerMode.HOTSPOT, hotspot.state.mode)
        assertEquals(2L, hotspot.state.generation)
        assertEquals(null, hotspot.state.lastTriggerActive)
        assertEquals(0, runtime.starts)
        assertEquals(0, runtime.stops)
    }

    @Test
    fun `stale callback from previous mode is ignored`() {
        val store = FakeStateStore()
        val runtime = FakeRuntime()
        val controller = controller(store, runtime)
        val bluetoothGeneration = controller
            .selectMode(AutomationTriggerMode.BLUETOOTH)
            .state.generation
        controller.selectMode(AutomationTriggerMode.HOTSPOT)

        val result = controller.onTriggerStateChanged(
            AutomationTriggerMode.BLUETOOTH,
            bluetoothGeneration,
            active = true,
        )

        assertTrue(result is AutomationDispatchResult.IgnoredStaleSignal)
        assertEquals(0, runtime.starts)
        assertEquals(null, result.state.lastTriggerActive)
    }

    @Test
    fun `actual connected event starts stopped service`() {
        val store = FakeStateStore()
        val runtime = FakeRuntime()
        val controller = controller(store, runtime)
        val generation = controller.selectMode(AutomationTriggerMode.BLUETOOTH).state.generation

        val result = controller.onTriggerStateChanged(
            AutomationTriggerMode.BLUETOOTH,
            generation,
            active = true,
        )

        assertTrue(result is AutomationDispatchResult.Started)
        assertTrue(runtime.running)
        assertEquals(1, runtime.starts)
        assertEquals(true, result.state.lastTriggerActive)
    }

    @Test
    fun `actual disconnected event stops manually running service without ownership`() {
        val store = FakeStateStore()
        val runtime = FakeRuntime(running = true)
        val controller = controller(store, runtime)
        val generation = controller.selectMode(AutomationTriggerMode.BLUETOOTH).state.generation

        val result = controller.onTriggerStateChanged(
            AutomationTriggerMode.BLUETOOTH,
            generation,
            active = false,
        )

        assertTrue(result is AutomationDispatchResult.Stopped)
        assertFalse(runtime.running)
        assertEquals(1, runtime.stops)
        assertEquals(1, runtime.pendingStartCancellations)
        assertEquals(false, result.state.lastTriggerActive)
    }

    @Test
    fun `actual disconnected event cancels delayed manual start even before service is running`() {
        val store = FakeStateStore()
        val runtime = FakeRuntime(running = false)
        val controller = controller(store, runtime)
        val generation = controller.selectMode(AutomationTriggerMode.HOTSPOT).state.generation

        val result = controller.onTriggerStateChanged(
            AutomationTriggerMode.HOTSPOT,
            generation,
            active = false,
        )

        assertTrue(result is AutomationDispatchResult.NoChange)
        assertEquals(1, runtime.pendingStartCancellations)
        assertEquals(0, runtime.stops)
        assertEquals(false, result.state.lastTriggerActive)
    }

    @Test
    fun `failed stop does not consume inactive event and same event can retry`() {
        val store = FakeStateStore()
        val runtime = FakeRuntime(
            running = true,
            stopResult = AutomationRuntimeResult.Failed(
                AutomationRuntimeFailureReason.PLATFORM_REJECTED,
            ),
        )
        val controller = controller(store, runtime)
        val generation = controller.selectMode(AutomationTriggerMode.BLUETOOTH).state.generation

        val failed = controller.onTriggerStateChanged(
            AutomationTriggerMode.BLUETOOTH,
            generation,
            active = false,
        )
        runtime.stopResult = AutomationRuntimeResult.Success
        val retried = controller.onTriggerStateChanged(
            AutomationTriggerMode.BLUETOOTH,
            generation,
            active = false,
        )

        assertTrue(failed is AutomationDispatchResult.Failed)
        assertNull(failed.state.lastTriggerActive)
        assertTrue(retried is AutomationDispatchResult.Stopped)
        assertEquals(2, runtime.stops)
        assertEquals(2, runtime.pendingStartCancellations)
        assertEquals(false, retried.state.lastTriggerActive)
    }

    @Test
    fun `inactive mode initialization does not synthesize stop`() {
        val store = FakeStateStore()
        val runtime = FakeRuntime(running = true)
        val controller = controller(store, runtime)

        val result = controller.selectMode(AutomationTriggerMode.BLUETOOTH)

        assertTrue(result is AutomationDispatchResult.ModeChanged)
        assertEquals(null, result.state.lastTriggerActive)
        assertEquals(0, runtime.stops)
        assertTrue(runtime.running)
    }

    @Test
    fun `duplicate source state cannot restart or stop a manually changed session`() {
        val store = FakeStateStore()
        val runtime = FakeRuntime()
        val controller = controller(store, runtime)
        val generation = controller.selectMode(AutomationTriggerMode.BLUETOOTH).state.generation
        controller.onTriggerStateChanged(AutomationTriggerMode.BLUETOOTH, generation, active = true)
        runtime.running = false

        val duplicateConnect = controller.onTriggerStateChanged(
            AutomationTriggerMode.BLUETOOTH,
            generation,
            active = true,
        )
        runtime.running = true
        controller.onTriggerStateChanged(AutomationTriggerMode.BLUETOOTH, generation, active = false)
        runtime.running = true
        val duplicateDisconnect = controller.onTriggerStateChanged(
            AutomationTriggerMode.BLUETOOTH,
            generation,
            active = false,
        )

        assertTrue(duplicateConnect is AutomationDispatchResult.NoChange)
        assertTrue(duplicateDisconnect is AutomationDispatchResult.NoChange)
        assertEquals(1, runtime.starts)
        assertEquals(1, runtime.stops)
    }

    @Test
    fun `changing selected device resets generation without touching service`() {
        val store = FakeStateStore()
        val runtime = FakeRuntime(running = true)
        val controller = controller(store, runtime)
        val first = controller.selectMode(AutomationTriggerMode.BLUETOOTH).state
        controller.onTriggerStateChanged(
            AutomationTriggerMode.BLUETOOTH,
            first.generation,
            active = true,
        )

        val reset = controller.resetSelectedModeSignal(AutomationTriggerMode.BLUETOOTH)

        assertTrue(reset is AutomationDispatchResult.ModeChanged)
        assertEquals(first.generation + 1L, reset.state.generation)
        assertNull(reset.state.lastTriggerActive)
        assertEquals(0, runtime.starts)
        assertEquals(0, runtime.stops)
    }

    @Test
    fun `background restriction is deferred with recovery notification`() {
        val store = FakeStateStore()
        val runtime = FakeRuntime(
            startResult = AutomationRuntimeResult.Deferred(
                AutomationStartDeferredReason.BACKGROUND_FOREGROUND_SERVICE_START_RESTRICTED,
            ),
        )
        var fallbackCalls = 0
        val controller = CoordinatingAutomationServiceController(
            store,
            runtime,
            AutomationStartFallback {
                fallbackCalls += 1
                true
            },
        )
        val generation = controller.selectMode(AutomationTriggerMode.BLUETOOTH).state.generation

        val result = controller.onTriggerStateChanged(
            AutomationTriggerMode.BLUETOOTH,
            generation,
            active = true,
        )

        assertTrue(result is AutomationDispatchResult.StartDeferred)
        assertTrue((result as AutomationDispatchResult.StartDeferred).fallbackShown)
        assertEquals(1, fallbackCalls)
    }

    @Test
    fun `persistence failure prevents service dispatch`() {
        val store = FakeStateStore()
        val runtime = FakeRuntime()
        val controller = controller(store, runtime)
        val generation = controller.selectMode(AutomationTriggerMode.BLUETOOTH).state.generation
        store.allowSave = false

        val result = controller.onTriggerStateChanged(
            AutomationTriggerMode.BLUETOOTH,
            generation,
            active = true,
        )

        assertTrue(result is AutomationDispatchResult.Failed)
        assertEquals(0, runtime.starts)
    }

    @Test
    fun `inactive event still cancels and stops when event persistence fails`() {
        val store = FakeStateStore()
        val runtime = FakeRuntime(running = true)
        val controller = controller(store, runtime)
        val generation = controller.selectMode(AutomationTriggerMode.BLUETOOTH).state.generation
        store.allowSave = false

        val result = controller.onTriggerStateChanged(
            AutomationTriggerMode.BLUETOOTH,
            generation,
            active = false,
        )

        assertTrue(result is AutomationDispatchResult.Failed)
        assertEquals(AutomationOperation.SAVE_STATE, (result as AutomationDispatchResult.Failed).operation)
        assertEquals(1, runtime.pendingStartCancellations)
        assertEquals(1, runtime.stops)
        assertFalse(runtime.running)
        assertNull(store.state.lastTriggerActive)
    }

    private fun controller(
        store: FakeStateStore,
        runtime: FakeRuntime,
    ) = CoordinatingAutomationServiceController(store, runtime)

    private class FakeStateStore : AutomationControlStateStore {
        var state = AutomationControlState()
        var allowSave = true

        override fun load(): AutomationControlState = state

        override fun save(state: AutomationControlState): Boolean {
            if (!allowSave) return false
            this.state = state
            return true
        }
    }

    private class FakeRuntime(
        var running: Boolean = false,
        private val startResult: AutomationRuntimeResult = AutomationRuntimeResult.Success,
        var stopResult: AutomationRuntimeResult = AutomationRuntimeResult.Success,
    ) : AutomationServiceRuntime {
        var starts = 0
        var stops = 0
        var pendingStartCancellations = 0

        override fun isRunning(): Boolean = running

        override fun start(): AutomationRuntimeResult {
            starts += 1
            if (startResult == AutomationRuntimeResult.Success) running = true
            return startResult
        }

        override fun stop(): AutomationRuntimeResult {
            stops += 1
            if (stopResult == AutomationRuntimeResult.Success) running = false
            return stopResult
        }

        override fun cancelPendingStarts() {
            pendingStartCancellations += 1
        }
    }
}
