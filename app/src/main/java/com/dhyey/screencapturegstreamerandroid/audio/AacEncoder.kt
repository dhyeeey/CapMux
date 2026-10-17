package com.dhyey.screencapturegstreamerandroid.audio

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.util.Log
import java.nio.ByteBuffer

class AacEncoder(
    private val onEncodedFrame: (ByteBuffer, Int, Long, Int) -> Unit,
) {
    companion object {
        private const val TAG = "AacEncoder"
        private const val MIME = MediaFormat.MIMETYPE_AUDIO_AAC
        private const val TIMEOUT_US = 10_000L
    }

    private var codec: MediaCodec? = null
    private var presentationTimeUs = 0L
    private var codecConfigSent = false

    fun start() {
        val format = MediaFormat.createAudioFormat(
            MIME,
            AudioConstants.SAMPLE_RATE,
            AudioConstants.CHANNEL_COUNT,
        ).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, AudioConstants.BIT_RATE)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, AudioConstants.BYTES_PER_BUFFER * 2)
        }

        codec = MediaCodec.createEncoderByType(MIME).also {
            it.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            it.start()
        }
        presentationTimeUs = 0L
        codecConfigSent = false
        Log.i(TAG, "AAC encoder started")
    }

    private fun emitCodecConfigFromFormat(format: MediaFormat) {
        if (codecConfigSent) return
        val csd = format.getByteBuffer("csd-0") ?: return
        val copy = ByteBuffer.allocateDirect(csd.remaining())
        val saved = csd.duplicate()
        copy.put(saved)
        copy.flip()
        onEncodedFrame(
            copy,
            copy.remaining(),
            0L,
            MediaCodec.BUFFER_FLAG_CODEC_CONFIG,
        )
        codecConfigSent = true
        Log.i(TAG, "Forwarded AAC csd-0 (${copy.remaining()} bytes)")
    }

    fun encodePcm(pcm: ByteArray, length: Int) {
        val encoder = codec ?: return
        if (length <= 0) return

        var offset = 0
        while (offset < length) {
            val inputIndex = encoder.dequeueInputBuffer(TIMEOUT_US)
            if (inputIndex < 0) {
                drain()
                continue
            }

            val inputBuffer = encoder.getInputBuffer(inputIndex) ?: continue
            inputBuffer.clear()
            val toCopy = minOf(inputBuffer.remaining(), length - offset)
            inputBuffer.put(pcm, offset, toCopy)
            encoder.queueInputBuffer(inputIndex, 0, toCopy, presentationTimeUs, 0)

            val frames = toCopy / AudioConstants.BYTES_PER_FRAME
            presentationTimeUs += frames * 1_000_000L / AudioConstants.SAMPLE_RATE
            offset += toCopy
            drain()
        }
    }

    fun signalEndOfStream() {
        val encoder = codec ?: return
        val inputIndex = encoder.dequeueInputBuffer(TIMEOUT_US)
        if (inputIndex >= 0) {
            encoder.queueInputBuffer(
                inputIndex,
                0,
                0,
                presentationTimeUs,
                MediaCodec.BUFFER_FLAG_END_OF_STREAM,
            )
        }
        drain(untilEos = true)
    }

    private fun drain(untilEos: Boolean = false) {
        val encoder = codec ?: return
        val info = MediaCodec.BufferInfo()
        var idleTries = 0
        while (true) {
            val index = encoder.dequeueOutputBuffer(info, if (untilEos) TIMEOUT_US else 0)
            when {
                index == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    if (!untilEos) return
                    idleTries++
                    if (idleTries > 50) return
                }
                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    idleTries = 0
                    emitCodecConfigFromFormat(encoder.outputFormat)
                }
                index >= 0 -> {
                    idleTries = 0
                    val buffer = encoder.getOutputBuffer(index)
                    if (buffer != null && info.size > 0) {
                        buffer.position(info.offset)
                        buffer.limit(info.offset + info.size)
                        if ((info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                            codecConfigSent = true
                        } else if (!codecConfigSent) {
                            // Some devices emit AUs before FORMAT_CHANGED; try format now.
                            emitCodecConfigFromFormat(encoder.outputFormat)
                        }
                        onEncodedFrame(buffer, info.size, info.presentationTimeUs, info.flags)
                    }
                    val eos = (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                    encoder.releaseOutputBuffer(index, false)
                    if (eos) return
                }
                else -> return
            }
        }
    }

    fun release() {
        try {
            codec?.stop()
        } catch (_: Exception) {
        }
        try {
            codec?.release()
        } catch (_: Exception) {
        }
        codec = null
    }
}
