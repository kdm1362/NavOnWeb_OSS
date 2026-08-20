/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.eigenkodex.navonweb.openauto

import com.eigenkodex.navonweb.openauto.protocol.AasdkProtocolException
import java.io.IOException

/**
 * Local decoder failures that can only be recovered by opening a fresh
 * MediaCodec instance and negotiating a new AASDK session.
 *
 * These failures are deliberately separate from [AasdkProtocolException]: a
 * decoder worker or codec can fail even when the phone supplied a valid AASDK
 * message and a valid H.264 access unit.
 */
internal class AasdkRecoverableDecoderException(
    val failure: AasdkRecoverableDecoderFailure,
    cause: Throwable? = null,
) : IOException("recoverable video decoder failure: ${failure.name}", cause)

internal enum class AasdkRecoverableDecoderFailure {
    SETUP_FAILED,
    WORKER_STOPPED,
    INPUT_TIMEOUT,
    INPUT_INTERRUPTED,
    INPUT_BUFFER_UNAVAILABLE,
    CODEC_REJECTED,
}

/**
 * A missing or invalid render target is reported separately from codec and
 * transport failures. It remains retryable because a caller-owned Surface can
 * be recreated while the projection service stays alive.
 */
internal class AasdkSurfaceUnavailableException(
    cause: Throwable? = null,
) : IOException("video output surface unavailable", cause)

/** A permanent local decoder capability/configuration failure. */
internal class AasdkDecoderUnavailableException(
    cause: Throwable? = null,
) : IllegalStateException("compatible video decoder unavailable", cause)

/**
 * MediaCodec explicitly tells callers whether restarting the same codec may
 * recover an error. Keeping this decision pure makes the retry boundary
 * testable without loading Android framework classes in a JVM unit test.
 */
internal fun isRecoverableCodecFailure(
    isTransient: Boolean,
    isRecoverable: Boolean,
): Boolean = isTransient || isRecoverable
