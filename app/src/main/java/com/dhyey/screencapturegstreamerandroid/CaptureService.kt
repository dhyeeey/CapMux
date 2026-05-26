package com.dhyey.screencapturegstreamerandroid

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import android.os.Process
import android.provider.MediaStore
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import com.dhyey.screencapturegstreamerandroid.codec.CodecManager
import com.dhyey.screencapturegstreamerandroid.codec.RecordingProfile
import com.dhyey.screencapturegstreamerandroid.codec.VideoCodec
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

class CaptureService : Service() {

    companion object {
        const val TAG = "CaptureService"

        // Intent actions
        const val ACTION_START = "com.example.screencap.START"
        const val ACTION_STOP  = "com.example.screencap.STOP"

        // Intent extras
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"

        // Notification
        private const val NOTIF_CHANNEL = "screencap_channel"
        private const val NOTIF_ID      = 1

        init {
            System.loadLibrary("screencapturegstreamerandroid")
        }
    }

    // ── state ─────────────────────────────────────────────────────────────────

    private var projection: MediaProjection?  = null
    private var virtualDisplay: VirtualDisplay? = null
    private var mediaCodec: MediaCodec?       = null
    private var nativeHandle: Long            = 0L

    // Actual capture dimensions (resolved at runtime)
    private var capWidth:  Int = 0
    private var capHeight: Int = 0
    private var capDpi:    Int = 0
    private var baseBitrate: Int = 0

    // MediaStore tracking
    private var mediaStoreUri: Uri?                  = null
    private var outputPfd:     ParcelFileDescriptor? = null

    // Threading
    private var encoderThread: HandlerThread? = null
    private var encoderHandler: Handler? = null
    private val isEncoding = AtomicBoolean(false)

    // Thermal
    private var powerManager: PowerManager? = null
    private var thermalListener: PowerManager.OnThermalStatusChangedListener? = null

    // Configuration
    private var currentProfile = RecordingProfile.HIGH_QUALITY
    private lateinit var activeCodec: VideoCodec

