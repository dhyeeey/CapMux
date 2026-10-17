package com.dhyey.screencapturegstreamerandroid.video

/**
 * Downscales / rate-limits screen capture from user quality settings.
 *
 * Resolution targets use the **short edge** (mobile “720p / 1080p” convention).
 * On a 1080×2400 phone, 1080p keeps native size; long-edge capping would wrongly
 * shrink it to ~486×1080.
 */
object ScreenCaptureSizing {
    data class CaptureSize(
        val width: Int,
        val height: Int,
        val dpi: Int,
        val fps: Int,
    )

    /**
     * @param maxShortEdge null = keep native resolution; otherwise scale so
     *   min(width, height) ≤ maxShortEdge
     * @param maxFps encode frame-rate cap
     */
    fun compute(
        screenWidth: Int,
        screenHeight: Int,
        densityDpi: Int,
        refreshRateHz: Float,
        maxShortEdge: Int?,
        maxFps: Int,
    ): CaptureSize {
        val (w, h) = if (maxShortEdge == null) {
            even(screenWidth) to even(screenHeight)
        } else {
            scaleToShortEdge(screenWidth, screenHeight, maxShortEdge)
        }
        val scale = if (screenWidth > 0) w.toFloat() / screenWidth.toFloat() else 1f
        val dpi = (densityDpi * scale).toInt().coerceAtLeast(120)
        val refreshCap = refreshRateHz.toInt().coerceAtLeast(1)
        val fps = minOf(refreshCap, maxFps.coerceAtLeast(1))
        return CaptureSize(width = w, height = h, dpi = dpi, fps = fps)
    }

    private fun scaleToShortEdge(width: Int, height: Int, maxShortEdge: Int): Pair<Int, Int> {
        val shortEdge = minOf(width, height)
        if (shortEdge <= maxShortEdge) {
            return even(width) to even(height)
        }
        val scale = maxShortEdge.toFloat() / shortEdge.toFloat()
        return even((width * scale).toInt()) to even((height * scale).toInt())
    }

    /** MediaCodec / H.264 require even dimensions. */
    private fun even(value: Int): Int = value.coerceAtLeast(2) and 1.inv()
}
