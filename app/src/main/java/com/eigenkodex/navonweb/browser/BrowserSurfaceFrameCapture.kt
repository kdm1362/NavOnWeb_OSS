/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.eigenkodex.navonweb.browser

import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean
import org.webrtc.EglBase
import org.webrtc.EglRenderer
import org.webrtc.GlRectDrawer
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoFrame
import org.webrtc.VideoSink

/**
 * Service-owned Android Auto decoder target and low-rate browser JPEG fan-out.
 *
 * The owned [decoderSurface] is backed by a continuously draining [SurfaceTextureHelper], so the
 * MediaCodec producer never depends on an Activity Surface or on an active HTTP subscriber. When
 * native WebRTC is available, its texture bridge can also fan out the same [VideoFrame] through
 * [onFrame]; only the runtime-bound decoder target receives frames. I420 conversion and JPEG
 * encoding are latest-only, subscriber-gated and serialized on one worker.
 *
 * [attach] and [detach] manage only an optional Activity preview. They never release the decoder
 * target or clear the latest browser frame.
 */
class BrowserSurfaceFrameCapture(
    private val frameStore: BrowserFrameStore,
    private val width: Int = DEFAULT_WIDTH,
    private val height: Int = DEFAULT_HEIGHT,
    targetFramesPerSecond: Int = DEFAULT_TARGET_FPS,
    private val jpegQuality: Int = DEFAULT_JPEG_QUALITY,
) : Closeable, VideoSink {
    @Suppress("unused")
    private val validatedParameters = Unit.also {
        require(width > 0 && height > 0 && width % 2 == 0 && height % 2 == 0) {
            "browser capture dimensions must be positive and even"
        }
        require(jpegQuality in 1..100) { "invalid browser JPEG quality" }
    }
    private val closed = AtomicBoolean(false)
    private val lifecycleLock = Any()
    private val frameIntervalMillis = 1_000L / targetFramesPerSecond.also {
        require(it in 1..MAX_TARGET_FPS) { "invalid browser capture frame rate" }
    }
    private val initialJpegCapacity: Int =
        (width.toLong() * height / 4L)
            .coerceIn(INITIAL_JPEG_CAPACITY.toLong(), MAX_INITIAL_JPEG_CAPACITY.toLong())
            .toInt()
    private val rateLimiter = BrowserCaptureRateLimiter(frameIntervalMillis)
    private val decoderResources = createDecoderResources(width, height)
    private val captureThread = HandlerThread("NavOnWebBrowserJpegEncoder").apply { start() }
    private val handler = Handler(captureThread.looper)

    /** Stable service-owned target used when the native WebRTC controller is unavailable. */
    val decoderSurface: Surface
        get() = decoderResources.surface

    private val previewBinding = PreviewRendererBinding<Surface, EglRenderer>(
        sameTarget = { first, second -> first === second },
        releaseRenderer = EglRenderer::release,
    )

    // Handler-thread confined state.
    private var subscriberCount = 0
    private var pendingFrame: VideoFrame? = null
    private var encodeScheduled = false
    private var closing = false

    private val encodeTick = Runnable {
        encodeScheduled = false
        encodeLatestFrame()
    }

    private val subscriberListener: (Int) -> Unit = {
        if (!closed.get()) {
            // Listener callbacks run after the store lock is released and may be posted out of
            // order. Resolve the authoritative count on the serialized worker.
            handler.post {
                onSubscriberCountChanged(frameStore.activeSubscriberCount)
            }
        }
    }

    init {
        frameStore.setSubscriberListener(subscriberListener)
        decoderResources.textureHelper.startListening(this)
    }

    /** Attaches a newer caller-owned preview Surface without taking ownership of it. */
    fun attach(outputSurface: Surface, generation: Long) {
        require(generation >= 0L) { "invalid projection surface generation" }
        require(outputSurface.isValid) { "preview Surface is invalid" }
        if (closed.get()) return

        previewBinding.attach(outputSurface, generation) {
            createInitializedPreviewRenderer(
                allocate = { EglRenderer(PREVIEW_RENDERER_NAME) },
                initialize = { candidate ->
                    candidate.init(
                        decoderResources.eglBase.eglBaseContext,
                        EglBase.CONFIG_PLAIN,
                        GlRectDrawer(),
                    )
                    candidate.setFpsReduction(PREVIEW_FRAMES_PER_SECOND.toFloat())
                    candidate.createEglSurface(outputSurface)
                },
                release = EglRenderer::release,
            )
        }
    }

    /** Detaches only the matching Activity preview generation. */
    fun detach(generation: Long? = null) {
        if (generation != null && generation < 0L) return
        previewBinding.detach(generation)
    }

    /**
     * Receives either the owned decoder texture or the native controller's texture fan-out.
     * The renderer and WebRTC sink retain frames they need; this method retains only a bounded
     * latest frame for the asynchronous JPEG encoder.
     */
    override fun onFrame(frame: VideoFrame) {
        val queueForJpeg: Boolean
        synchronized(lifecycleLock) {
            if (closed.get()) return
            queueForJpeg = frameStore.activeSubscriberCount > 0
            if (queueForJpeg) frame.retain()
        }
        previewBinding.dispatch { renderer -> renderer.onFrame(frame) }
        if (!queueForJpeg) return

        if (!handler.post { enqueueLatestFrame(frame) }) {
            frame.release()
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        frameStore.clearSubscriberListener(subscriberListener)

        // Stop both frame producers before releasing the EGL context. Any frame already retained
        // by the encoder worker is released by the serialized close task below.
        decoderResources.textureHelper.stopListening()
        previewBinding.close()
        handler.post {
            closing = true
            cancelScheduledEncode()
            pendingFrame?.release()
            pendingFrame = null
            decoderResources.surface.release()
            decoderResources.textureHelper.dispose()
            decoderResources.eglBase.release()
            captureThread.quitSafely()
        }
    }

    private fun onSubscriberCountChanged(count: Int) {
        if (closing) return
        subscriberCount = count.coerceAtLeast(0)
        if (subscriberCount == 0) {
            cancelScheduledEncode()
            pendingFrame?.release()
            pendingFrame = null
        }
    }

    private fun enqueueLatestFrame(frame: VideoFrame) {
        if (closing || subscriberCount == 0 || frameStore.activeSubscriberCount == 0) {
            frame.release()
            return
        }
        pendingFrame?.release()
        pendingFrame = frame
        scheduleEncode(rateLimiter.remainingDelayMillis(SystemClock.elapsedRealtime()))
    }

    private fun encodeLatestFrame() {
        if (closing || subscriberCount == 0 || frameStore.activeSubscriberCount == 0) {
            pendingFrame?.release()
            pendingFrame = null
            return
        }
        val frame = pendingFrame ?: return
        pendingFrame = null
        rateLimiter.markRequestStarted(SystemClock.elapsedRealtime())
        try {
            val buffer = frame.buffer.toI420()
            if (buffer == null) {
                Log.w(LOG_TAG, "BROWSER_JPEG_I420_UNAVAILABLE")
                return
            }
            try {
                val frameWidth = buffer.width
                val frameHeight = buffer.height
                if (frameWidth % 2 != 0 || frameHeight % 2 != 0) {
                    Log.w(LOG_TAG, "BROWSER_JPEG_ODD_I420_FRAME ${frameWidth}x$frameHeight")
                    return
                }
                val nv21 = copyI420ToNv21(
                    width = frameWidth,
                    height = frameHeight,
                    dataY = buffer.dataY,
                    strideY = buffer.strideY,
                    dataU = buffer.dataU,
                    strideU = buffer.strideU,
                    dataV = buffer.dataV,
                    strideV = buffer.strideV,
                )
                val output = ByteArrayOutputStream(initialJpegCapacity)
                val encoded = YuvImage(
                    nv21,
                    ImageFormat.NV21,
                    frameWidth,
                    frameHeight,
                    null,
                ).compressToJpeg(Rect(0, 0, frameWidth, frameHeight), jpegQuality, output)
                if (!encoded || !frameStore.publishOwned(output.toByteArray())) {
                    Log.w(LOG_TAG, "BROWSER_JPEG_FRAME_REJECTED")
                }
            } finally {
                buffer.release()
            }
        } catch (error: Throwable) {
            Log.w(LOG_TAG, "BROWSER_JPEG_ENCODE_FAILED ${error.javaClass.simpleName}")
        } finally {
            frame.release()
        }
        if (pendingFrame != null) {
            scheduleEncode(rateLimiter.remainingDelayMillis(SystemClock.elapsedRealtime()))
        }
    }

    private fun scheduleEncode(delayMillis: Long) {
        if (closing || encodeScheduled || pendingFrame == null || subscriberCount == 0) return
        encodeScheduled = true
        handler.postDelayed(encodeTick, delayMillis)
    }

    private fun cancelScheduledEncode() {
        if (!encodeScheduled) return
        handler.removeCallbacks(encodeTick)
        encodeScheduled = false
    }

    private data class DecoderResources(
        val eglBase: EglBase,
        val textureHelper: SurfaceTextureHelper,
        val surface: Surface,
    )

    private companion object {
        const val LOG_TAG = "NavOnWebBrowser"
        const val DECODER_THREAD_NAME = "NavOnWebBrowserFallbackTexture"
        const val PREVIEW_RENDERER_NAME = "NavOnWebBrowserFallbackPreview"
        const val DEFAULT_WIDTH = 800
        const val DEFAULT_HEIGHT = 480
        const val DEFAULT_TARGET_FPS = 5
        const val PREVIEW_FRAMES_PER_SECOND = 30
        const val MAX_TARGET_FPS = 10
        const val DEFAULT_JPEG_QUALITY = 70
        const val INITIAL_JPEG_CAPACITY = 128 * 1024
        const val MAX_INITIAL_JPEG_CAPACITY = 512 * 1024
        fun createDecoderResources(width: Int, height: Int): DecoderResources {
            val eglBase = EglBase.create()
            var textureHelper: SurfaceTextureHelper? = null
            var surface: Surface? = null
            try {
                textureHelper = checkNotNull(
                    SurfaceTextureHelper.create(DECODER_THREAD_NAME, eglBase.eglBaseContext),
                ) { "Unable to create browser fallback SurfaceTextureHelper" }
                textureHelper.setTextureSize(width, height)
                textureHelper.surfaceTexture.setDefaultBufferSize(width, height)
                textureHelper.setFrameRotation(0)
                surface = Surface(textureHelper.surfaceTexture)
                return DecoderResources(eglBase, textureHelper, surface)
            } catch (error: Throwable) {
                surface?.release()
                textureHelper?.dispose()
                eglBase.release()
                throw error
            }
        }
    }
}

/** Copies strided planar I420 into the tightly packed NV21 format expected by [YuvImage]. */
internal fun copyI420ToNv21(
    width: Int,
    height: Int,
    dataY: ByteBuffer,
    strideY: Int,
    dataU: ByteBuffer,
    strideU: Int,
    dataV: ByteBuffer,
    strideV: Int,
): ByteArray {
    require(width > 0 && height > 0 && width % 2 == 0 && height % 2 == 0) {
        "I420 dimensions must be positive and even"
    }
    require(strideY >= width && strideU >= width / 2 && strideV >= width / 2) {
        "I420 stride is smaller than its plane width"
    }
    requirePlaneCapacity(dataY, strideY, width, height, "Y")
    requirePlaneCapacity(dataU, strideU, width / 2, height / 2, "U")
    requirePlaneCapacity(dataV, strideV, width / 2, height / 2, "V")

    val output = ByteArray(width * height * 3 / 2)
    copyRows(
        source = dataY,
        stride = strideY,
        rowWidth = width,
        rows = height,
        destination = output,
    )
    var outputIndex = width * height
    val chromaWidth = width / 2
    // Bulk-copy each chroma row once instead of issuing one ByteBuffer.get(index) per sample;
    // the duplicates keep the caller-visible positions of libwebrtc's planes untouched.
    val uRow = ByteArray(chromaWidth)
    val vRow = ByteArray(chromaWidth)
    val u = dataU.duplicate()
    val v = dataV.duplicate()
    val uStart = u.position()
    val vStart = v.position()
    repeat(height / 2) { row ->
        u.position(uStart + row * strideU)
        u.get(uRow, 0, chromaWidth)
        v.position(vStart + row * strideV)
        v.get(vRow, 0, chromaWidth)
        for (column in 0 until chromaWidth) {
            output[outputIndex++] = vRow[column]
            output[outputIndex++] = uRow[column]
        }
    }
    return output
}

private fun requirePlaneCapacity(
    buffer: ByteBuffer,
    stride: Int,
    rowWidth: Int,
    rows: Int,
    name: String,
) {
    val required = (rows - 1).toLong() * stride + rowWidth
    require(required <= buffer.remaining().toLong()) { "$name plane is truncated" }
}

private fun copyRows(
    source: ByteBuffer,
    stride: Int,
    rowWidth: Int,
    rows: Int,
    destination: ByteArray,
) {
    val duplicate = source.duplicate()
    val start = duplicate.position()
    repeat(rows) { row ->
        duplicate.position(start + row * stride)
        duplicate.get(destination, row * rowWidth, rowWidth)
    }
}
