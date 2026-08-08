package com.pebble.tecomheadunit.core

import com.pebble.tecomheadunit.openauto.BenchOpenAutoEngine
import com.pebble.tecomheadunit.openauto.OpenAutoConfig
import com.pebble.tecomheadunit.openauto.OpenAutoInputSink
import com.pebble.tecomheadunit.openauto.ProjectionViewportResolver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenAutoCoordinatorTest {
    @Test
    fun benchSessionMapsAndAcceptsTouch() {
        val coordinator = OpenAutoCoordinator(BenchOpenAutoEngine(), SafetyGate())

        assertTrue(coordinator.start(VehicleState.BENCH_STATIONARY).isSuccess)
        val mapped = coordinator.submitTouch(
            NormalizedTouch(TouchPhase.DOWN, x = 0.5, y = 0.5),
        ).getOrThrow()

        assertEquals(400, mapped.x)
        assertEquals(240, mapped.y)
    }

    @Test
    fun movingTransitionStopsFurtherInput() {
        val coordinator = OpenAutoCoordinator(BenchOpenAutoEngine(), SafetyGate())
        coordinator.start(VehicleState.BENCH_STATIONARY).getOrThrow()
        coordinator.updateVehicleState(VehicleState.MOVING)

        assertTrue(
            coordinator.submitTouch(NormalizedTouch(TouchPhase.DOWN, 0.3, 0.4)).isFailure,
        )
    }

    @Test
    fun externalRuntimeRejectionIsReturnedToBrowserPath() {
        val inputSink = object : OpenAutoInputSink {
            override fun sendTouch(event: OpenAutoTouchEvent): Boolean = false
        }
        val coordinator = OpenAutoCoordinator(
            engine = BenchOpenAutoEngine(),
            safetyGate = SafetyGate(),
            inputSink = inputSink,
        )
        coordinator.start(VehicleState.BENCH_STATIONARY).getOrThrow()

        assertTrue(
            coordinator.submitTouch(NormalizedTouch(TouchPhase.DOWN, 0.3, 0.4)).isFailure,
        )
    }

    @Test
    fun browserContentTouchUsesContentOriginWithoutEncodedVideoMarginOffset() {
        val coordinator = OpenAutoCoordinator(
            engine = BenchOpenAutoEngine(),
            safetyGate = SafetyGate(),
            config = OpenAutoConfig(
                viewport = VideoViewport(1280, 720),
                totalMarginWidth = 320,
                totalMarginHeight = 120,
            ),
        )
        coordinator.start(VehicleState.BENCH_STATIONARY).getOrThrow()

        val topLeft = coordinator.submitTouch(
            NormalizedTouch(TouchPhase.DOWN, x = 0.0, y = 0.0),
        ).getOrThrow()
        val center = coordinator.submitTouch(
            NormalizedTouch(TouchPhase.MOVE, x = 0.5, y = 0.5),
        ).getOrThrow()
        val bottomRight = coordinator.submitTouch(
            NormalizedTouch(TouchPhase.UP, x = 1.0, y = 1.0),
        ).getOrThrow()

        assertEquals(0, topLeft.x)
        assertEquals(0, topLeft.y)
        assertEquals(480, center.x)
        assertEquals(300, center.y)
        assertEquals(959, bottomRight.x)
        assertEquals(599, bottomRight.y)
    }

    @Test
    fun portraitBrowserContentMapsOnceToExactEncodedPixels() {
        val viewport = VideoViewport(1080, 1920)
        val layout = ProjectionViewportResolver.resolve(
            encodedViewport = viewport,
            browserWidth = 800,
            browserHeight = 1280,
        )
        val config = layout.applyTo(
            OpenAutoConfig(viewport = viewport, dpi = layout.densityDpi),
        )
        val coordinator = OpenAutoCoordinator(
            engine = BenchOpenAutoEngine(),
            safetyGate = SafetyGate(),
            config = config,
        )
        coordinator.start(VehicleState.BENCH_STATIONARY).getOrThrow()

        val topLeft = coordinator.submitTouch(
            NormalizedTouch(TouchPhase.DOWN, x = 0.0, y = 0.0),
        ).getOrThrow()
        val center = coordinator.submitTouch(
            NormalizedTouch(TouchPhase.MOVE, x = 0.5, y = 0.5),
        ).getOrThrow()
        val bottomRight = coordinator.submitTouch(
            NormalizedTouch(TouchPhase.UP, x = 1.0, y = 1.0),
        ).getOrThrow()

        assertEquals(0, layout.totalMarginWidth)
        assertEquals(192, layout.totalMarginHeight)
        assertEquals(1080, config.contentViewport.width)
        assertEquals(1728, config.contentViewport.height)
        assertEquals(0, topLeft.x)
        assertEquals(0, topLeft.y)
        assertEquals(540, center.x)
        assertEquals(864, center.y)
        assertEquals(1079, bottomRight.x)
        assertEquals(1727, bottomRight.y)
    }

    @Test
    fun letterboxedBrowserContentMapsOnceToExactEncodedPixels() {
        val viewport = VideoViewport(1920, 1080)
        val layout = ProjectionViewportResolver.resolve(
            encodedViewport = viewport,
            browserWidth = 1920,
            browserHeight = 600,
        )
        val coordinator = OpenAutoCoordinator(
            engine = BenchOpenAutoEngine(),
            safetyGate = SafetyGate(),
            config = layout.applyTo(
                OpenAutoConfig(viewport = viewport, dpi = layout.densityDpi),
            ),
        )
        coordinator.start(VehicleState.BENCH_STATIONARY).getOrThrow()

        val topLeft = coordinator.submitTouch(
            NormalizedTouch(TouchPhase.DOWN, x = 0.0, y = 0.0),
        ).getOrThrow()
        val bottomRight = coordinator.submitTouch(
            NormalizedTouch(TouchPhase.UP, x = 1.0, y = 1.0),
        ).getOrThrow()

        assertEquals(480, layout.totalMarginHeight)
        assertEquals(0, topLeft.x)
        assertEquals(0, topLeft.y)
        assertEquals(1919, bottomRight.x)
        assertEquals(599, bottomRight.y)
    }
}
