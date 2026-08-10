/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.pebble.tecomheadunit.openauto

import com.pebble.tecomheadunit.core.OpenAutoTouchEvent
import java.util.concurrent.atomic.AtomicReference

/**
 * Exercises lifecycle and input boundaries without claiming to speak the AA
 * protocol. This is the default until the optional AASDK native port is linked.
 */
class BenchOpenAutoEngine : OpenAutoEngine {
    private val snapshot = AtomicReference(
        EngineSnapshot(
            state = EngineState.STOPPED,
            detail = "벤치 엔진 대기",
            nativeRuntimeLinked = false,
        ),
    )

    private val lastTouch = AtomicReference<OpenAutoTouchEvent?>(null)

    override fun start(config: OpenAutoConfig): Result<Unit> {
        snapshot.set(
            EngineSnapshot(
                state = EngineState.BENCH_READY,
                detail = "브라우저·입력 P0 준비됨 (${config.viewport.width}×${config.viewport.height})",
                nativeRuntimeLinked = false,
            ),
        )
        return Result.success(Unit)
    }

    override fun sendTouch(event: OpenAutoTouchEvent): Boolean {
        if (snapshot.get().state != EngineState.BENCH_READY) return false
        lastTouch.set(event)
        return true
    }

    override fun stop() {
        lastTouch.set(null)
        snapshot.set(
            EngineSnapshot(
                state = EngineState.STOPPED,
                detail = "벤치 엔진 중지",
                nativeRuntimeLinked = false,
            ),
        )
    }

    override fun snapshot(): EngineSnapshot = snapshot.get()
}
