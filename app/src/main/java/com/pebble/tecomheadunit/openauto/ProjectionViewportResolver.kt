/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.pebble.tecomheadunit.openauto

import com.pebble.tecomheadunit.core.VideoViewport
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToInt

/** A centred content rectangle inside the fixed Android Auto encoded frame. */
data class ProjectionContentRect(
    val left: Int,
    val top: Int,
    val width: Int,
    val height: Int,
) {
    init {
        require(left >= 0 && top >= 0) { "content origin must not be negative" }
        require(width > 0 && height > 0) { "content dimensions must be positive" }
    }

    val rightExclusive: Int
        get() = left + width

    val bottomExclusive: Int
        get() = top + height
}

/**
 * Dynamic geometry sent to Android Auto while the encoder keeps [encodedViewport] unchanged.
 * Android Auto defines margin_width and margin_height as totals, so each is split equally around
 * the centred [contentRect].
 */
data class ProjectionViewportLayout(
    val encodedViewport: VideoViewport,
    val totalMarginWidth: Int,
    val totalMarginHeight: Int,
    val densityDpi: Int = ProjectionViewportResolver.DEFAULT_BASE_DENSITY_DPI,
) {
    init {
        require(totalMarginWidth >= 0 && totalMarginWidth < encodedViewport.width) {
            "total margin width must leave a non-empty content viewport"
        }
        require(totalMarginHeight >= 0 && totalMarginHeight < encodedViewport.height) {
            "total margin height must leave a non-empty content viewport"
        }
        require(totalMarginWidth % 2 == 0 && totalMarginHeight % 2 == 0) {
            "centred viewport margins must be even"
        }
        require(
            densityDpi in ProjectionViewportResolver.MIN_DYNAMIC_DENSITY_DPI..
                ProjectionViewportResolver.MAX_BASE_DENSITY_DPI,
        ) {
            "projection density is outside the supported range"
        }
    }

    val contentRect: ProjectionContentRect
        get() = ProjectionContentRect(
            left = totalMarginWidth / 2,
            top = totalMarginHeight / 2,
            width = encodedViewport.width - totalMarginWidth,
            height = encodedViewport.height - totalMarginHeight,
        )

    fun applyTo(config: OpenAutoConfig): OpenAutoConfig {
        require(config.viewport == encodedViewport) { "layout and projection viewport differ" }
        require(densityDpi <= config.dpi) {
            "dynamic projection density must not enlarge UI above the profile baseline"
        }
        return config.copy(
            totalMarginWidth = totalMarginWidth,
            totalMarginHeight = totalMarginHeight,
            dpi = densityDpi,
        )
    }
}

/**
 * Converts a browser CSS viewport aspect ratio into AA total margins.
 *
 * The largest matching rectangle is centred inside the fixed encoded frame. Margins are rounded
 * to eight-pixel boundaries to avoid reconnect churn from single-pixel browser chrome changes.
 */
object ProjectionViewportResolver {
    const val MARGIN_QUANTUM_PIXELS = 8
    const val DEFAULT_HYSTERESIS_PIXELS = MARGIN_QUANTUM_PIXELS * 2
    const val DEFAULT_BASE_DENSITY_DPI = 140
    const val MIN_DYNAMIC_DENSITY_DPI = 72
    const val MAX_BASE_DENSITY_DPI = DEFAULT_BASE_DENSITY_DPI
    const val DENSITY_QUANTUM_DPI = 4
    const val DENSITY_HYSTERESIS_DPI = DENSITY_QUANTUM_DPI * 2
    const val MIN_LOGICAL_CONTENT_WIDTH_DP = 480
    const val MIN_DEVICE_PIXEL_RATIO = 0.5
    const val MAX_DEVICE_PIXEL_RATIO = 8.0
    const val MAX_VIEWPORT_EDGE_PIXELS = 16_384
    const val MIN_CONTENT_WIDTH_PIXELS = 216
    const val MIN_CONTENT_HEIGHT_PIXELS = 240

