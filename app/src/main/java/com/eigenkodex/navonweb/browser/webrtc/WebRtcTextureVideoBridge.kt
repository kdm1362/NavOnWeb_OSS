/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.eigenkodex.navonweb.browser.webrtc

import android.view.Surface
import com.eigenkodex.navonweb.browser.PreviewRendererBinding
import com.eigenkodex.navonweb.browser.createInitializedPreviewRenderer
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

    private val previewBinding = PreviewRendererBinding<Surface, EglRenderer>(
        sameTarget = { first, second -> first === second },
        releaseRenderer = EglRenderer::release,
    )
    private var auxiliaryFrameSink: VideoSink? = null
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
        generation: Long,
        sharedContext: EglBase.Context,
    ) {
        require(generation >= 0L) { "invalid preview Surface generation" }
        require(surface.isValid) { "preview Surface is invalid" }
        synchronized(lifecycleLock) {
            check(!closed) { "WebRTC texture bridge is closed" }
        }
        previewBinding.attach(surface, generation) {
            createInitializedPreviewRenderer(
                allocate = { EglRenderer(PREVIEW_RENDERER_NAME) },
                initialize = { candidate ->
                    candidate.init(sharedContext, EglBase.CONFIG_PLAIN, GlRectDrawer())
                    candidate.setFpsReduction(framesPerSecond.toFloat())
                    candidate.createEglSurface(surface)
                },
                release = EglRenderer::release,
            )
        }
    }

    fun detachPreviewSurface(generation: Long? = null) {
        previewBinding.detach(generation)
    }

    /** Installs a service-owned JPEG sink without changing the WebRTC or preview consumers. */
    fun setAuxiliaryFrameSink(sink: VideoSink?) {
        synchronized(lifecycleLock) {
            if (!closed) auxiliaryFrameSink = sink
        }
    }

    private fun onTextureFrame(frame: VideoFrame) {
        capturerObserver.onFrameCaptured(frame)
        previewBinding.dispatch { renderer -> renderer.onFrame(frame) }
        val auxiliarySink = synchronized(lifecycleLock) { auxiliaryFrameSink }
        auxiliarySink?.onFrame(frame)
    }

    override fun close() {
        synchronized(lifecycleLock) {
            if (closed) return
            closed = true
            auxiliaryFrameSink = null
        }
        textureHelper.stopListening()
        capturerObserver.onCapturerStopped()
        previewBinding.close()
        decoderSurface.release()
        textureHelper.dispose()
    }

    private companion object {
        const val THREAD_NAME = "NavOnWebWebRtcTexture"
        const val PREVIEW_RENDERER_NAME = "NavOnWebWebRtcPreview"
    }
}
