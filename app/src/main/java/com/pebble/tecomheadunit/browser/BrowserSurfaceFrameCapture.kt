/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.pebble.tecomheadunit.browser

import android.graphics.Bitmap
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import android.view.PixelCopy
import android.view.Surface
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Low-rate browser preview copied from the existing MediaCodec output Surface.
 *
 * PixelCopy is requested only while at least one counted browser subscriber is
 * active. This class borrows the UI-owned Surface and never releases it. All
 * capture, JPEG encoding and lifecycle state are serialized on one HandlerThread.
 */
class BrowserSurfaceFrameCapture(
    private val frameStore: BrowserFrameStore,
    private val width: Int = DEFAULT_WIDTH,
    private val height: Int = DEFAULT_HEIGHT,
    targetFramesPerSecond: Int = DEFAULT_TARGET_FPS,
    private val jpegQuality: Int = DEFAULT_JPEG_QUALITY,
) : Closeable {
    @Suppress("unused")
    private val validatedParameters = Unit.also {
        require(width > 0 && height > 0) { "invalid browser capture dimensions" }
        require(jpegQuality in 1..100) { "invalid browser JPEG quality" }
    }
    private val closed = AtomicBoolean(false)
    private val frameIntervalMillis = 1_000L / targetFramesPerSecond.also {
        require(it in 1..MAX_TARGET_FPS) { "invalid browser capture frame rate" }
    }
    private val rateLimiter = BrowserCaptureRateLimiter(frameIntervalMillis)
    private val captureThread = HandlerThread("TecomBrowserPixelCopy").apply { start() }
    private val handler = Handler(captureThread.looper)

    // Handler-thread confined state.
    private var surface: Surface? = null
    private var surfaceGeneration = NO_GENERATION
    private var subscriberCount = 0
    private var captureScheduled = false
    private var copyInFlight = false
    private var bitmap: Bitmap? = null
    private var requestStartedAtMillis = 0L
    private var closing = false

    private val captureTick = Runnable {
        captureScheduled = false
        requestCaptureIfEligible()
    }

    init {
        frameStore.setSubscriberListener {
            if (!closed.get()) {
                // Listener calls may originate from concurrent HTTP workers.
                // Resolve the current count on our serialized handler instead
                // of trusting an older queued transition.
                handler.post {
                    onSubscriberCountChanged(frameStore.activeSubscriberCount)
                }
            }
        }
    }

    /** Attaches a newer UI-owned output Surface. Ownership remains with SurfaceView. */
    fun attach(outputSurface: Surface, generation: Long) {
        require(generation >= 0L) { "invalid projection surface generation" }
        if (closed.get()) return
        handler.post {
            if (closing || generation < surfaceGeneration) return@post
            if (generation == surfaceGeneration && surface === outputSurface) {
                scheduleCapture(rateLimiter.remainingDelayMillis(SystemClock.elapsedRealtime()))
                return@post
            }
            surface = outputSurface
            surfaceGeneration = generation
            frameStore.clear()
            scheduleCapture(rateLimiter.remainingDelayMillis(SystemClock.elapsedRealtime()))
        }
    }

    /** Detaches only the matching generation, preventing stale UI callbacks. */
    fun detach(generation: Long) {
        if (generation < 0L || closed.get()) return
        handler.post {
            if (generation != surfaceGeneration) return@post
            surface = null
            frameStore.clear()
            cancelScheduledCapture()
            recycleBitmapWhenIdle()
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        frameStore.setSubscriberListener(null)
        handler.post {
            closing = true
            surface = null
            subscriberCount = 0
            cancelScheduledCapture()
            frameStore.clear()
            if (!copyInFlight) {
                recycleBitmap()
                captureThread.quitSafely()
            } else {
                // PixelCopy normally completes promptly. This timeout only
                // releases the worker; it deliberately does not recycle a
                // bitmap that the platform may still be writing.
                handler.postDelayed({ captureThread.quitSafely() }, CLOSE_TIMEOUT_MILLIS)
            }
        }
    }

    private fun onSubscriberCountChanged(count: Int) {
        if (closing) return
        subscriberCount = count.coerceAtLeast(0)
        if (subscriberCount == 0) {
            cancelScheduledCapture()
            return
        }
        // A browser may long-poll using one short subscription per HTTP
        // request. Preserve the global FPS ceiling across 0 -> 1 transitions.
        scheduleCapture(rateLimiter.remainingDelayMillis(SystemClock.elapsedRealtime()))
    }

    private fun requestCaptureIfEligible() {
        if (closing || copyInFlight || frameStore.activeSubscriberCount == 0) return
        val activeSurface = surface?.takeIf(Surface::isValid) ?: return
        val generation = surfaceGeneration
        val target = bitmap?.takeUnless(Bitmap::isRecycled) ?: Bitmap.createBitmap(
            width,
            height,
            Bitmap.Config.ARGB_8888,
        ).also { bitmap = it }

        copyInFlight = true
        requestStartedAtMillis = SystemClock.elapsedRealtime()
        rateLimiter.markRequestStarted(requestStartedAtMillis)
        try {
            PixelCopy.request(
                activeSurface,
                target,
                { result -> onPixelCopyFinished(result, activeSurface, generation, target) },
                handler,
            )
        } catch (error: Throwable) {
            copyInFlight = false
            Log.w(LOG_TAG, "BROWSER_PIXEL_COPY_REQUEST_FAILED ${error.javaClass.simpleName}")
            scheduleNextCapture()
        }
    }

    private fun onPixelCopyFinished(
        result: Int,
        requestedSurface: Surface,
        requestedGeneration: Long,
        target: Bitmap,
    ) {
        copyInFlight = false
        val requestStillCurrent = !closing &&
            frameStore.activeSubscriberCount > 0 &&
            surface === requestedSurface &&
            surfaceGeneration == requestedGeneration &&
            requestedSurface.isValid

        if (result == PixelCopy.SUCCESS && requestStillCurrent) {
            val jpegOutput = ByteArrayOutputStream(INITIAL_JPEG_CAPACITY)
            val encoded = target.compress(Bitmap.CompressFormat.JPEG, jpegQuality, jpegOutput)
            if (!encoded || !frameStore.publishOwned(jpegOutput.toByteArray())) {
                Log.w(LOG_TAG, "BROWSER_JPEG_FRAME_REJECTED")
            }
        } else if (result != PixelCopy.SUCCESS && requestStillCurrent) {
            Log.w(LOG_TAG, "BROWSER_PIXEL_COPY_FAILED result=$result")
        }

        if (closing) {
            recycleBitmap()
            captureThread.quitSafely()
            return
        }
        recycleBitmapWhenIdle()
        scheduleNextCapture()
    }

    private fun scheduleNextCapture() {
        scheduleCapture(rateLimiter.remainingDelayMillis(SystemClock.elapsedRealtime()))
    }

    private fun scheduleCapture(delayMillis: Long) {
        if (closing || captureScheduled || copyInFlight || frameStore.activeSubscriberCount == 0) return
        if (surface?.isValid != true) return
        captureScheduled = true
        handler.postDelayed(captureTick, delayMillis)
    }

    private fun cancelScheduledCapture() {
        if (!captureScheduled) return
        handler.removeCallbacks(captureTick)
        captureScheduled = false
    }

    private fun recycleBitmapWhenIdle() {
        if (copyInFlight || surface != null) return
        recycleBitmap()
    }

    private fun recycleBitmap() {
        bitmap?.takeUnless(Bitmap::isRecycled)?.recycle()
        bitmap = null
    }

    private companion object {
        const val LOG_TAG = "TecomBrowser"
        const val DEFAULT_WIDTH = 800
        const val DEFAULT_HEIGHT = 480
        const val DEFAULT_TARGET_FPS = 5
        const val MAX_TARGET_FPS = 10
        const val DEFAULT_JPEG_QUALITY = 70
        const val INITIAL_JPEG_CAPACITY = 128 * 1024
        const val NO_GENERATION = -1L
        const val CLOSE_TIMEOUT_MILLIS = 1_000L
    }
}
