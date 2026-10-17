package com.dhyey.screencapturegstreamerandroid.codec

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Build
import android.util.Log

/**
 * Portable MediaCodec video encoder setup.
 *
 * - Always uses the generic `prepend-sps-pps-to-idr-frames` key (multi-OEM / AOSP).
 * - Applies Qualcomm's documented vendor key only when the encoder is QTI/QCOM.
 * - Retries configure with simpler formats so Exynos, MediaTek, Tensor, Kirin,
 *   Unisoc, etc. are not broken by optional keys or High profile.
 *
 * GStreamer `h264parse config-interval=-1` also re-emits SPS/PPS, so recording
 * remains correct even when a SoC ignores prepend hints.
 */
object EncoderFormatHelper {
    private const val TAG = "EncoderFormatHelper"

    private const val KEY_PREPEND_SPS_PPS = "prepend-sps-pps-to-idr-frames"
    private const val KEY_QTI_PREPEND =
        "vendor.qti-ext-enc-prepend-sps-pps-to-idr-frames.value"

    fun configureEncoder(
        codec: MediaCodec,
        mime: String,
        width: Int,
        height: Int,
        bitrate: Int,
        fps: Int,
        encoderName: String,
    ) {
        val attempts = if (mime == MediaFormat.MIMETYPE_VIDEO_HEVC) {
            listOf(
                FormatAttempt(
                    includeOptionalKeys = true,
                    avcProfile = null,
                    hevcProfile = MediaCodecInfo.CodecProfileLevel.HEVCProfileMain,
                    label = "hevc-main+optional",
                ),
                FormatAttempt(
                    includeOptionalKeys = false,
                    avcProfile = null,
                    hevcProfile = null,
                    label = "hevc-minimal",
                ),
            )
        } else {
            listOf(
                FormatAttempt(
                    includeOptionalKeys = true,
                    avcProfile = MediaCodecInfo.CodecProfileLevel.AVCProfileHigh,
                    label = "high+optional",
                ),
                FormatAttempt(
                    includeOptionalKeys = true,
                    avcProfile = MediaCodecInfo.CodecProfileLevel.AVCProfileMain,
                    label = "main+optional",
                ),
                FormatAttempt(
                    includeOptionalKeys = true,
                    avcProfile = MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline,
                    label = "baseline+optional",
                ),
                FormatAttempt(
                    includeOptionalKeys = false,
                    avcProfile = null,
                    label = "minimal",
                ),
            )
        }

        var lastError: Exception? = null
        for (attempt in attempts) {
            val format = createVideoFormat(
                mime = mime,
                width = width,
                height = height,
                bitrate = bitrate,
                fps = fps,
                encoderName = encoderName,
                includeOptionalKeys = attempt.includeOptionalKeys,
                avcProfile = attempt.avcProfile,
                hevcProfile = attempt.hevcProfile,
            )
            try {
                codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                Log.i(TAG, "Configured $encoderName (${attempt.label})")
                return
            } catch (e: Exception) {
                lastError = e
                Log.w(TAG, "configure failed for $encoderName (${attempt.label}): ${e.message}")
            }
        }
        throw lastError ?: RuntimeException("Failed to configure encoder $encoderName")
    }

    private fun createVideoFormat(
        mime: String,
        width: Int,
        height: Int,
        bitrate: Int,
        fps: Int,
        encoderName: String,
        includeOptionalKeys: Boolean,
        avcProfile: Int?,
        hevcProfile: Int? = null,
    ): MediaFormat {
        val isHevc = mime == MediaFormat.MIMETYPE_VIDEO_HEVC
        return MediaFormat.createVideoFormat(mime, width, height).apply {
            setInteger(
                MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface,
            )
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            // HEVC: 1s GOP recovers faster from the muddy VBR startup some SoCs show.
            // AVC: 2s is fine and a bit lighter on the encoder.
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, if (isHevc) 1 else 2)
            setInteger(
                MediaFormat.KEY_BITRATE_MODE,
                MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR,
            )

            // Best-effort (1) so the encoder yields to UI / composition.
            // Realtime (0) is a common cause of "phone feels laggy while recording".
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                setInteger(MediaFormat.KEY_PRIORITY, 1)
            }

            if (!includeOptionalKeys) return@apply

            // Portable multi-OEM key (AOSP screenrecord and most vendors).
            setInteger(KEY_PREPEND_SPS_PPS, 1)

            // Qualcomm-only documented extension — never set on other SoCs.
            val lower = encoderName.lowercase()
            if (lower.contains("qti") || lower.contains("qcom")) {
                setInteger(KEY_QTI_PREPEND, 1)
                Log.i(TAG, "Applied Qualcomm SPS/PPS hint for $encoderName")
            }

            // Do NOT set KEY_LATENCY — that biases the encoder toward ultra-low
            // latency streaming and burns more GPU/power. File capture doesn't need it.

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                when {
                    avcProfile != null && mime == MediaFormat.MIMETYPE_VIDEO_AVC ->
                        setInteger(MediaFormat.KEY_PROFILE, avcProfile)
                    hevcProfile != null && isHevc ->
                        setInteger(MediaFormat.KEY_PROFILE, hevcProfile)
                }
            }
        }
    }

    private data class FormatAttempt(
        val includeOptionalKeys: Boolean,
        val avcProfile: Int?,
        val hevcProfile: Int? = null,
        val label: String,
    )
}
