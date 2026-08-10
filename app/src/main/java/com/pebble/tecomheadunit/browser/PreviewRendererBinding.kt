/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.pebble.tecomheadunit.browser

internal enum class PreviewRendererAttachResult {
    ATTACHED,
    ALREADY_ATTACHED,
    STALE_IGNORED,
}

/**
 * Owns exactly one preview renderer and preserves the generation-to-target binding after detach.
 * All create/use/release operations are serialized so two native window producers never overlap.
 */
internal class PreviewRendererBinding<Target : Any, Renderer : Any>(
    private val sameTarget: (Target, Target) -> Boolean,
    private val releaseRenderer: (Renderer) -> Unit,
) {
    private val lock = Any()
    private var generation = NO_GENERATION
    private var target: Target? = null
    private var renderer: Renderer? = null
    private var closed = false

    fun attach(
        requestedTarget: Target,
        requestedGeneration: Long,
        createRenderer: (Target) -> Renderer,
    ): PreviewRendererAttachResult = synchronized(lock) {
        check(!closed) { "preview renderer binding is closed" }
        require(requestedGeneration >= 0L) { "invalid preview Surface generation" }
        if (requestedGeneration < generation) {
            return@synchronized PreviewRendererAttachResult.STALE_IGNORED
        }
        if (requestedGeneration == generation) {
            target?.let { boundTarget ->
                check(sameTarget(boundTarget, requestedTarget)) {
                    "preview Surface changed without a newer generation"
                }
            }
            if (renderer != null) {
                return@synchronized PreviewRendererAttachResult.ALREADY_ATTACHED
            }
        }

        // Preserve the new binding tombstone even when renderer initialization fails. This keeps
        // stale generations and same-generation target substitution fail-closed.
        val previous = renderer
        renderer = null
        generation = requestedGeneration
        target = requestedTarget
        previous?.let(releaseRenderer)
        renderer = createRenderer(requestedTarget)
        PreviewRendererAttachResult.ATTACHED
    }

    /** Exact Activity detach passes a generation; owner switching passes null unconditionally. */
    fun detach(expectedGeneration: Long? = null): Boolean = synchronized(lock) {
        if (expectedGeneration != null && expectedGeneration != generation) {
            return@synchronized false
        }
        val previous = renderer ?: return@synchronized false
        renderer = null
        releaseRenderer(previous)
        true
    }

    fun dispatch(block: (Renderer) -> Unit) {
        synchronized(lock) { renderer?.let(block) }
    }

    fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            renderer?.let(releaseRenderer)
            renderer = null
        }
    }

    private companion object {
        const val NO_GENERATION = -1L
    }
}

/** Releases a partially initialized native renderer before propagating its initialization error. */
internal fun <Renderer : Any> createInitializedPreviewRenderer(
    allocate: () -> Renderer,
    initialize: (Renderer) -> Unit,
    release: (Renderer) -> Unit,
): Renderer {
    val candidate = allocate()
    try {
        initialize(candidate)
        return candidate
    } catch (error: Throwable) {
        runCatching { release(candidate) }
        throw error
    }
}
