package com.dhyey.screencapturegstreamerandroid.codec

import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.util.Log

enum class VideoCodec(val mime: String, val gstParser: String) {
    AVC(MediaFormat.MIMETYPE_VIDEO_AVC, "h264parse"),
    HEVC(MediaFormat.MIMETYPE_VIDEO_HEVC, "h265parse"),
    AV1(MediaFormat.MIMETYPE_VIDEO_AV1, "av1parse"); // Experimental, requires modern GStreamer

    // HEVC provides roughly 40% better compression than AVC, AV1 ~50%
    val bitrateMultiplier: Double
        get() = when (this) {
            AVC -> 1.0
            HEVC -> 0.6
            AV1 -> 0.5
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
        for (info in codecList.codecInfos) {
            if (!info.isEncoder) continue
            
            // We ONLY want hardware-accelerated encoders to prevent CPU bottleneck
            val isHardware = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                info.isHardwareAccelerated
            } else {
                // Fallback heuristic for older Android versions
                val name = info.name.lowercase()
                !name.startsWith("omx.google.") && !name.contains("sw") && !name.contains("software")
            }

            if (!isHardware) continue

            if (info.supportedTypes.contains(mimeType)) {
                Log.i(TAG, "Found hardware encoder for $mimeType: ${info.name}")
                return info
            }
        }
        return null
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
        val baseBpp = when (profile) {
            RecordingProfile.COMPATIBILITY -> 0.10
            RecordingProfile.LOW_LATENCY -> 0.12
            RecordingProfile.BALANCED -> 0.15
            RecordingProfile.HIGH_QUALITY -> 0.25
            RecordingProfile.VISUALLY_LOSSLESS -> 0.40
        }

        // Apply codec efficiency scaling
        val rawBitrate = (pixelsPerSecond * baseBpp * codec.bitrateMultiplier).toInt()

        // Clamp to sane limits (2 Mbps to 100 Mbps)
        return rawBitrate.coerceIn(2_000_000, 100_000_000)
    }
}
