/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.eigenkodex.navonweb.openauto

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.util.Log
import android.view.Surface
import com.eigenkodex.navonweb.openauto.protocol.AasdkProtocolException
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Low-latency H.264 decoder backed by a caller-owned render Surface.
 *
 * Every MediaCodec call is serialized on one worker. The caller waits only
 * until one access unit has entered the decoder, which preserves AASDK's
 * max_unacked=1 backpressure without running codec calls on the network loop.
 */
internal class MediaCodecVideoSink(
    private val surfaceProvider: () -> Surface?,
) : OpenAutoVideoSink {
    private val lifecycleLock = Any()
    private var activeWorker: DecoderWorker? = null
    private var nextGeneration = 0L

    override fun open(config: OpenAutoConfig): Boolean {
        val surface = surfaceProvider() ?: run {
            Log.w(LOG_TAG, "VIDEO_DECODER_WAITING_FOR_SURFACE")
            throw AasdkSurfaceUnavailableException()
        }
        if (!surface.isValid) {
            Log.w(LOG_TAG, "VIDEO_DECODER_WAITING_FOR_SURFACE")
            throw AasdkSurfaceUnavailableException()
        }

        val worker = synchronized(lifecycleLock) {
            val existing = activeWorker
            if (existing != null) {
                if (!existing.surface.isValid) throw AasdkSurfaceUnavailableException()
                val reusable = existing.accepting.get() &&
                    existing.configured &&
                    existing.surface === surface &&
                    existing.config == config
                if (reusable) return true
                throw AasdkRecoverableDecoderException(
                    AasdkRecoverableDecoderFailure.WORKER_STOPPED,
                )
            }

            val generation = ++nextGeneration
            DecoderWorker(
                generation = generation,
                config = config,
                surface = surface,
                executor = Executors.newSingleThreadExecutor { runnable ->
                    Thread(runnable, "NavOnWebH264Decoder-$generation").apply { isDaemon = true }
                },
            ).also { activeWorker = it }
        }

        val future = try {
            worker.executor.submit { configureDecoder(worker) }
        } catch (error: RejectedExecutionException) {
            failWorker(worker)
            throw AasdkRecoverableDecoderException(
                AasdkRecoverableDecoderFailure.WORKER_STOPPED,
                error,
            )
        }

        try {
            future.get(SETUP_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
        } catch (error: TimeoutException) {
            future.cancel(true)
            Log.e(LOG_TAG, "VIDEO_DECODER_SETUP_FAILED TimeoutException")
            failWorker(worker)
            throw AasdkRecoverableDecoderException(
                AasdkRecoverableDecoderFailure.SETUP_FAILED,
                error,
            )
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            future.cancel(true)
            Log.e(LOG_TAG, "VIDEO_DECODER_SETUP_FAILED InterruptedException")
            failWorker(worker)
            throw AasdkRecoverableDecoderException(
                AasdkRecoverableDecoderFailure.INPUT_INTERRUPTED,
                error,
            )
        } catch (error: java.util.concurrent.CancellationException) {
            Log.e(LOG_TAG, "VIDEO_DECODER_SETUP_FAILED CancellationException")
            failWorker(worker)
            throw AasdkRecoverableDecoderException(
                AasdkRecoverableDecoderFailure.WORKER_STOPPED,
                error,
            )
        } catch (error: ExecutionException) {
            val cause = error.cause ?: error
            Log.e(LOG_TAG, "VIDEO_DECODER_SETUP_FAILED ${safeCauseName(cause)}")
            failWorker(worker)
            throwDecoderFailure(cause, AasdkRecoverableDecoderFailure.SETUP_FAILED)
        }

        val remainsActive = synchronized(lifecycleLock) {
            activeWorker === worker && worker.accepting.get() && worker.configured
        }
        if (!remainsActive) {
            val surfaceWasLost = !worker.surface.isValid
            failWorker(worker)
            if (surfaceWasLost) throw AasdkSurfaceUnavailableException()
            throw AasdkRecoverableDecoderException(
                AasdkRecoverableDecoderFailure.WORKER_STOPPED,
            )
        }
        return true
    }

    override fun onEncodedFrame(frame: EncodedVideoFrame) {
        val future = synchronized(lifecycleLock) {
            val worker = activeWorker
                ?: throw AasdkRecoverableDecoderException(
                    AasdkRecoverableDecoderFailure.WORKER_STOPPED,
                )
            if (!worker.surface.isValid) throw AasdkSurfaceUnavailableException()
            if (!worker.accepting.get() || !worker.configured) {
                throw AasdkRecoverableDecoderException(
                    AasdkRecoverableDecoderFailure.WORKER_STOPPED,
                )
            }
            try {
                worker.executor.submit { queueFrame(worker, frame) }
            } catch (error: RejectedExecutionException) {
                throw AasdkRecoverableDecoderException(
                    AasdkRecoverableDecoderFailure.WORKER_STOPPED,
                    error,
                )
            }
        }

        try {
            future.get(FRAME_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
        } catch (error: TimeoutException) {
            future.cancel(true)
            throw AasdkRecoverableDecoderException(
                AasdkRecoverableDecoderFailure.INPUT_TIMEOUT,
                error,
            )
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            future.cancel(true)
            throw AasdkRecoverableDecoderException(
                AasdkRecoverableDecoderFailure.INPUT_INTERRUPTED,
                error,
            )
        } catch (error: java.util.concurrent.CancellationException) {
            throw AasdkRecoverableDecoderException(
                AasdkRecoverableDecoderFailure.WORKER_STOPPED,
                error,
            )
        } catch (error: ExecutionException) {
            throwDecoderFailure(
                error.cause ?: error,
                AasdkRecoverableDecoderFailure.CODEC_REJECTED,
            )
        }
    }

    override fun stop() {
        val worker = synchronized(lifecycleLock) {
            activeWorker?.also {
                activeWorker = null
                it.accepting.set(false)
            }
        } ?: return
        releaseWorker(worker)
    }

    private fun configureDecoder(worker: DecoderWorker) {
        if (!worker.surface.isValid) throw AasdkSurfaceUnavailableException()
        if (!worker.accepting.get()) {
            throw AasdkRecoverableDecoderException(
                AasdkRecoverableDecoderFailure.WORKER_STOPPED,
            )
        }
        val config = worker.config
        val format = MediaFormat.createVideoFormat(
            MediaFormat.MIMETYPE_VIDEO_AVC,
            config.viewport.width,
            config.viewport.height,
        ).apply {
            setInteger(MediaFormat.KEY_FRAME_RATE, config.fps)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, MAX_INPUT_SIZE_BYTES)
            setInteger(MediaFormat.KEY_PRIORITY, 0)
        }
        val decoderName = MediaCodecList(MediaCodecList.ALL_CODECS).findDecoderForFormat(format)
            ?: throw AasdkDecoderUnavailableException()
        val created = MediaCodec.createByCodecName(decoderName)
        try {
            created.configure(format, worker.surface, null, 0)
            created.start()
        } catch (error: Throwable) {
            runCatching { created.release() }
            throw error
        }

        val published = synchronized(lifecycleLock) {
            if (activeWorker === worker && worker.accepting.get() && worker.surface.isValid) {
                worker.codec = created
                worker.configured = true
                true
            } else {
                false
            }
        }
        if (!published) {
            runCatching { created.stop() }
            runCatching { created.release() }
            if (!worker.surface.isValid) throw AasdkSurfaceUnavailableException()
            throw AasdkRecoverableDecoderException(
                AasdkRecoverableDecoderFailure.WORKER_STOPPED,
            )
        }

        Log.i(
            LOG_TAG,
            "VIDEO_DECODER_CONFIGURED generation=${worker.generation} codec=$decoderName " +
                "size=${config.viewport.width}x${config.viewport.height} fps=${config.fps}",
        )
    }

    private fun queueFrame(worker: DecoderWorker, frame: EncodedVideoFrame) {
        if (!worker.surface.isValid) throw AasdkSurfaceUnavailableException()
        if (!worker.accepting.get()) {
            throw AasdkRecoverableDecoderException(
                AasdkRecoverableDecoderFailure.WORKER_STOPPED,
            )
        }
        val activeCodec = worker.codec
            ?: throw AasdkRecoverableDecoderException(
                AasdkRecoverableDecoderFailure.WORKER_STOPPED,
            )
        drainOutput(worker, activeCodec)
        val inputIndex = activeCodec.dequeueInputBuffer(INPUT_TIMEOUT_MICROS)
        if (inputIndex < 0) {
            throw AasdkRecoverableDecoderException(
                AasdkRecoverableDecoderFailure.INPUT_BUFFER_UNAVAILABLE,
            )
        }
        val input = activeCodec.getInputBuffer(inputIndex)
            ?: throw AasdkRecoverableDecoderException(
                AasdkRecoverableDecoderFailure.INPUT_BUFFER_UNAVAILABLE,
            )
        input.clear()
        if (frame.length > input.remaining()) {
            throw AasdkProtocolException("video access unit exceeds decoder buffer")
        }
        input.put(frame.bytes, frame.offset, frame.length)
        val flags = if (frame.codecConfigOnly) MediaCodec.BUFFER_FLAG_CODEC_CONFIG else 0
        activeCodec.queueInputBuffer(
            inputIndex,
            0,
            frame.length,
            frame.timestampMicros,
            flags,
        )
        drainOutput(worker, activeCodec)
    }

    private fun drainOutput(worker: DecoderWorker, activeCodec: MediaCodec) {
        repeat(MAX_OUTPUTS_PER_DRAIN) {
            when (val outputIndex = activeCodec.dequeueOutputBuffer(worker.outputInfo, 0)) {
                MediaCodec.INFO_TRY_AGAIN_LATER -> return
                MediaCodec.INFO_OUTPUT_FORMAT_CHANGED ->
                    Log.i(LOG_TAG, "VIDEO_DECODER_OUTPUT_FORMAT ${activeCodec.outputFormat}")
                else -> if (outputIndex >= 0) {
                    val terminalFlags = MediaCodec.BUFFER_FLAG_CODEC_CONFIG or
                        MediaCodec.BUFFER_FLAG_END_OF_STREAM
                    val render = worker.outputInfo.flags and terminalFlags == 0
                    activeCodec.releaseOutputBuffer(outputIndex, render)
                    if (render) {
                        worker.renderedFrames += 1
                        if (
                            worker.renderedFrames == 1L ||
                            worker.renderedFrames % RENDER_LOG_INTERVAL == 0L
                        ) {
                            Log.i(LOG_TAG, "VIDEO_OUTPUT_RENDERED frames=${worker.renderedFrames}")
                        }
                    }
                }
            }
        }
    }

    private fun failWorker(worker: DecoderWorker) {
        synchronized(lifecycleLock) {
            if (activeWorker === worker) activeWorker = null
            worker.accepting.set(false)
        }
        releaseWorker(worker)
    }

    private fun releaseWorker(worker: DecoderWorker) {
        if (!worker.releaseStarted.compareAndSet(false, true)) return
        worker.accepting.set(false)
        val releaseFuture = try {
            worker.executor.submit { releaseDecoderOnWorker(worker) }
        } catch (_: RejectedExecutionException) {
            null
        }
        if (releaseFuture != null) {
            runCatching { releaseFuture.get(RELEASE_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS) }
        }
        worker.executor.shutdownNow()
        runCatching {
            worker.executor.awaitTermination(RELEASE_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
        }
    }

    private fun releaseDecoderOnWorker(worker: DecoderWorker) {
        val activeCodec = worker.codec
        worker.codec = null
        worker.configured = false
        if (activeCodec != null) {
            runCatching { activeCodec.stop() }
            runCatching { activeCodec.release() }
            Log.i(
                LOG_TAG,
                "VIDEO_DECODER_RELEASED generation=${worker.generation} " +
                    "rendered=${worker.renderedFrames}",
            )
        }
        worker.renderedFrames = 0L
    }

    private fun safeCauseName(error: Throwable): String {
        var cause = error
        while (cause is ExecutionException && cause.cause != null) cause = cause.cause!!
        return cause.javaClass.simpleName
    }

    private fun throwDecoderFailure(
        cause: Throwable,
        fallback: AasdkRecoverableDecoderFailure,
    ): Nothing = when (cause) {
        is AasdkProtocolException -> throw cause
        is AasdkSurfaceUnavailableException -> throw cause
        is AasdkRecoverableDecoderException -> throw cause
        is AasdkDecoderUnavailableException -> throw cause
        is MediaCodec.CodecException -> {
            if (isRecoverableCodecFailure(cause.isTransient, cause.isRecoverable)) {
                throw AasdkRecoverableDecoderException(fallback, cause)
            }
            throw AasdkDecoderUnavailableException(cause)
        }
        is SecurityException -> throw cause
        is Error -> throw cause
        is IllegalStateException -> throw AasdkRecoverableDecoderException(fallback, cause)
        else -> throw AasdkDecoderUnavailableException(cause)
    }

    private class DecoderWorker(
        val generation: Long,
        val config: OpenAutoConfig,
        val surface: Surface,
        val executor: ExecutorService,
    ) {
        val accepting = AtomicBoolean(true)
        val releaseStarted = AtomicBoolean(false)

        @Volatile
        var configured = false

        // Accessed only by this worker except for the configured publication.
        var codec: MediaCodec? = null
        val outputInfo = MediaCodec.BufferInfo()
        var renderedFrames = 0L
    }

    private companion object {
        const val LOG_TAG = "NavOnWebAasdk"
        const val MAX_INPUT_SIZE_BYTES = 2 * 1024 * 1024
        const val INPUT_TIMEOUT_MICROS = 50_000L
        const val SETUP_TIMEOUT_MILLIS = 2_000L
        const val FRAME_TIMEOUT_MILLIS = 250L
        const val RELEASE_TIMEOUT_MILLIS = 1_000L
        const val MAX_OUTPUTS_PER_DRAIN = 16
        const val RENDER_LOG_INTERVAL = 60L
    }
}
