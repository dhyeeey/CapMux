package com.dhyey.screencapturegstreamerandroid.recording

import android.content.Intent
import android.os.Environment

data class RecordingConfig(
    val captureScreen: Boolean = true,
    val captureMic: Boolean = false,
    val captureInternalAudio: Boolean = false,
    val showTaps: Boolean = false,
    val quality: CaptureQuality = CaptureQuality(),
) {
    val hasVideo: Boolean get() = captureScreen
    val hasAudio: Boolean get() = captureMic || captureInternalAudio
    val needsMediaProjection: Boolean get() = captureScreen || captureInternalAudio
    val needsMicPermission: Boolean get() = captureMic

    /** Effective visual aids — only when screen is being recorded. */
    val effectiveShowTaps: Boolean get() = captureScreen && showTaps

    /** Overlay used so tap dots are included in the MediaProjection capture. */
    val needsOverlayPermission: Boolean get() = effectiveShowTaps

    /** Optional path: system show_touches when the OEM allows it. */
    val needsWriteSettingsPermission: Boolean get() = effectiveShowTaps

    fun isValid(): Boolean = captureScreen || captureMic || captureInternalAudio

    val mimeType: String
        get() = if (hasVideo) "video/mp4" else "audio/mp4"

    val fileExtension: String
        get() = if (hasVideo) "mp4" else "m4a"

    val relativePath: String
        get() = if (hasVideo) {
            "${Environment.DIRECTORY_MOVIES}/ScreenCap"
        } else {
            "${Environment.DIRECTORY_MUSIC}/ScreenCap"
        }

    fun summary(): String = buildList {
        if (captureScreen) {
            add("Screen")
            add(quality.summary())
        }
        if (captureMic) add("Mic")
        if (captureInternalAudio) add("Media sound")
    }.joinToString(" · ").ifEmpty { "None" }

    fun writeToIntent(intent: Intent) {
        intent.putExtra(EXTRA_CAPTURE_SCREEN, captureScreen)
        intent.putExtra(EXTRA_CAPTURE_MIC, captureMic)
        intent.putExtra(EXTRA_CAPTURE_INTERNAL, captureInternalAudio)
        intent.putExtra(EXTRA_SHOW_TAPS, showTaps)
        intent.putExtra(EXTRA_QUALITY_PRESET, quality.preset.name)
        intent.putExtra(EXTRA_RESOLUTION, quality.resolution.name)
        intent.putExtra(EXTRA_FPS, quality.fps.name)
        intent.putExtra(EXTRA_BITRATE, quality.bitrate.name)
        intent.putExtra(EXTRA_CODEC, quality.codec.name)
    }

    companion object {
        const val EXTRA_CAPTURE_SCREEN = "capture_screen"
        const val EXTRA_CAPTURE_MIC = "capture_mic"
        const val EXTRA_CAPTURE_INTERNAL = "capture_internal"
        const val EXTRA_SHOW_TAPS = "show_taps"
        const val EXTRA_QUALITY_PRESET = "quality_preset"
        const val EXTRA_RESOLUTION = "quality_resolution"
        const val EXTRA_FPS = "quality_fps"
        const val EXTRA_BITRATE = "quality_bitrate"
        const val EXTRA_CODEC = "quality_codec"

        fun fromIntent(intent: Intent): RecordingConfig {
            val default = CaptureQuality()
            val quality = CaptureQuality(
                preset = enumExtra(intent, EXTRA_QUALITY_PRESET, default.preset),
                resolution = enumExtra(intent, EXTRA_RESOLUTION, default.resolution),
                fps = enumExtra(intent, EXTRA_FPS, default.fps),
                bitrate = enumExtra(intent, EXTRA_BITRATE, default.bitrate),
                codec = enumExtra(intent, EXTRA_CODEC, default.codec),
            )
            return RecordingConfig(
                captureScreen = intent.getBooleanExtra(EXTRA_CAPTURE_SCREEN, true),
                captureMic = intent.getBooleanExtra(EXTRA_CAPTURE_MIC, false),
                captureInternalAudio = intent.getBooleanExtra(EXTRA_CAPTURE_INTERNAL, false),
                showTaps = intent.getBooleanExtra(EXTRA_SHOW_TAPS, false),
                quality = quality,
            )
        }

        private inline fun <reified T : Enum<T>> enumExtra(
            intent: Intent,
            key: String,
            default: T,
        ): T {
            val raw = intent.getStringExtra(key) ?: return default
            return try {
                java.lang.Enum.valueOf(T::class.java, raw)
            } catch (_: Exception) {
                default
            }
        }
    }
}
