/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.pebble.tecomheadunit.core

import com.pebble.tecomheadunit.openauto.OpenAutoConfig
import com.pebble.tecomheadunit.openauto.OpenAutoEngine
import com.pebble.tecomheadunit.openauto.OpenAutoInputSink

class OpenAutoCoordinator(
    private val engine: OpenAutoEngine,
    private val safetyGate: SafetyGate,
    private val config: OpenAutoConfig = OpenAutoConfig(),
    private val inputSink: OpenAutoInputSink = engine,
) {
    @Volatile
    private var vehicleState: VehicleState = VehicleState.UNKNOWN

    fun start(state: VehicleState): Result<Unit> {
        vehicleState = state
        val decision = safetyGate.canStart(state)
        if (!decision.allowed) return Result.failure(IllegalStateException(decision.reason))
        return engine.start(config)
    }

    fun submitTouch(touch: NormalizedTouch): Result<OpenAutoTouchEvent> {
        val decision = safetyGate.canSendInput(vehicleState)
        if (!decision.allowed) return Result.failure(IllegalStateException(decision.reason))

        // Android Auto interprets input reports inside the negotiated content viewport, with
        // (0, 0) at the visible content's top-left. Video margins affect placement in the encoded
        // frame only; adding them to input coordinates makes portrait controls miss their visual
        // targets. The browser already normalizes against that same visible content rectangle.
        val mapped = TouchMapper.map(touch, config.contentViewport)
        if (!inputSink.sendTouch(mapped)) {
            return Result.failure(IllegalStateException("OpenAuto 입력 sink가 이벤트를 거부했습니다."))
        }
        return Result.success(mapped)
    }

    fun updateVehicleState(state: VehicleState) {
        vehicleState = state
        if (!safetyGate.canStart(state).allowed) engine.stop()
    }

    fun stop() {
        vehicleState = VehicleState.UNKNOWN
        engine.stop()
    }

    fun snapshot() = engine.snapshot()
}