    fun resolve(
        encodedViewport: VideoViewport,
        browserWidth: Int,
        browserHeight: Int,
        baseDensityDpi: Int = DEFAULT_BASE_DENSITY_DPI,
        browserDevicePixelRatio: Double = 1.0,
    ): ProjectionViewportLayout {
        require(encodedViewport.width <= MAX_VIEWPORT_EDGE_PIXELS) {
            "encoded viewport width exceeds limit"
        }
        require(encodedViewport.height <= MAX_VIEWPORT_EDGE_PIXELS) {
            "encoded viewport height exceeds limit"
        }
        require(browserWidth in 1..MAX_VIEWPORT_EDGE_PIXELS) {
            "browser width is outside the supported range"
        }
        require(browserHeight in 1..MAX_VIEWPORT_EDGE_PIXELS) {
            "browser height is outside the supported range"
        }
        require(baseDensityDpi in MIN_DYNAMIC_DENSITY_DPI..MAX_BASE_DENSITY_DPI) {
            "base projection density is outside the supported range"
        }
        require(
            browserDevicePixelRatio.isFinite() &&
                browserDevicePixelRatio in MIN_DEVICE_PIXEL_RATIO..MAX_DEVICE_PIXEL_RATIO,
        ) {
            "browser device pixel ratio is outside the supported range"
        }

        val browserCrossProduct = browserWidth.toLong() * encodedViewport.height
        val encodedCrossProduct = encodedViewport.width.toLong() * browserHeight
        val rawMarginWidth: Double
        val rawMarginHeight: Double
        when {
            browserCrossProduct < encodedCrossProduct -> {
                val contentWidth = encodedViewport.height.toDouble() * browserWidth / browserHeight
                rawMarginWidth = encodedViewport.width - contentWidth
                rawMarginHeight = 0.0
            }

            browserCrossProduct > encodedCrossProduct -> {
                val contentHeight = encodedViewport.width.toDouble() * browserHeight / browserWidth
                rawMarginWidth = 0.0
                rawMarginHeight = encodedViewport.height - contentHeight
            }

            else -> {
                rawMarginWidth = 0.0
                rawMarginHeight = 0.0
            }
        }

        val totalMarginWidth = quantizeMargin(rawMarginWidth)
        val totalMarginHeight = quantizeMargin(rawMarginHeight)
        val contentWidth = encodedViewport.width - totalMarginWidth
        val layout = ProjectionViewportLayout(
            encodedViewport = encodedViewport,
            totalMarginWidth = totalMarginWidth,
            totalMarginHeight = totalMarginHeight,
            densityDpi = resolveDensityDpi(contentWidth, baseDensityDpi),
        )
        val content = layout.contentRect
        require(
            content.width >= MIN_CONTENT_WIDTH_PIXELS &&
                content.height >= MIN_CONTENT_HEIGHT_PIXELS,
        ) {
            "browser aspect ratio leaves an unsafe projection content viewport"
        }
        return layout
    }

    /** Returns false for minor quantized oscillation around a browser resize boundary. */
    fun isMeaningfulChange(
        current: ProjectionViewportLayout,
        candidate: ProjectionViewportLayout,
        hysteresisPixels: Int = DEFAULT_HYSTERESIS_PIXELS,
    ): Boolean {
        require(hysteresisPixels >= MARGIN_QUANTUM_PIXELS) {
            "hysteresis must cover at least one margin quantum"
        }
        if (current.encodedViewport != candidate.encodedViewport) return true
        return abs(current.totalMarginWidth - candidate.totalMarginWidth) >= hysteresisPixels ||
            abs(current.totalMarginHeight - candidate.totalMarginHeight) >= hysteresisPixels ||
            abs(current.densityDpi - candidate.densityDpi) >= DENSITY_HYSTERESIS_DPI
    }

    /** Keeps [current] until [candidate] crosses the configured hysteresis threshold. */
    fun stabilize(
        current: ProjectionViewportLayout,
        candidate: ProjectionViewportLayout,
        hysteresisPixels: Int = DEFAULT_HYSTERESIS_PIXELS,
    ): ProjectionViewportLayout = if (isMeaningfulChange(current, candidate, hysteresisPixels)) {
        candidate
    } else {
        current
    }

    /**
     * Android Auto lays out its UI in dp using the encoded content width and advertised density.
     * A narrow margin-shaped content rectangle can otherwise collapse below a phone-class 480 dp
     * width, making fixed-size controls overlap. Density only decreases, in four-dpi steps.
     *
     * CSS pixels are already reference pixels. Multiplying both the browser surface and its
     * reference-pixel scale by devicePixelRatio cancels out, so DPR is validated by [resolve] but
     * deliberately does not change this logical-width calculation.
     */
    internal fun resolveDensityDpi(contentWidth: Int, baseDensityDpi: Int): Int {
        require(contentWidth > 0) { "content width must be positive" }
        require(baseDensityDpi in MIN_DYNAMIC_DENSITY_DPI..MAX_BASE_DENSITY_DPI) {
            "base projection density is outside the supported range"
        }
        val logicalWidthAtBaseline =
            contentWidth.toDouble() * OpenAutoConfig.REFERENCE_DENSITY_DPI / baseDensityDpi
        if (logicalWidthAtBaseline >= MIN_LOGICAL_CONTENT_WIDTH_DP) return baseDensityDpi

        val upperBound = contentWidth.toDouble() * OpenAutoConfig.REFERENCE_DENSITY_DPI /
            MIN_LOGICAL_CONTENT_WIDTH_DP
        val quantized = floor(upperBound / DENSITY_QUANTUM_DPI).toInt() * DENSITY_QUANTUM_DPI
        return quantized.coerceIn(MIN_DYNAMIC_DENSITY_DPI, baseDensityDpi)
    }

    private fun quantizeMargin(rawMargin: Double): Int =
        (rawMargin / MARGIN_QUANTUM_PIXELS).roundToInt() * MARGIN_QUANTUM_PIXELS
}
