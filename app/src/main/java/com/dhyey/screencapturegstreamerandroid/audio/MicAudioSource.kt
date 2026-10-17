package com.dhyey.screencapturegstreamerandroid.audio

import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log

class MicAudioSource {
    companion object {
        private const val TAG = "MicAudioSource"
    }

    private var recorder: AudioRecord? = null

    fun start() {
        val minBuf = AudioRecord.getMinBufferSize(
            AudioConstants.SAMPLE_RATE,
            AudioConstants.CHANNEL_MASK,
            AudioConstants.ENCODING,
        )
        val bufferSize = maxOf(minBuf, AudioConstants.BYTES_PER_BUFFER * 2)

        recorder = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            AudioConstants.SAMPLE_RATE,
            AudioConstants.CHANNEL_MASK,
            AudioConstants.ENCODING,
            bufferSize,
        )

        if (recorder?.state != AudioRecord.STATE_INITIALIZED) {
            release()
            throw IllegalStateException("Failed to initialize mic AudioRecord")
        }

        recorder?.startRecording()
        Log.i(TAG, "Mic capture started")
    }

    /** Reads up to [buffer].size bytes. Returns bytes read, or <=0 on error/EOF. */
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
