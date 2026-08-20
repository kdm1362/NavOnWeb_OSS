// NavOnWeb: forces H.264 encode sessions to constrained-baseline.
//
// Why this file lives in package org.webrtc: MediaCodecWrapper and
// MediaCodecWrapperFactory are package-private interfaces of the bundled
// webrtc-sdk (io.github.webrtc-sdk:android 144.7559.09), and they are the
// only seam through which MediaCodec.configure() can be reached.
//
// Why it exists at all: HardwareVideoEncoder.initEncodeInternal() sets
// KEY_PROFILE/KEY_LEVEL ONLY for the "640c1f" (constrained-high) parameter
// set. For the "42e01f" constrained-baseline negotiation this app actually
// performs, it sets NO profile key, so the vendor encoder (c2.qti.avc.encoder
// on the field phone) emits its own default - which is not contractually
// baseline and was observed switching profiles between days. The in-vehicle
// head unit's decoder pipeline negotiated baseline; every non-baseline SPS
// cost a session. This enforcer injects the profile/level at configure time
// and logs the first emitted SPS as on-device proof.
package org.webrtc

import android.media.MediaCodecInfo
import android.media.MediaCrypto
import android.media.MediaFormat
import android.os.Build
import android.util.Log
import android.view.Surface
import java.nio.ByteBuffer

object NavOnWebH264BaselineEnforcer {
    private const val TAG = "NavOnWebWebRtc"
    private const val MIME_H264 = "video/avc"
    // MediaCodecInfo.CodecProfileLevel values; constants spelled numerically
    // so minSdk 26 builds do not reference API-27 symbols.
    private const val AVC_PROFILE_BASELINE = 0x01
    private const val AVC_PROFILE_CONSTRAINED_BASELINE = 0x10000
    private const val AVC_LEVEL_3 = 0x100
    private const val AVC_LEVEL_31 = 0x200
    private const val AVC_LEVEL_32 = 0x400
    private const val AVC_LEVEL_4 = 0x800
    private const val AVC_LEVEL_42 = 0x2000

    /**
     * Swaps the encoder's MediaCodec wrapper factory for one that injects
     * KEY_PROFILE/KEY_LEVEL. Reflection is confined to one private field of
     * the bundled (not platform) SDK, and failure is fail-open: the encoder
     * runs unmodified exactly as before this change, with a loud log line,
     * and the head unit's own gate now tolerates Main/High as a second layer.
     * Returns whether enforcement is armed.
     */
    @JvmStatic
    fun enforce(encoder: VideoEncoder): Boolean {
        val target = unwrap(encoder)
        if (target !is HardwareVideoEncoder) {
            Log.w(TAG, "H264 baseline enforcement skipped: encoder is " +
                target.javaClass.simpleName)
            return false
        }
        return try {
            val field =
                HardwareVideoEncoder::class.java.getDeclaredField("mediaCodecWrapperFactory")
            field.isAccessible = true
            val inner = field.get(target) as MediaCodecWrapperFactory
            if (inner is BaselineWrapperFactory) return true
            field.set(target, BaselineWrapperFactory(inner))
            Log.i(TAG, "H264 baseline enforcement armed (configure-time " +
                "KEY_PROFILE injection)")
            true
        } catch (t: Throwable) {
            Log.w(TAG, "H264 baseline enforcement UNAVAILABLE (SDK layout " +
                "changed?) - vendor default profile will be emitted", t)
            false
        }
    }

    private fun unwrap(encoder: VideoEncoder): VideoEncoder {
        if (encoder !is VideoEncoderFallback) return encoder
        // This SDK build has no software H.264, so fallback wrapping is not
        // expected for H264; handled anyway for SDK-bump safety.
        return try {
            val field = VideoEncoderFallback::class.java.getDeclaredField("primary")
            field.isAccessible = true
            (field.get(encoder) as? VideoEncoder) ?: encoder
        } catch (t: Throwable) {
            encoder
        }
    }

    private class BaselineWrapperFactory(
        private val inner: MediaCodecWrapperFactory,
    ) : MediaCodecWrapperFactory {
        override fun createByCodecName(name: String): MediaCodecWrapper =
            BaselineWrapper(inner.createByCodecName(name))
    }

