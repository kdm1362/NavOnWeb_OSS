/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.eigenkodex.navonweb.service

internal enum class ProjectionSurfaceDetachAction {
    KEEP_RUNTIME,
    STOP_RUNTIME,
}

/** Decides whether losing the Activity-owned preview Surface also loses the decoder target. */
internal object ProjectionSurfaceLossPolicy {
    fun decide(
        runtimeUsesNativeWebRtcDecoder: Boolean,
        nativeWebRtcDecoderSurfaceValid: Boolean,
        fallbackDecoderSurfaceValid: Boolean,
    ): ProjectionSurfaceDetachAction {
        val boundDecoderSurfaceValid = if (runtimeUsesNativeWebRtcDecoder) {
            nativeWebRtcDecoderSurfaceValid
        } else {
            fallbackDecoderSurfaceValid
        }
        return if (boundDecoderSurfaceValid) {
            ProjectionSurfaceDetachAction.KEEP_RUNTIME
        } else {
            ProjectionSurfaceDetachAction.STOP_RUNTIME
        }
    }
}
