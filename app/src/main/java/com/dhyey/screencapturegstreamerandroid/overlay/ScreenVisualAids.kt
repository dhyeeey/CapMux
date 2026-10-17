package com.dhyey.screencapturegstreamerandroid.overlay

import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.util.Log
import android.widget.Toast

/**
 * Screen-recording tap indicators.
 *
 * Preferred order:
 * 1. [TapAccessibilityService] (API 33+) — reliable tap dots in the capture
 * 2. System "show_touches" when the OEM still allows WRITE_SETTINGS
 *
 * Borders are intentionally not drawn by the app: TYPE_APPLICATION_OVERLAY is
 * captured into the recording. Android already shows its own MediaProjection
 * indicator (status-bar chip) outside the recorded frames.
 */
class ScreenVisualAids(private val context: Context) {
    companion object {
        private const val TAG = "ScreenVisualAids"
        /** Hidden from public SDK on newer platforms; value is still honored by the OS. */
        private const val SHOW_TOUCHES = "show_touches"

        fun canWriteSystemSettings(context: Context): Boolean =
            Settings.System.canWrite(context)

        fun isShowTapsEnabled(context: Context): Boolean =
            Settings.System.getInt(
                context.contentResolver,
                SHOW_TOUCHES,
                0,
            ) == 1

        fun isTapServiceEnabled(context: Context): Boolean =
            TapAccessibilityService.isEnabled(context)

        /** True if we can show taps without sending the user to Developer options. */
        fun canAutoShowTaps(context: Context): Boolean {
            if (isShowTapsEnabled(context)) return true
            if (canWriteSystemSettings(context)) return true
            return Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                isTapServiceEnabled(context)
        }

        fun openWriteSettings(context: Context) {
            context.startActivity(
                Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS).apply {
                    data = android.net.Uri.parse("package:${context.packageName}")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                },
            )
        }

        fun openAccessibilitySettings(context: Context) {
            context.startActivity(
                Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                },
            )
        }

        fun openDeveloperOptions(context: Context) {
            try {
                context.startActivity(
                    Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    },
                )
            } catch (_: Exception) {
                context.startActivity(
                    Intent(Settings.ACTION_SETTINGS).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    },
                )
            }
        }
    }

    private var restoredShowTouches: Int? = null
    private var tapsEnabledByUs = false
    private var usedAccessibility = false

    fun start(showTaps: Boolean) {
        if (!showTaps) return
        // Prefer Accessibility dots — SHOW_TOUCHES is often blocked for third-party apps.
        if (enableAccessibilityTaps()) return
        if (enableSystemShowTouches()) return
        toast("Enable Accessibility → CapMux taps, or Developer options → Show taps")
    }

    fun stop() {
        if (usedAccessibility) {
            TapAccessibilityService.setVisualizationActive(false)
            usedAccessibility = false
        }
        restoreSystemShowTouches()
    }

    private fun enableSystemShowTouches(): Boolean {
        if (isShowTapsEnabled(context)) {
            Log.i(TAG, "show_touches already on")
            return true
        }
        if (!canWriteSystemSettings(context)) return false
        return try {
            restoredShowTouches = Settings.System.getInt(
                context.contentResolver,
                SHOW_TOUCHES,
                0,
            )
            Settings.System.putInt(
                context.contentResolver,
                SHOW_TOUCHES,
                1,
            )
            if (!isShowTapsEnabled(context)) {
                Log.w(TAG, "SHOW_TOUCHES write ignored by system")
                restoredShowTouches = null
                return false
            }
            tapsEnabledByUs = true
            Log.i(TAG, "System show_touches enabled")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to enable show_touches", e)
            restoredShowTouches = null
            false
        }
    }

    private fun enableAccessibilityTaps(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return false
        if (!isTapServiceEnabled(context)) return false
        TapAccessibilityService.setVisualizationActive(true)
        usedAccessibility = true
        Log.i(TAG, "Accessibility tap visualization enabled")
        return true
    }

    private fun restoreSystemShowTouches() {
        if (!tapsEnabledByUs) return
        tapsEnabledByUs = false
        val previous = restoredShowTouches ?: 0
        restoredShowTouches = null
        if (!canWriteSystemSettings(context)) return
        try {
            Settings.System.putInt(
                context.contentResolver,
                SHOW_TOUCHES,
                previous,
            )
            Log.i(TAG, "System show_touches restored to $previous")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to restore show_touches", e)
        }
    }

    private fun toast(message: String) {
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            Toast.makeText(context.applicationContext, message, Toast.LENGTH_LONG).show()
        }
    }
}