    private class BaselineWrapper(
        private val inner: MediaCodecWrapper,
    ) : MediaCodecWrapper by inner {
        private var spsReported = false

        override fun configure(
            format: MediaFormat,
            surface: Surface?,
            crypto: MediaCrypto?,
            flags: Int,
        ) {
            val mime = runCatching { format.getString(MediaFormat.KEY_MIME) }.getOrNull()
            if (mime != MIME_H264) {
                inner.configure(format, surface, crypto, flags)
                return
            }
            val levels = runCatching {
                inner.codecInfo.getCapabilitiesForType(MIME_H264).profileLevels
            }.getOrNull()
            val supportsConstrained = Build.VERSION.SDK_INT >= 27 &&
                levels?.any { it.profile == AVC_PROFILE_CONSTRAINED_BASELINE } == true
            val profile =
                if (supportsConstrained) AVC_PROFILE_CONSTRAINED_BASELINE
                else AVC_PROFILE_BASELINE
            // MINIMAL SUFFICIENT level, not maximal: embedded receivers on
            // old decoder silicon commonly cap at 3.0/3.1, and the level_idc
            // the encoder writes into the SPS is what their CheckInputType
            // validates. Compute the H.264 Table A-1 minimum for the
            // configured size and frame rate (frame MBs and MB/s), clamped
            // into what the encoder supports; fall back to the old
            // clamp-to-4.2 when the format carries no geometry.
            val width = runCatching { format.getInteger(MediaFormat.KEY_WIDTH) }.getOrDefault(0)
            val height = runCatching { format.getInteger(MediaFormat.KEY_HEIGHT) }.getOrDefault(0)
            val fps = runCatching { format.getInteger(MediaFormat.KEY_FRAME_RATE) }.getOrDefault(30)
            val mbs = ((width + 15) / 16) * ((height + 15) / 16)
            val mbRate = mbs * (if (fps in 1..120) fps else 30)
            val maxSupported = (levels
                ?.filter { it.profile == profile }
                ?.maxOfOrNull { it.level }
                ?: AVC_LEVEL_42)
            val level = if (mbs <= 0) {
                maxSupported.coerceAtMost(AVC_LEVEL_42)
            } else {
                when {
                    mbs <= 1620 && mbRate <= 40500 -> AVC_LEVEL_3
                    mbs <= 3600 && mbRate <= 108000 -> AVC_LEVEL_31
                    mbs <= 5120 && mbRate <= 216000 -> AVC_LEVEL_32
                    mbs <= 8192 && mbRate <= 245760 -> AVC_LEVEL_4
                    else -> AVC_LEVEL_42
                }.coerceAtMost(maxSupported.coerceAtMost(AVC_LEVEL_42))
            }
            format.setInteger(MediaFormat.KEY_PROFILE, profile)
            format.setInteger(MediaFormat.KEY_LEVEL, level)
            Log.i(TAG, "H264 encoder configure: injected profile=0x" +
                profile.toString(16) + " level=0x" + level.toString(16))
            try {
                inner.configure(format, surface, crypto, flags)
            } catch (e: Exception) {
                // Fail-open must cover the vendor REJECTING the injected keys,
                // or this enforcement would turn into the very no-video
                // failure it exists to fix.  Retry once without them.
                // (removeKey needs API 29; below that the rejection just
                // propagates exactly as an un-enforced configure error would.)
                if (Build.VERSION.SDK_INT < 29) throw e
                Log.w(TAG, "H264 encoder rejected injected profile/level - " +
                    "retrying without them (vendor default profile will be " +
                    "emitted; the head unit accepts 66/77/100)", e)
                format.removeKey(MediaFormat.KEY_PROFILE)
                format.removeKey(MediaFormat.KEY_LEVEL)
                inner.configure(format, surface, crypto, flags)
            }
        }

        override fun getOutputBuffer(index: Int): ByteBuffer? {
            val buffer = inner.getOutputBuffer(index)
            if (!spsReported && buffer != null) {
                reportSps(buffer)
            }
            return buffer
        }

        /** One-shot on-device proof of what the encoder ACTUALLY emits. */
        private fun reportSps(buffer: ByteBuffer) {
            val data = buffer.duplicate()
            val bytes = ByteArray(minOf(data.remaining(), 128))
            data.get(bytes)
            var i = 0
            while (i + 4 < bytes.size) {
                val start3 = bytes[i].toInt() == 0 && bytes[i + 1].toInt() == 0 &&
                    bytes[i + 2].toInt() == 1
                val start4 = i + 5 < bytes.size && bytes[i].toInt() == 0 &&
                    bytes[i + 1].toInt() == 0 && bytes[i + 2].toInt() == 0 &&
                    bytes[i + 3].toInt() == 1
                val nalAt = if (start4) i + 4 else if (start3) i + 3 else -1
                if (nalAt >= 0 && nalAt + 3 < bytes.size) {
                    if ((bytes[nalAt].toInt() and 0x1f) == 7) {
                        spsReported = true
                        Log.i(TAG, "H264 encoder FIRST SPS: profile_idc=" +
                            (bytes[nalAt + 1].toInt() and 0xff) +
                            " constraints=0x" +
                            String.format("%02x", bytes[nalAt + 2].toInt() and 0xff) +
                            " level_idc=" + (bytes[nalAt + 3].toInt() and 0xff))
                        return
                    }
                    i = nalAt
                }
                i++
            }
        }
    }
}
