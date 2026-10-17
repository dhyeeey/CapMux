package com.dhyey.screencapturegstreamerandroid.recording

import android.content.Context
import android.os.Build
import android.util.DisplayMetrics
import android.view.WindowManager
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Real display bounds + refresh rates for the default screen.
 * Used to hide resolution / fps chips the panel cannot do.
 */
data class DisplayCapabilities(
    val widthPx: Int,
    val heightPx: Int,
    val maxRefreshHz: Float,
    val supportedRefreshRates: List<Float>,
) {
    val shortEdge: Int get() = min(widthPx, heightPx)
    val longEdge: Int get() = max(widthPx, heightPx)

    val sizeLabel: String get() = "${widthPx}x${heightPx}"

    val refreshLabel: String
        get() {
            val maxHz = maxRefreshHz.roundToInt()
            return if (supportedRefreshRates.size <= 1) {
                "$maxHz Hz"
            } else {
                val rates = supportedRefreshRates
                    .map { it.roundToInt() }
                    .distinct()
                    .sorted()
                "${rates.joinToString("/")} Hz (max $maxHz)"
            }
        }

    /** Resolution chips that make sense on this panel (always includes Native). */
    fun availableResolutions(): List<CaptureResolution> {
        return CaptureResolution.entries.filter { opt ->
            when (opt) {
                CaptureResolution.NATIVE -> true
                else -> {
                    val target = opt.maxShortEdge ?: return@filter false
                    // Only offer a tier if the panel is at least that sharp
                    // (or slightly below so 1080p still shows on 1080-wide phones).
                    shortEdge >= target - 16
                }
            }
        }
    }

    /** Frame-rate chips ≤ max panel refresh. */
    fun availableFpsOptions(): List<CaptureFps> {
        val maxHz = maxRefreshHz
        return CaptureFps.entries.filter { it.fps <= maxHz + 0.5f }
    }

    fun clampResolution(resolution: CaptureResolution): CaptureResolution {
        val available = availableResolutions()
        if (resolution in available) return resolution
        // Prefer highest named tier the device can do, else Native.
        return available
            .filter { it != CaptureResolution.NATIVE }
            .maxByOrNull { it.maxShortEdge ?: 0 }
            ?: CaptureResolution.NATIVE
    }

    fun clampFps(fps: CaptureFps): CaptureFps {
        val available = availableFpsOptions()
        if (fps in available) return fps
        return available.maxByOrNull { it.fps } ?: CaptureFps.FPS_30
    }

    /** Highest discrete fps option at or below [prefer], within device limits. */
    fun bestFpsAtMost(prefer: Int): CaptureFps {
        val available = availableFpsOptions()
        return available.filter { it.fps <= prefer }.maxByOrNull { it.fps }
            ?: available.minByOrNull { it.fps }
            ?: CaptureFps.FPS_30
    }

    fun maxFpsOption(): CaptureFps = availableFpsOptions().maxByOrNull { it.fps } ?: CaptureFps.FPS_30

    companion object {
        fun from(context: Context): DisplayCapabilities {
            val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val (w, h) = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val bounds = wm.maximumWindowMetrics.bounds
                bounds.width() to bounds.height()
            } else {
                val metrics = DisplayMetrics()
                @Suppress("DEPRECATION")
                wm.defaultDisplay.getRealMetrics(metrics)
                metrics.widthPixels to metrics.heightPixels
            }

            @Suppress("DEPRECATION")
            val display = wm.defaultDisplay
            val rates = linkedSetOf<Float>()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                for (mode in display.supportedModes) {
                    rates.add(mode.refreshRate)
                }
            }
            @Suppress("DEPRECATION")
            rates.add(display.refreshRate)

            val sorted = rates.sorted()
            val maxHz = sorted.maxOrNull() ?: 60f
            return DisplayCapabilities(
                widthPx = w,
                heightPx = h,
                maxRefreshHz = maxHz,
                supportedRefreshRates = sorted,
            )
        }
    }
}
