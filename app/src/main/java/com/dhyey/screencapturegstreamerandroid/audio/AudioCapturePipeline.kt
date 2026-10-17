package com.dhyey.screencapturegstreamerandroid.audio

import android.media.projection.MediaProjection
import android.os.Build
import android.os.Process
import android.util.Log
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Captures mic and/or internal audio, mixes to one PCM stream, encodes AAC.
 */
class AudioCapturePipeline(
    private val captureMic: Boolean,
    private val captureInternal: Boolean,
    private val onEncodedFrame: (ByteBuffer, Int, Long, Int) -> Unit,
) {
    companion object {
        private const val TAG = "AudioCapturePipeline"
    }

    private val micSource = if (captureMic) MicAudioSource() else null
    private val internalSource = if (captureInternal) InternalAudioSource() else null
    private var aacEncoder: AacEncoder? = null
    private var thread: Thread? = null
    private val running = AtomicBoolean(false)

    fun start(projection: MediaProjection?) {
        require(captureMic || captureInternal) { "No audio sources enabled" }
        if (captureInternal) {
            require(Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                "Internal audio requires Android 10+"
            }
            require(projection != null) { "MediaProjection required for internal audio" }
            internalSource!!.start(projection)
        }
        micSource?.start()

        aacEncoder = AacEncoder(onEncodedFrame).also { it.start() }
        running.set(true)

        thread = Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
            captureLoop()
        }, "AudioCaptureThread").also { it.start() }

        Log.i(TAG, "Audio pipeline started mic=$captureMic internal=$captureInternal")
    }

    private fun captureLoop() {
        val micBuf = ByteArray(AudioConstants.BYTES_PER_BUFFER)
        val intBuf = ByteArray(AudioConstants.BYTES_PER_BUFFER)
        val mixBuf = ByteArray(AudioConstants.BYTES_PER_BUFFER)
        val encoder = aacEncoder ?: return

        while (running.get()) {
            val micLen = if (micSource != null) {
                val n = micSource.read(micBuf)
                if (n < 0) 0 else n
            } else {
                0
            }

            val intLen = if (internalSource != null) {
                val n = internalSource.read(intBuf)
                if (n < 0) 0 else n
            } else {
                0
            }

            if (micSource != null && internalSource != null) {
                val mixedLen = PcmAudioMixer.mix(micBuf, micLen, intBuf, intLen, mixBuf)
                if (mixedLen > 0) encoder.encodePcm(mixBuf, mixedLen)
            } else if (micSource != null) {
                if (micLen > 0) encoder.encodePcm(micBuf, micLen)
            } else {
                if (intLen > 0) encoder.encodePcm(intBuf, intLen)
            }

            // If both reads returned nothing, yield briefly to avoid busy spin.
            if (micLen == 0 && intLen == 0) {
                try {
                    Thread.sleep(5)
                } catch (_: InterruptedException) {
                    break
                }
            }
        }
    }

    fun stop() {
        running.set(false)
        try {
            thread?.join(1500)
        } catch (_: Exception) {
        }
        thread = null

        try {
            aacEncoder?.signalEndOfStream()
        } catch (_: Exception) {
        }
        aacEncoder?.release()
        aacEncoder = null

        micSource?.release()
        internalSource?.release()
        Log.i(TAG, "Audio pipeline stopped")
    }
}