    // ── lifecycle ─────────────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val code = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
                @Suppress("DEPRECATION")
                val data = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                    intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)!!
                else
                    intent.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)!!
                startCapture(code, data)
            }
            ACTION_STOP -> stopCapture()
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        cleanupResources()
        super.onDestroy()
    }

    // ── screen metrics ────────────────────────────────────────────────────────

    private fun getScreenSize(): Pair<Int, Int> {
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = wm.currentWindowMetrics.bounds
            bounds.width() to bounds.height()
        } else {
            val dm = DisplayMetrics()
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealMetrics(dm)
            dm.widthPixels to dm.heightPixels
        }
    }

    // ── start ─────────────────────────────────────────────────────────────────

    private fun startCapture(resultCode: Int, data: Intent) {
        startForeground(NOTIF_ID, buildNotification("Capturing screen…"))

        val (w, h) = getScreenSize()
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        
        val displayFps = wm.defaultDisplay.refreshRate.toInt()
        val fps = if (displayFps > 60) 60 else displayFps

        // Ensure width and height are multiples of 2 for MediaCodec
        capWidth  = w and 1.inv()
        capHeight = h and 1.inv()
        capDpi    = resources.displayMetrics.densityDpi

        activeCodec = CodecManager.selectOptimalCodec(currentProfile)
        baseBitrate = CodecManager.calculateAdaptiveBitrate(capWidth, capHeight, fps, currentProfile, activeCodec)

        Log.i(TAG, "Screen: ${capWidth}x${capHeight} @ ${capDpi} dpi, display=${displayFps}Hz, encoder=${fps}fps")
        Log.i(TAG, "Selected Codec: ${activeCodec.name} (${activeCodec.mime}), Bitrate: ${baseBitrate / 1_000_000} Mbps")

        val ts       = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val fileName = "capture_${activeCodec.name}_$ts.ts"
        val values   = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, fileName)
            put(MediaStore.Video.Media.MIME_TYPE,    "video/mp2t")
            put(MediaStore.Video.Media.RELATIVE_PATH,
                "${Environment.DIRECTORY_MOVIES}/ScreenCap")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        mediaStoreUri = contentResolver.insert(
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
            values
        )!!
        outputPfd = contentResolver.openFileDescriptor(mediaStoreUri!!, "w")!!
        Log.i(TAG, "Output → Movies/ScreenCap/$fileName  (fd=${outputPfd!!.fd})")

        nativeHandle = nativeCreate(capWidth, capHeight, fps, outputPfd!!.fd, baseBitrate, activeCodec.gstParser)

        if (nativeHandle == 0L) {
            Log.e(TAG, "Native pipeline creation failed")
            finalizeMediaStore(success = false)
            stopSelf(); return
        }

        // Setup MediaCodec
        val format = MediaFormat.createVideoFormat(activeCodec.mime, capWidth, capHeight).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, baseBitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)

            // Tuning for realtime
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                setInteger(MediaFormat.KEY_LATENCY, 1) // Request low latency
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                setInteger(MediaFormat.KEY_PRIORITY, 0) // Realtime priority
            }
            setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)

            // Optional optimizations
            if (activeCodec == VideoCodec.AVC && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileHigh)
                setInteger(MediaFormat.KEY_LEVEL, MediaCodecInfo.CodecProfileLevel.AVCLevel41)
            } else if (activeCodec == VideoCodec.HEVC && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.HEVCProfileMain)
            }
        }
        
        try {
            val encoderInfo = CodecManager.findHardwareEncoder(activeCodec.mime)
                ?: throw RuntimeException("No hardware encoder found for ${activeCodec.mime}")
            
            mediaCodec = MediaCodec.createByCodecName(encoderInfo.name)
            mediaCodec!!.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            val inputSurface = mediaCodec!!.createInputSurface()
            mediaCodec!!.start()

            // Thermal setup
            setupThermalListener()

            // MediaProjection
            val mgr = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            projection = mgr.getMediaProjection(resultCode, data)

            projection!!.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    Log.i(TAG, "MediaProjection stopped by system")
                    Handler(Looper.getMainLooper()).post { stopCapture() }
                }
            }, Handler(Looper.getMainLooper()))

            // VirtualDisplay
            virtualDisplay = projection!!.createVirtualDisplay(
                "ScreenCap",
                capWidth, capHeight, capDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                inputSurface,
                null, null
            )

            // Start encoder loop thread
            startEncoderThread()

            // Start GStreamer pipeline
            nativeStart(nativeHandle)
            Log.i(TAG, "Capture started at ${capWidth}x${capHeight}")

            CaptureState.isCapturing.value = true

        } catch (e: Exception) {
            Log.e(TAG, "Failed to start capture", e)
            cleanupResources()
            stopSelf()
        }
    }

    private fun setupThermalListener() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            thermalListener = PowerManager.OnThermalStatusChangedListener { status ->
                if (!isEncoding.get() || mediaCodec == null) return@OnThermalStatusChangedListener

                val newBitrate = when (status) {
                    PowerManager.THERMAL_STATUS_SEVERE, PowerManager.THERMAL_STATUS_CRITICAL -> 
                        (baseBitrate * 0.5).toInt()
                    PowerManager.THERMAL_STATUS_MODERATE -> 
                        (baseBitrate * 0.8).toInt()
                    else -> baseBitrate
                }

                try {
                    val params = Bundle().apply {
                        putInt(MediaCodec.PARAMETER_KEY_VIDEO_BITRATE, newBitrate)
                    }
                    mediaCodec!!.setParameters(params)
                    Log.w(TAG, "Thermal status changed to $status. Adjusted bitrate to ${newBitrate / 1_000_000} Mbps")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to adjust bitrate for thermal status", e)
                }
            }
            powerManager?.addThermalStatusListener(thermalListener!!)
        }
    }

    private fun startEncoderThread() {
        isEncoding.set(true)
        
        encoderThread = HandlerThread("EncoderThread", Process.THREAD_PRIORITY_VIDEO)
        encoderThread!!.start()
        encoderHandler = Handler(encoderThread!!.looper)

        encoderHandler!!.post(object : Runnable {
            val bufferInfo = MediaCodec.BufferInfo()
            val timeoutUs = 10000L

            override fun run() {
                if (!isEncoding.get() || mediaCodec == null) return

                try {
                    var outputBufferIndex = mediaCodec!!.dequeueOutputBuffer(bufferInfo, timeoutUs)
                    while (outputBufferIndex >= 0) {
                        val outputBuffer = mediaCodec!!.getOutputBuffer(outputBufferIndex)
                        if (outputBuffer != null && bufferInfo.size > 0) {
                            outputBuffer.position(bufferInfo.offset)
                            outputBuffer.limit(bufferInfo.offset + bufferInfo.size)
                            
                            nativePushEncodedFrame(
                                nativeHandle,
                                outputBuffer,
                                bufferInfo.size,
                                bufferInfo.presentationTimeUs,
                                bufferInfo.flags
                            )
                        }
                        mediaCodec!!.releaseOutputBuffer(outputBufferIndex, false)
                        
                        if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            Log.i(TAG, "Encoder reached EOS")
                            return
                        }
                        
                        // Check if there are more buffers to process immediately
                        outputBufferIndex = mediaCodec!!.dequeueOutputBuffer(bufferInfo, 0)
                    }
                    
                    // Re-post to keep the loop going
                    if (isEncoding.get()) {
                        encoderHandler!!.post(this)
                    }
                } catch (e: Exception) {
                    if (isEncoding.get()) Log.e(TAG, "Encoder error", e)
                }
            }
        })
    }


    // ── stop ──────────────────────────────────────────────────────────────────

    private fun stopCapture() {
        if (!CaptureState.isCapturing.value) return
        Log.i(TAG, "Stopping capture")
        updateNotification("Saving file…")

        isEncoding.set(false)
        try {
            mediaCodec?.signalEndOfInputStream()
        } catch (e: Exception) {
            Log.w(TAG, "Error signaling EOS", e)
        }

        if (nativeHandle != 0L) nativeStop(nativeHandle)

        Handler(Looper.getMainLooper()).postDelayed({
            cleanupResources()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }, 3_000)
    }

    // ── cleanup ───────────────────────────────────────────────────────────────

    private fun cleanupResources() {
        isEncoding.set(false)
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && thermalListener != null) {
            powerManager?.removeThermalStatusListener(thermalListener!!)
            thermalListener = null
        }

        encoderThread?.quitSafely()
        encoderThread = null
        encoderHandler = null

        virtualDisplay?.release();  virtualDisplay = null
        try {
            mediaCodec?.stop()
            mediaCodec?.release()
        } catch (e: Exception) {}
        mediaCodec = null

        projection?.stop();         projection     = null

        if (nativeHandle != 0L) {
            nativeDestroy(nativeHandle)
            nativeHandle = 0L
        }

        finalizeMediaStore(success = true)

        CaptureState.isCapturing.value = false
        Log.i(TAG, "Capture cleaned up — recording visible in Gallery")
    }

    private fun finalizeMediaStore(success: Boolean) {
        try { outputPfd?.close() } catch (e: Exception) { Log.w(TAG, "pfd close: $e") }
        outputPfd = null

        mediaStoreUri?.let { uri ->
            if (success) {
                val values = ContentValues().apply {
                    put(MediaStore.Video.Media.IS_PENDING, 0)
                }
                contentResolver.update(uri, values, null, null)
                Log.i(TAG, "Published to gallery ✓")
            } else {
                contentResolver.delete(uri, null, null)
                Log.w(TAG, "Deleted incomplete MediaStore entry")
            }
        }
        mediaStoreUri = null
    }

    // ── notification ──────────────────────────────────────────────────────────

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                NOTIF_CHANNEL, "Screen Capture",
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }
    }

    private fun buildNotification(text: String): Notification {
        val stopIntent = PendingIntent.getService(
            this, 0,
            Intent(this, CaptureService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, NOTIF_CHANNEL)
            .setContentTitle("Screen Capture")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .addAction(android.R.drawable.ic_media_pause, "Stop", stopIntent)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification(text))
    }

    // ── native declarations ───────────────────────────────────────────────────

    private external fun nativeCreate(w: Int, h: Int, fps: Int, outFd: Int, bitrateBps: Int, gstParser: String): Long
    private external fun nativeStart(handle: Long)
    private external fun nativePushEncodedFrame(
        handle: Long, buf: java.nio.ByteBuffer, size: Int, tsUs: Long, flags: Int)
    private external fun nativeStop(handle: Long)
    private external fun nativeDestroy(handle: Long)
}