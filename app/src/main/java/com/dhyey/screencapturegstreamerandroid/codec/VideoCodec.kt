package com.dhyey.screencapturegstreamerandroid.codec

import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.util.Log

enum class VideoCodec(val mime: String, val gstParser: String) {
    AVC(MediaFormat.MIMETYPE_VIDEO_AVC, "h264parse"),
    HEVC(MediaFormat.MIMETYPE_VIDEO_HEVC, "h265parse"),
    AV1(MediaFormat.MIMETYPE_VIDEO_AV1, "av1parse"); // Experimental, requires modern GStreamer

    /**
     * Screen UI (sharp icons/text) benefits less from HEVC efficiency than camera
     * video. A large discount makes QTI HEVC start muddy for a few seconds under VBR.
     */
    val bitrateMultiplier: Double
        get() = when (this) {
            AVC -> 1.0
            HEVC -> 0.9
            AV1 -> 0.75
        }
}

enum class RecordingProfile {
    COMPATIBILITY, // Strict H.264, Baseline/Main, lower bitrate
    BALANCED,      // H.264 High Profile, decent bitrate, good latency
    HIGH_QUALITY,  // H.265 if available, high bitrate
    LOW_LATENCY,   // Prioritizes speed over quality, drops frames if needed
    VISUALLY_LOSSLESS // Maxed out bitrate, HEVC/AV1 preferred
}

object CodecManager {
    private const val TAG = "CodecManager"

    /**
     * Finds the best hardware encoder for the requested mime type.
     */
    fun findHardwareEncoder(mimeType: String): MediaCodecInfo? {
        val codecList = MediaCodecList(MediaCodecList.ALL_CODECS)
        val candidates = codecList.codecInfos.filter { info ->
            if (!info.isEncoder) return@filter false
            if (!info.supportedTypes.contains(mimeType)) return@filter false
            isHardwareEncoder(info)
        }

        // Prefer Codec2 hardware encoders when present; works across QTI / Exynos / MTK / Tensor.
        val preferred = candidates.firstOrNull { it.name.lowercase().startsWith("c2.") }
            ?: candidates.firstOrNull()

        if (preferred != null) {
            Log.i(TAG, "Found hardware encoder for $mimeType: ${preferred.name}")
        }
        return preferred
    }

    private fun isHardwareEncoder(info: MediaCodecInfo): Boolean {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            return info.isHardwareAccelerated
        }
        val name = info.name.lowercase()
        // Exclude known software encoder namespaces across vendors.
        return !name.startsWith("omx.google.") &&
            !name.startsWith("c2.android.") &&
            !name.contains("sw") &&
            !name.contains("software")
    }

    /**
     * Determines the optimal codec based on the requested profile.
     * Fallbacks to AVC if HEVC/AV1 hardware encoders are unavailable.
     */
    fun selectOptimalCodec(profile: RecordingProfile): VideoCodec {
        if (profile == RecordingProfile.COMPATIBILITY || profile == RecordingProfile.BALANCED) {
            return VideoCodec.AVC
        }

        // Try AV1 first for visually lossless (Experimental)
        if (profile == RecordingProfile.VISUALLY_LOSSLESS) {
            if (findHardwareEncoder(VideoCodec.AV1.mime) != null) {
                return VideoCodec.AV1
            }
        }

        // Try HEVC
        if (findHardwareEncoder(VideoCodec.HEVC.mime) != null) {
            return VideoCodec.HEVC
        }

        // Fallback
        Log.w(TAG, "Requested high-tier codec not available in hardware. Falling back to AVC.")
        return VideoCodec.AVC
    }

    /**
     * Calculates adaptive bitrate based on profile, resolution, fps and codec efficiency.
     */
    fun calculateAdaptiveBitrate(
        w: Int, h: Int, fps: Int, 
        profile: RecordingProfile, 
        codec: VideoCodec
    ): Int {
        val pixelsPerSecond = w.toLong() * h * fps
        
        // Base bits per pixel
        // Slightly conservative bpp keeps GPU/encoder cooler during screen share.
        val baseBpp = when (profile) {
            RecordingProfile.COMPATIBILITY -> 0.08
            RecordingProfile.LOW_LATENCY -> 0.10
            RecordingProfile.BALANCED -> 0.12
            RecordingProfile.HIGH_QUALITY -> 0.20
            RecordingProfile.VISUALLY_LOSSLESS -> 0.35
        }

        // Apply codec efficiency scaling
        val rawBitrate = (pixelsPerSecond * baseBpp * codec.bitrateMultiplier).toInt()

        // Floor scales with resolution so HEVC never opens at a tiny 2 Mbps on 1080p+.
        val minBitrate = when {
            pixelsPerSecond >= 1080L * 2400 * 30 -> 6_000_000
            pixelsPerSecond >= 720L * 1280 * 30 -> 4_000_000
            else -> 2_500_000
        }
        return rawBitrate.coerceIn(minBitrate, 100_000_000)
    }
}
