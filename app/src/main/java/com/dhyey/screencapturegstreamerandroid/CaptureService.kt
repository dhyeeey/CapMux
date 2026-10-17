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
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.dhyey.screencapturegstreamerandroid.audio.AudioCapturePipeline
import com.dhyey.screencapturegstreamerandroid.codec.VideoCodec
import com.dhyey.screencapturegstreamerandroid.nativebridge.NativeMediaRecorder
import com.dhyey.screencapturegstreamerandroid.overlay.ScreenVisualAids
import com.dhyey.screencapturegstreamerandroid.recording.CaptureState
import com.dhyey.screencapturegstreamerandroid.recording.RecordingConfig
import com.dhyey.screencapturegstreamerandroid.video.ScreenCaptureSizing
import com.dhyey.screencapturegstreamerandroid.video.ScreenVideoEncoder
import com.dhyey.screencapturegstreamerandroid.video.calculateScreenBitrate
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class CaptureService : Service() {

    companion object {
        private const val TAG = "CaptureService"

        const val ACTION_START = "com.dhyey.screencapturegstreamerandroid.START"
        const val ACTION_STOP = "com.dhyey.screencapturegstreamerandroid.STOP"

        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"

        private const val NOTIF_CHANNEL = "capture_channel"
        private const val NOTIF_ID = 1
    }

    private var config: RecordingConfig = RecordingConfig()
    private var projection: MediaProjection? = null
    private var videoEncoder: ScreenVideoEncoder? = null
    private var audioPipeline: AudioCapturePipeline? = null
    private var nativeRecorder: NativeMediaRecorder? = null
    private var visualAids: ScreenVisualAids? = null

    private var mediaStoreUri: Uri? = null
    private var outputPfd: ParcelFileDescriptor? = null

    private var width = 0
    private var height = 0
    private var dpi = 0
    private var fps = 30
    private var activeCodec = VideoCodec.AVC

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                config = RecordingConfig.fromIntent(intent)
                if (!config.isValid()) {
                    Log.e(TAG, "Invalid recording config")
                    stopSelf()
                    return START_NOT_STICKY
                }

                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
                @Suppress("DEPRECATION")
                val resultData = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
                } else {
                    intent.getParcelableExtra(EXTRA_RESULT_DATA)
                }

                startCapture(resultCode, resultData)
            }

            ACTION_STOP -> stopCapture()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        cleanup()
        super.onDestroy()
    }

    private fun startForegroundForConfig() {
        var type = 0
        if (config.needsMediaProjection) {
            type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        }
        if (config.captureMic) {
            type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        }
        if (type == 0) {
            // Should not happen for valid configs; fall back to microphone for safety.
            type = ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        }

        ServiceCompat.startForeground(
            this,
            NOTIF_ID,
            buildNotification("Recording: ${config.summary()}"),
            type,
        )
    }

    private fun getScreenSize(): Pair<Int, Int> {
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = wm.currentWindowMetrics.bounds
            bounds.width() to bounds.height()
        } else {
            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealMetrics(metrics)
            metrics.widthPixels to metrics.heightPixels
        }
    }

    private fun startCapture(resultCode: Int, resultData: Intent?) {
        startForegroundForConfig()

        if (config.needsMediaProjection) {
            if (resultData == null || resultCode != Activity.RESULT_OK) {
                Log.e(TAG, "MediaProjection result missing")
                stopSelf()
                return
            }
            val projectionManager =
                getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            projection = projectionManager.getMediaProjection(resultCode, resultData)
            projection?.registerCallback(
                object : MediaProjection.Callback() {
                    override fun onStop() {
                        Log.i(TAG, "MediaProjection stopped")
                        Handler(Looper.getMainLooper()).post { stopCapture() }
                    }
                },
                Handler(Looper.getMainLooper()),
            )
        }

        if (config.hasVideo) {
            activeCodec = config.quality.resolveVideoCodec()
            val (screenW, screenH) = getScreenSize()
            val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
            @Suppress("DEPRECATION")
            val refresh = wm.defaultDisplay.refreshRate
            val sized = ScreenCaptureSizing.compute(
                screenWidth = screenW,
                screenHeight = screenH,
                densityDpi = resources.displayMetrics.densityDpi,
                refreshRateHz = refresh,
                maxShortEdge = config.quality.resolution.maxShortEdge,
                maxFps = config.quality.fps.fps,
            )
            width = sized.width
            height = sized.height
            dpi = sized.dpi
            fps = sized.fps
            Log.i(
                TAG,
                "Capture ${width}x${height}@${fps}fps dpi=$dpi codec=${activeCodec.name} " +
                    "quality=${config.quality.summary()} " +
                    "(display ${screenW}x${screenH}@${refresh}Hz)",
            )
        }

        if (!openOutputFile()) {
            cleanup()
            stopSelf()
            return
        }

        nativeRecorder = NativeMediaRecorder.create(
            hasVideo = config.hasVideo,
            hasAudio = config.hasAudio,
            width = width,
            height = height,
            fps = fps,
            fd = outputPfd!!.fd,
            videoParser = activeCodec.gstParser,
        )

        if (nativeRecorder == null) {
            Log.e(TAG, "nativeCreate failed")
            cleanup()
            stopSelf()
            return
        }

        try {
            nativeRecorder!!.start()

            if (config.hasVideo) {
                val bitrate = calculateScreenBitrate(
                    width = width,
                    height = height,
                    fps = fps,
                    profile = config.quality.bitrate.profile,
                    codec = activeCodec,
                )
                Log.i(TAG, "Video bitrate=${bitrate / 1_000_000.0} Mbps")
                videoEncoder = ScreenVideoEncoder(
                    width = width,
                    height = height,
                    dpi = dpi,
                    fps = fps,
                    bitrate = bitrate,
                    codec = activeCodec,
                ) { buffer, size, tsUs, flags ->
                    nativeRecorder?.pushVideo(buffer, size, tsUs, flags)
                }
                videoEncoder!!.start(projection!!)

                if (config.effectiveShowTaps) {
                    visualAids = ScreenVisualAids(this).also {
                        it.start(showTaps = true)
                    }
                }
            }

            if (config.hasAudio) {
                if (config.captureInternalAudio && Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                    throw IllegalStateException("Internal audio requires Android 10+")
                }
                audioPipeline = AudioCapturePipeline(
                    captureMic = config.captureMic,
                    captureInternal = config.captureInternalAudio,
                ) { buffer, size, tsUs, flags ->
                    nativeRecorder?.pushAudio(buffer, size, tsUs, flags)
                }
                audioPipeline!!.start(projection)
            }

            CaptureState.activeConfig.value = config
            CaptureState.isCapturing.value = true
            Log.i(TAG, "Capture started: ${config.summary()}")
        } catch (e: Exception) {
            Log.e(TAG, "startCapture failed", e)
            cleanup()
            stopSelf()
        }
    }

    private fun openOutputFile(): Boolean {
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val fileName = "capture_${timestamp}.${config.fileExtension}"

        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, config.mimeType)
            put(MediaStore.MediaColumns.RELATIVE_PATH, config.relativePath)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }

        val collection = if (config.hasVideo) {
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else {
            MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        }

        mediaStoreUri = contentResolver.insert(collection, values)
        if (mediaStoreUri == null) {
            Log.e(TAG, "Failed MediaStore insert")
            return false
        }

        outputPfd = contentResolver.openFileDescriptor(mediaStoreUri!!, "rw")
        if (outputPfd == null) {
            Log.e(TAG, "Failed opening FD")
            return false
        }
        return true
    }

    private fun stopCapture() {
        if (!CaptureState.isCapturing.value) {
            return
        }

        Log.i(TAG, "Stopping capture")
        try {
            visualAids?.stop()
        } catch (_: Exception) {
        }
        visualAids = null
        videoEncoder?.signalEndOfStream()
        audioPipeline?.stop()
        audioPipeline = null
        nativeRecorder?.stop()

        Handler(Looper.getMainLooper()).postDelayed({
            cleanup()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }, 3000)
    }

    private fun cleanup() {
        try {
            visualAids?.stop()
        } catch (_: Exception) {
        }
        visualAids = null

        try {
            audioPipeline?.stop()
        } catch (_: Exception) {
        }
        audioPipeline = null

        try {
            videoEncoder?.release()
        } catch (_: Exception) {
        }
        videoEncoder = null

        try {
            projection?.stop()
        } catch (_: Exception) {
        }
        projection = null

        nativeRecorder?.destroy()
        nativeRecorder = null

        try {
            outputPfd?.close()
        } catch (_: Exception) {
        }
        outputPfd = null

        mediaStoreUri?.let { uri ->
            try {
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.IS_PENDING, 0)
                }
                contentResolver.update(uri, values, null, null)
            } catch (_: Exception) {
            }
        }
        mediaStoreUri = null

        CaptureState.isCapturing.value = false
        CaptureState.activeConfig.value = null
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIF_CHANNEL,
                "CapMux",
                NotificationManager.IMPORTANCE_LOW,
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String): Notification {
        val stopIntent = PendingIntent.getService(
            this,
            0,
            Intent(this, CaptureService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        return NotificationCompat.Builder(this, NOTIF_CHANNEL)
            .setContentTitle("CapMux")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_media_pause, "Stop", stopIntent)
            .build()
    }
}
