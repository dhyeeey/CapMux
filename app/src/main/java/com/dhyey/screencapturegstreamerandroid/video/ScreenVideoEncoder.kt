package com.dhyey.screencapturegstreamerandroid.video

import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaCodec
import android.media.MediaFormat
import android.media.projection.MediaProjection
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.util.Log
import com.dhyey.screencapturegstreamerandroid.codec.CodecManager
import com.dhyey.screencapturegstreamerandroid.codec.EncoderFormatHelper
import com.dhyey.screencapturegstreamerandroid.codec.RecordingProfile
import com.dhyey.screencapturegstreamerandroid.codec.VideoCodec
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

class ScreenVideoEncoder(
    private val width: Int,
    private val height: Int,
    private val dpi: Int,
    private val fps: Int,
    private val bitrate: Int,
    private val codec: VideoCodec = VideoCodec.AVC,
    private val onEncodedFrame: (ByteBuffer, Int, Long, Int) -> Unit,
) {
    companion object {
        private const val TAG = "ScreenVideoEncoder"
    }

    private var mediaCodec: MediaCodec? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var encoderThread: HandlerThread? = null
    private var encoderHandler: Handler? = null
    private val isEncoding = AtomicBoolean(false)

    fun start(projection: MediaProjection) {
        val encoderInfo = CodecManager.findHardwareEncoder(codec.mime)
            ?: throw RuntimeException("No hardware encoder for ${codec.mime}")

        mediaCodec = MediaCodec.createByCodecName(encoderInfo.name).also { encoder ->
            EncoderFormatHelper.configureEncoder(
                codec = encoder,
                mime = codec.mime,
                width = width,
                height = height,
                bitrate = bitrate,
                fps = fps,
                encoderName = encoderInfo.name,
            )
            val inputSurface = encoder.createInputSurface()
            encoder.start()

            // Kick an IDR immediately — QTI HEVC often spends the first 1–2s at high QP
            // under VBR until the rate controller settles; a sync frame cleans that up.
            try {
                encoder.setParameters(
                    Bundle().apply {
                        putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0)
                        putInt(MediaCodec.PARAMETER_KEY_VIDEO_BITRATE, bitrate)
                    },
                )
            } catch (e: Exception) {
                Log.w(TAG, "Request sync frame failed: ${e.message}")
            }

            virtualDisplay = projection.createVirtualDisplay(
                "ScreenCapture",
                width,
                height,
                dpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                inputSurface,
                null,
                null,
            )
        }

        startDrainLoop()
        // Re-assert bitrate + sync after the first frames arrive (HEVC warm-up).
        if (codec.mime == MediaFormat.MIMETYPE_VIDEO_HEVC) {
            encoderHandler?.postDelayed({ requestKeyFrameAndBitrate() }, 400L)
            encoderHandler?.postDelayed({ requestKeyFrameAndBitrate() }, 1200L)
        }
        Log.i(TAG, "Screen encoder started ${width}x${height}@$fps bitrate=$bitrate codec=${codec.name}")
    }

    private fun requestKeyFrameAndBitrate() {
        if (!isEncoding.get()) return
        try {
            mediaCodec?.setParameters(
                Bundle().apply {
                    putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0)
                    putInt(MediaCodec.PARAMETER_KEY_VIDEO_BITRATE, bitrate)
                },
            )
        } catch (e: Exception) {
            Log.w(TAG, "HEVC warm-up parameters failed: ${e.message}")
        }
    }

    private fun startDrainLoop() {
        isEncoding.set(true)
        // Default priority — VIDEO priority can contend with UI / composition.
        encoderThread = HandlerThread("EncoderThread", Process.THREAD_PRIORITY_DEFAULT).also {
            it.start()
            encoderHandler = Handler(it.looper)
        }

        encoderHandler?.post(object : Runnable {
            private val bufferInfo = MediaCodec.BufferInfo()
            // ~1 frame at 30fps; avoids a tight spin when the encoder is idle.
            private val dequeueTimeoutUs = 33_000L

            override fun run() {
                if (!isEncoding.get()) return

                try {
                    var index = mediaCodec?.dequeueOutputBuffer(bufferInfo, dequeueTimeoutUs) ?: -1
                    while (index >= 0) {
                        val outputBuffer = mediaCodec?.getOutputBuffer(index)
                        if (outputBuffer != null && bufferInfo.size > 0) {
                            outputBuffer.position(bufferInfo.offset)
                            outputBuffer.limit(bufferInfo.offset + bufferInfo.size)
                            onEncodedFrame(
                                outputBuffer,
                                bufferInfo.size,
                                bufferInfo.presentationTimeUs,
                                bufferInfo.flags,
                            )
                        }
                        mediaCodec?.releaseOutputBuffer(index, false)
                        index = mediaCodec?.dequeueOutputBuffer(bufferInfo, 0) ?: -1
                    }
                } catch (e: Exception) {
                    if (isEncoding.get()) {
                        Log.e(TAG, "Encoder loop failed", e)
                    }
                }

                if (isEncoding.get()) {
                    encoderHandler?.post(this)
                }
            }
        })
    }

    fun signalEndOfStream() {
        isEncoding.set(false)
        try {
            mediaCodec?.signalEndOfInputStream()
        } catch (_: Exception) {
        }
    }

    fun release() {
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
    }
}

/** Helper for bitrate selection used by the service. */
fun calculateScreenBitrate(
    width: Int,
    height: Int,
    fps: Int,
    profile: RecordingProfile = RecordingProfile.BALANCED,
    codec: VideoCodec = VideoCodec.AVC,
): Int = CodecManager.calculateAdaptiveBitrate(width, height, fps, profile, codec)
