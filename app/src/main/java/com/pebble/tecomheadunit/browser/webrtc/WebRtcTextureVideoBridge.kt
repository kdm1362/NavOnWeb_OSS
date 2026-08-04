/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.pebble.tecomheadunit.browser.webrtc

import android.view.Surface
import java.io.Closeable
import org.webrtc.CapturerObserver
import org.webrtc.EglBase
import org.webrtc.EglRenderer
import org.webrtc.GlRectDrawer
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoFrame
import org.webrtc.VideoSink

/**
 * GPU-only bridge between MediaCodec, libwebrtc and the existing debug Surface.
 *
 * [decoderSurface] is the sole Surface MediaCodec should decode into. SurfaceTextureHelper turns
 * each rendered OES texture into a VideoFrame. That same frame is submitted to VideoSource and,
 * when attached, to an EglRenderer backed by the app's existing preview Surface. No PixelCopy,
 * Bitmap or JPEG allocation is used on this path.
 */
class WebRtcTextureVideoBridge internal constructor(
    sharedContext: EglBase.Context,
    private val capturerObserver: CapturerObserver,
    width: Int,
    height: Int,
    private val framesPerSecond: Int,
) : Closeable {
    private val lifecycleLock = Any()
    private val textureHelper = checkNotNull(
        SurfaceTextureHelper.create(THREAD_NAME, sharedContext),
    ) { "Unable to create WebRTC SurfaceTextureHelper" }

    val decoderSurface: Surface

    private var previewRenderer: EglRenderer? = null
    private var closed = false

    init {
        textureHelper.setTextureSize(width, height)
        textureHelper.surfaceTexture.setDefaultBufferSize(width, height)
        textureHelper.setFrameRotation(0)
        decoderSurface = Surface(textureHelper.surfaceTexture)

        capturerObserver.onCapturerStarted(true)
        textureHelper.startListening(VideoSink(::onTextureFrame))
    }

    /** Replaces the raw UI preview target while preserving the WebRTC sender. */
    fun attachPreviewSurface(
        surface: Surface,
        sharedContext: EglBase.Context,
    ) {
        require(surface.isValid) { "preview Surface is invalid" }
        val replacement = EglRenderer(PREVIEW_RENDERER_NAME).apply {
            init(sharedContext, EglBase.CONFIG_PLAIN, GlRectDrawer())
            setFpsReduction(framesPerSecond.toFloat())
            createEglSurface(surface)
        }
        val previous = synchronized(lifecycleLock) {
            check(!closed) { "WebRTC texture bridge is closed" }
            previewRenderer.also { previewRenderer = replacement }
        }
        previous?.release()
    }

    fun detachPreviewSurface() {
        val previous = synchronized(lifecycleLock) {
            previewRenderer.also { previewRenderer = null }
        }
        previous?.release()
    }

    private fun onTextureFrame(frame: VideoFrame) {
        capturerObserver.onFrameCaptured(frame)
        synchronized(lifecycleLock) { previewRenderer }?.onFrame(frame)
    }

    override fun close() {
        val renderer = synchronized(lifecycleLock) {
            if (closed) return
            closed = true
            previewRenderer.also { previewRenderer = null }
        }
        textureHelper.stopListening()
        capturerObserver.onCapturerStopped()
        renderer?.release()
        decoderSurface.release()
        textureHelper.dispose()
    }

    private companion object {
        const val THREAD_NAME = "TecomWebRtcTexture"
        const val PREVIEW_RENDERER_NAME = "TecomWebRtcPreview"
    }
}
