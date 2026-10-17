package com.dhyey.screencapturegstreamerandroid.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi

class InternalAudioSource {
    companion object {
        private const val TAG = "InternalAudioSource"
    }

    private var recorder: AudioRecord? = null

    @RequiresApi(Build.VERSION_CODES.Q)
    fun start(projection: MediaProjection) {
        val captureConfig = AudioPlaybackCaptureConfiguration.Builder(projection)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .build()

        val minBuf = AudioRecord.getMinBufferSize(
            AudioConstants.SAMPLE_RATE,
            AudioConstants.CHANNEL_MASK,
            AudioConstants.ENCODING,
        )
        val bufferSize = maxOf(minBuf, AudioConstants.BYTES_PER_BUFFER * 2)

        recorder = AudioRecord.Builder()
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioConstants.ENCODING)
                    .setSampleRate(AudioConstants.SAMPLE_RATE)
                    .setChannelMask(AudioConstants.CHANNEL_MASK)
                    .build(),
            )
            .setBufferSizeInBytes(bufferSize)
            .setAudioPlaybackCaptureConfig(captureConfig)
            .build()

        if (recorder?.state != AudioRecord.STATE_INITIALIZED) {
            release()
            throw IllegalStateException("Failed to initialize internal AudioRecord")
        }

        recorder?.startRecording()
        Log.i(TAG, "Internal audio capture started")
    }

    fun read(buffer: ByteArray): Int {
        val rec = recorder ?: return -1
        return rec.read(buffer, 0, buffer.size)
    }

    fun release() {
        try {
            recorder?.stop()
        } catch (_: Exception) {
        }
        try {
            recorder?.release()
        } catch (_: Exception) {
        }
        recorder = null
    }
}
