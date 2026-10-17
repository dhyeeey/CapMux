package com.dhyey.screencapturegstreamerandroid.overlay

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.TouchInteractionController
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Display
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager
import android.widget.FrameLayout
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executor

/**
 * Draws tap indicators during screen recording without consuming input.
 *
 * On API 33+: briefly receives touches via [TouchInteractionController], draws a
 * dot, then [TouchInteractionController.requestDelegating] so the gesture goes
 * to the underlying app. Dots use a non-touchable overlay so they never steal hits.
 * Prefer [WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY] so MediaProjection
 * includes them in the file (same as other app overlays).
 *
 * Enable this service once in system Accessibility settings when using Show taps.
 */
class TapAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "TapA11y"
        private const val DOT_DP = 28f
        private const val FADE_MS = 350L

        @Volatile
        private var instance: TapAccessibilityService? = null

        fun isEnabled(context: Context): Boolean {
            val am = context.getSystemService(AccessibilityManager::class.java) ?: return false
            val expected = TapAccessibilityService::class.java.name
            return am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
                .any {
                    it.resolveInfo.serviceInfo.packageName == context.packageName &&
                        it.resolveInfo.serviceInfo.name == expected
                }
        }

        fun setVisualizationActive(active: Boolean) {
            instance?.setVisualizationActiveInternal(active)
        }
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val mainExecutor = Executor { command -> mainHandler.post(command) }
    private var overlayRoot: FrameLayout? = null
    private var overlayView: TapDotsView? = null
    private var visualizationActive = false
    private var touchCallback: TouchInteractionController.Callback? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.i(TAG, "Tap accessibility service connected")
        // Keep touch-exploration off until a recording asks for taps.
        applyTouchInterception(false)
    }

    override fun onDestroy() {
        setVisualizationActiveInternal(false)
        instance = null
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    private fun setVisualizationActiveInternal(active: Boolean) {
        mainHandler.post {
            if (active == visualizationActive) return@post
            visualizationActive = active
            if (active) {
                ensureOverlay()
                applyTouchInterception(true)
                registerTouchCallback()
                Log.i(TAG, "Tap visualization ON")
            } else {
                unregisterTouchCallback()
                applyTouchInterception(false)
                removeOverlay()
                Log.i(TAG, "Tap visualization OFF")
            }
        }
    }

    private fun applyTouchInterception(enabled: Boolean) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val info = serviceInfo ?: return
        val flag = AccessibilityServiceInfo.FLAG_REQUEST_TOUCH_EXPLORATION_MODE
        info.flags = if (enabled) {
            info.flags or flag
        } else {
            info.flags and flag.inv()
        }
        setServiceInfo(info)
    }

    private fun registerTouchCallback() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (touchCallback != null) return
        val displayId = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            display?.displayId ?: Display.DEFAULT_DISPLAY
        } else {
            Display.DEFAULT_DISPLAY
        }
        val controller = try {
            getTouchInteractionController(displayId)
        } catch (e: Exception) {
            Log.e(TAG, "TouchInteractionController unavailable", e)
            return
        }
        val callback = object : TouchInteractionController.Callback {
            override fun onMotionEvent(event: MotionEvent) {
                handleMotion(event, controller)
            }

            override fun onStateChanged(state: Int) {
                if (state == TouchInteractionController.STATE_CLEAR) {
                    mainHandler.postDelayed({ overlayView?.clearDots() }, FADE_MS)
                }
            }
        }
        touchCallback = callback
        controller.registerCallback(mainExecutor, callback)
    }

    private fun unregisterTouchCallback() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val callback = touchCallback ?: return
        touchCallback = null
        val displayId = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            display?.displayId ?: Display.DEFAULT_DISPLAY
        } else {
            Display.DEFAULT_DISPLAY
        }
        try {
            getTouchInteractionController(displayId).unregisterCallback(callback)
        } catch (e: Exception) {
            Log.w(TAG, "unregisterCallback failed", e)
        }
    }

    private fun handleMotion(event: MotionEvent, controller: TouchInteractionController) {
        if (event.actionMasked != MotionEvent.ACTION_DOWN &&
            event.actionMasked != MotionEvent.ACTION_POINTER_DOWN
        ) {
            return
        }
        val dots = ArrayList<Pair<Float, Float>>(event.pointerCount)
        for (i in 0 until event.pointerCount) {
            dots.add(event.getX(i) to event.getY(i))
        }
        overlayView?.showDots(dots)
        // Pass the gesture through so the phone keeps working.
        try {
            controller.requestDelegating()
        } catch (e: Exception) {
            Log.w(TAG, "requestDelegating failed", e)
        }
    }

    private fun ensureOverlay() {
        if (overlayRoot != null) return
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val dots = TapDotsView(this)
        val root = FrameLayout(this).apply {
            addView(
                dots,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT,
                ),
            )
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        // APPLICATION_OVERLAY is captured by MediaProjection (desired for demo taps).
        // Fall back to accessibility overlay if draw-over-apps is not granted.
        val type = if (Settings.canDrawOverlays(this) &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
        ) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            type,
            (WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS),
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        try {
            wm.addView(root, params)
            overlayRoot = root
            overlayView = dots
        } catch (e: Exception) {
            Log.e(TAG, "Failed to add tap overlay", e)
        }
    }

    private fun removeOverlay() {
        val root = overlayRoot ?: return
        try {
            (getSystemService(WINDOW_SERVICE) as WindowManager).removeView(root)
        } catch (_: Exception) {
        }
        overlayRoot = null
        overlayView = null
    }

    private class TapDotsView(context: Context) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#E53935")
            style = Paint.Style.FILL
            alpha = 180
        }
        private val radiusPx = DOT_DP * resources.displayMetrics.density
        private val points = ConcurrentHashMap<Int, Pair<Float, Float>>()

        fun showDots(dots: List<Pair<Float, Float>>) {
            points.clear()
            dots.forEachIndexed { index, pair -> points[index] = pair }
            postInvalidateOnAnimation()
        }

        fun clearDots() {
            points.clear()
            postInvalidateOnAnimation()
        }

        override fun onDraw(canvas: Canvas) {
            for (p in points.values) {
                canvas.drawCircle(p.first, p.second, radiusPx, paint)
            }
        }
    }
}
