package com.dhyey.screencapturegstreamerandroid.recording

import com.dhyey.screencapturegstreamerandroid.codec.CodecManager
import com.dhyey.screencapturegstreamerandroid.codec.RecordingProfile
import com.dhyey.screencapturegstreamerandroid.codec.VideoCodec

/** Named presets — defaults land on [RECOMMENDED] (smooth UI + solid quality). */
enum class QualityPreset(val label: String) {
    SMOOTH("Smooth"),
    RECOMMENDED("Recommended"),
    HIGH("High"),
    MAX("Max"),
    CUSTOM("Custom"),
}

/**
 * Short-edge targets (portrait 1080×2400 → 1080p stays 1080×2400).
 * Higher tiers are only offered when [DisplayCapabilities] can do them.
 */
enum class CaptureResolution(val maxShortEdge: Int?, val label: String) {
    P720(720, "720p"),
    P1080(1080, "1080p"),
    P1440(1440, "1440p"),
    P2160(2160, "4K"),
    NATIVE(null, "Native"),
}

enum class CaptureFps(val fps: Int, val label: String) {
    FPS_24(24, "24"),
    FPS_30(30, "30"),
    FPS_60(60, "60"),
    FPS_90(90, "90"),
    FPS_120(120, "120"),
    FPS_144(144, "144"),
    FPS_165(165, "165"),
    FPS_180(180, "180"),
    FPS_240(240, "240"),
}

enum class BitrateTier(val label: String, val profile: RecordingProfile) {
    LOW("Low", RecordingProfile.COMPATIBILITY),
    NORMAL("Normal", RecordingProfile.BALANCED),
    HIGH("High", RecordingProfile.HIGH_QUALITY),
}

enum class VideoCodecOption(val label: String) {
    H264("H.264"),
    H265("H.265"),
}

/**
 * User-facing capture knobs. Defaults match the lag-friendly recommended preset.
 */
data class CaptureQuality(
    val preset: QualityPreset = QualityPreset.RECOMMENDED,
    val resolution: CaptureResolution = CaptureResolution.P1080,
    val fps: CaptureFps = CaptureFps.FPS_30,
    val bitrate: BitrateTier = BitrateTier.NORMAL,
    val codec: VideoCodecOption = VideoCodecOption.H264,
) {
    fun summary(): String =
        "${resolution.label} · ${fps.fps} fps · ${bitrate.label} · ${codec.label}"

    fun blurb(): String = when (preset) {
        QualityPreset.SMOOTH -> "${resolution.label} · ${fps.fps} fps · lightest on the phone"
        QualityPreset.RECOMMENDED -> "${resolution.label} · ${fps.fps} fps · best balance"
        QualityPreset.HIGH -> "${resolution.label} · ${fps.fps} fps · smoother motion"
        QualityPreset.MAX -> "${resolution.label} · ${fps.fps} fps · sharpest, heaviest"
        QualityPreset.CUSTOM -> "Manual resolution / fps / bitrate / codec"
    }

    fun resolveVideoCodec(): VideoCodec = when (codec) {
        VideoCodecOption.H264 -> VideoCodec.AVC
        VideoCodecOption.H265 -> {
            if (CodecManager.findHardwareEncoder(VideoCodec.HEVC.mime) != null) {
                VideoCodec.HEVC
            } else {
                VideoCodec.AVC
            }
        }
    }

    fun clampedTo(caps: DisplayCapabilities): CaptureQuality = copy(
        resolution = caps.clampResolution(resolution),
        fps = caps.clampFps(fps),
    )

    companion object {
        fun fromPreset(preset: QualityPreset, caps: DisplayCapabilities): CaptureQuality {
            val raw = when (preset) {
                QualityPreset.SMOOTH -> CaptureQuality(
                    preset = QualityPreset.SMOOTH,
                    resolution = CaptureResolution.P720,
                    fps = caps.bestFpsAtMost(30),
                    bitrate = BitrateTier.LOW,
                    codec = VideoCodecOption.H264,
                )
                QualityPreset.RECOMMENDED -> CaptureQuality(
                    preset = QualityPreset.RECOMMENDED,
                    resolution = CaptureResolution.P1080,
                    fps = caps.bestFpsAtMost(30),
                    bitrate = BitrateTier.NORMAL,
                    codec = VideoCodecOption.H264,
                )
                QualityPreset.HIGH -> CaptureQuality(
                    preset = QualityPreset.HIGH,
                    resolution = CaptureResolution.P1080,
                    fps = caps.bestFpsAtMost(60),
                    bitrate = BitrateTier.HIGH,
                    codec = VideoCodecOption.H264,
                )
                QualityPreset.MAX -> CaptureQuality(
                    preset = QualityPreset.MAX,
                    resolution = CaptureResolution.NATIVE,
                    fps = caps.maxFpsOption(),
                    bitrate = BitrateTier.HIGH,
                    codec = VideoCodecOption.H265,
                )
                QualityPreset.CUSTOM -> CaptureQuality(preset = QualityPreset.CUSTOM)
            }
            return raw.clampedTo(caps).copy(preset = preset)
        }

        /** Device-aware default (Recommended, clamped). */
        fun defaultFor(caps: DisplayCapabilities): CaptureQuality =
            fromPreset(QualityPreset.RECOMMENDED, caps)
    }
}
