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
import android.os.Environment
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.ParcelFileDescriptor
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
        private const val TAG = "CaptureService"

        const val ACTION_START =
            "com.dhyey.screencapturegstreamerandroid.START"

        const val ACTION_STOP =
            "com.dhyey.screencapturegstreamerandroid.STOP"

        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"

        private const val NOTIF_CHANNEL = "capture_channel"
        private const val NOTIF_ID = 1

        init {
            System.loadLibrary("screencapturegstreamerandroid")
        }
    }

    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var mediaCodec: MediaCodec? = null

    private var nativeHandle: Long = 0L

    private var mediaStoreUri: Uri? = null
    private var outputPfd: ParcelFileDescriptor? = null

    private var encoderThread: HandlerThread? = null
    private var encoderHandler: Handler? = null

    private val isEncoding =
        AtomicBoolean(false)

    private var width = 0
    private var height = 0
    private var dpi = 0
    private var fps = 60

    // FORCE AVC FIRST
    private var activeCodec =
        VideoCodec.AVC

    private var profile =
        RecordingProfile.BALANCED

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int
    ): Int {

        when (intent?.action) {

            ACTION_START -> {

                val resultCode =
                    intent.getIntExtra(
                        EXTRA_RESULT_CODE,
                        Activity.RESULT_CANCELED
                    )

                @Suppress("DEPRECATION")
                val resultData =
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(
                            EXTRA_RESULT_DATA,
                            Intent::class.java
                        )!!
                    } else {
                        intent.getParcelableExtra<Intent>(
                            EXTRA_RESULT_DATA
                        )!!
                    }

                startCapture(
                    resultCode,
                    resultData
                )
            }

            ACTION_STOP -> {
                stopCapture()
            }
        }

        return START_NOT_STICKY
    }

    override fun onDestroy() {
        cleanup()
        super.onDestroy()
    }

    private fun getScreenSize(): Pair<Int, Int> {

        val wm =
            getSystemService(Context.WINDOW_SERVICE)
                    as WindowManager

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {

            val bounds =
                wm.currentWindowMetrics.bounds

            bounds.width() to bounds.height()

        } else {

            val metrics = DisplayMetrics()

            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealMetrics(metrics)

            metrics.widthPixels to metrics.heightPixels
        }
    }

    private fun startCapture(
        resultCode: Int,
        data: Intent
    ) {

        startForeground(
            NOTIF_ID,
            buildNotification("Recording...")
        )

        val (screenW, screenH) =
            getScreenSize()

        width = screenW and 1.inv()
        height = screenH and 1.inv()

        dpi = resources.displayMetrics.densityDpi

        val wm =
            getSystemService(Context.WINDOW_SERVICE)
                    as WindowManager

        @Suppress("DEPRECATION")
        fps = minOf(
            wm.defaultDisplay.refreshRate.toInt(),
            60
        )

        val bitrate =
            CodecManager.calculateAdaptiveBitrate(
                width,
                height,
                fps,
                profile,
                activeCodec
            )

        Log.i(TAG, "Starting capture ${width}x${height} @ ${fps}fps")

        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())

        val fileName = "capture_${activeCodec.name}_$timestamp.mp4"

        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, fileName)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MOVIES}/ScreenCap")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }

        mediaStoreUri =
            contentResolver.insert(MediaStore.Video.Media.getContentUri(
                    MediaStore.VOLUME_EXTERNAL_PRIMARY
                ), values)

        if (mediaStoreUri == null) {
            Log.e(TAG, "Failed MediaStore insert")
            stopSelf()
            return
        }

        outputPfd = contentResolver.openFileDescriptor(mediaStoreUri!!, "w")

        if (outputPfd == null) {
            Log.e(TAG, "Failed opening FD")
            stopSelf()
            return
        }

        nativeHandle =
            nativeCreate(
                width,
                height,
                fps,
                outputPfd!!.fd,
                bitrate,
                activeCodec.gstParser
            )

        if (nativeHandle == 0L) {
            Log.e(TAG, "nativeCreate failed")
            stopSelf()
            return
        }

        try {

            val encoderInfo =
                CodecManager.findHardwareEncoder(activeCodec.mime)
                    ?: throw RuntimeException("No hardware encoder")

            val format =
                MediaFormat.createVideoFormat(
                    activeCodec.mime,
                    width,
                    height
                ).apply {

                    setInteger(MediaFormat.KEY_COLOR_FORMAT,
                        MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface
                    )
                    setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
                    setInteger(MediaFormat.KEY_FRAME_RATE, fps)
                    setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
                    setInteger(MediaFormat.KEY_BITRATE_MODE,
                        MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR
                    )
                    setInteger("prepend-sps-pps-to-idr-frames", 1)
                    setInteger("vendor.qti-ext-enc-prepend-sps-pps-to-idr-frames.value", 1)

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        setInteger(
                            MediaFormat.KEY_PROFILE,
                            MediaCodecInfo.CodecProfileLevel.AVCProfileHigh
                        )
                    }

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        setInteger(MediaFormat.KEY_LATENCY, 1)
                    }
                }

            mediaCodec =
                MediaCodec.createByCodecName(encoderInfo.name)

            mediaCodec!!.configure(
                format,
                null,
                null,
                MediaCodec.CONFIGURE_FLAG_ENCODE
            )

            val inputSurface = mediaCodec!!.createInputSurface()

            mediaCodec!!.start()

            val projectionManager =
                getSystemService(
                    Context.MEDIA_PROJECTION_SERVICE
                ) as MediaProjectionManager

            projection =
                projectionManager.getMediaProjection(
                    resultCode,
                    data
                )

            // REQUIRED FOR ANDROID 14+
            projection!!.registerCallback(
                object : MediaProjection.Callback() {

                    override fun onStop() {
                        Log.i(
                            TAG,
                            "MediaProjection stopped"
                        )

                        Handler(
                            Looper.getMainLooper()
                        ).post {
                            stopCapture()
                        }
                    }
                },
                Handler(Looper.getMainLooper())
            )

            virtualDisplay =
                projection!!.createVirtualDisplay(
                    "ScreenCapture",
                    width,
                    height,
                    dpi,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    inputSurface,
                    null,
                    null
                )

            // IMPORTANT ORDER
            nativeStart(nativeHandle)

            startEncoderThread()

            CaptureState.isCapturing.value = true

            Log.i(TAG, "Capture started")

        } catch (e: Exception) {

            Log.e(
                TAG,
                "startCapture failed",
                e
            )

            cleanup()
            stopSelf()
        }
    }

    private fun startEncoderThread() {

        isEncoding.set(true)

        encoderThread =
            HandlerThread(
                "EncoderThread",
                Process.THREAD_PRIORITY_VIDEO
            )

        encoderThread!!.start()

        encoderHandler =
            Handler(encoderThread!!.looper)

        encoderHandler!!.post(
            object : Runnable {

                private val bufferInfo =
                    MediaCodec.BufferInfo()

                override fun run() {

                    if (!isEncoding.get()) {
                        return
                    }

                    try {

                        var index =
                            mediaCodec?.dequeueOutputBuffer(
                                bufferInfo,
                                10000
                            ) ?: -1

                        while (index >= 0) {

                            val outputBuffer =
                                mediaCodec?.getOutputBuffer(index)

                            if (outputBuffer != null &&
                                bufferInfo.size > 0
                            ) {

                                outputBuffer.position(
                                    bufferInfo.offset
                                )

                                outputBuffer.limit(
                                    bufferInfo.offset +
                                            bufferInfo.size
                                )

                                nativePushEncodedFrame(
                                    nativeHandle,
                                    outputBuffer,
                                    bufferInfo.size,
                                    bufferInfo.presentationTimeUs,
                                    bufferInfo.flags
                                )
                            }

                            mediaCodec?.releaseOutputBuffer(
                                index,
                                false
                            )

                            index =
                                mediaCodec?.dequeueOutputBuffer(
                                    bufferInfo,
                                    0
                                ) ?: -1
                        }

                    } catch (e: Exception) {

                        if (isEncoding.get()) {

                            Log.e(
                                TAG,
                                "Encoder loop failed",
                                e
                            )
                        }
                    }

                    if (isEncoding.get()) {
                        encoderHandler?.post(this)
                    }
                }
            }
        )
    }

    private fun stopCapture() {

        if (!CaptureState.isCapturing.value) {
            return
        }

        Log.i(TAG, "Stopping capture")

        isEncoding.set(false)

        try {

            // IMPORTANT ORDER
            mediaCodec?.signalEndOfInputStream()

        } catch (_: Exception) {
        }

        if (nativeHandle != 0L) {
            nativeStop(nativeHandle)
        }

        Handler(Looper.getMainLooper()).postDelayed({

            cleanup()

            stopForeground(STOP_FOREGROUND_REMOVE)

            stopSelf()

        }, 3000)
    }

    private fun cleanup() {

        isEncoding.set(false)

        try {
            encoderThread?.quitSafely()
        } catch (_: Exception) {
        }

        encoderThread = null
        encoderHandler = null

        try {
            virtualDisplay?.release()
        } catch (_: Exception) {
        }

        virtualDisplay = null

        try {
            mediaCodec?.stop()
        } catch (_: Exception) {
        }

        try {
            mediaCodec?.release()
        } catch (_: Exception) {
        }

        mediaCodec = null

        try {
            projection?.stop()
        } catch (_: Exception) {
        }

        projection = null

        if (nativeHandle != 0L) {

            nativeDestroy(nativeHandle)

            nativeHandle = 0L
        }

        try {
            outputPfd?.close()
        } catch (_: Exception) {
        }

        outputPfd = null

        mediaStoreUri?.let { uri ->

            try {

                val values =
                    ContentValues().apply {

                        put(
                            MediaStore.Video.Media.IS_PENDING,
                            0
                        )
                    }

                contentResolver.update(
                    uri,
                    values,
                    null,
                    null
                )

            } catch (_: Exception) {
            }
        }

        mediaStoreUri = null

        CaptureState.isCapturing.value = false
    }

    private fun createNotificationChannel() {

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {

            val channel =
                NotificationChannel(
                    NOTIF_CHANNEL,
                    "Screen Capture",
                    NotificationManager.IMPORTANCE_LOW
                )

            getSystemService(
                NotificationManager::class.java
            ).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(
        text: String
    ): Notification {

        val stopIntent =
            PendingIntent.getService(
                this,
                0,
                Intent(
                    this,
                    CaptureService::class.java
                ).setAction(ACTION_STOP),
                PendingIntent.FLAG_UPDATE_CURRENT or
                        PendingIntent.FLAG_IMMUTABLE
            )

        return NotificationCompat.Builder(
            this,
            NOTIF_CHANNEL
        )
            .setContentTitle("Screen Capture")
            .setContentText(text)
            .setSmallIcon(
                android.R.drawable.ic_menu_camera
            )
            .setOngoing(true)
            .addAction(
                android.R.drawable.ic_media_pause,
                "Stop",
                stopIntent
            )
            .build()
    }

    private external fun nativeCreate(
        width: Int,
        height: Int,
        fps: Int,
        fd: Int,
        bitrate: Int,
        parser: String
    ): Long

    private external fun nativeStart(
        handle: Long
    )

    private external fun nativePushEncodedFrame(
        handle: Long,
        buffer: java.nio.ByteBuffer,
        size: Int,
        tsUs: Long,
        flags: Int
    )

    private external fun nativeStop(
        handle: Long
    )

    private external fun nativeDestroy(
        handle: Long
    )
}