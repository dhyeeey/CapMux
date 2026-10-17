package com.dhyey.screencapturegstreamerandroid.audio

import android.media.AudioFormat

object AudioConstants {
    const val SAMPLE_RATE = 48_000
    const val CHANNEL_COUNT = 2
    const val BIT_RATE = 128_000
    const val CHANNEL_MASK = AudioFormat.CHANNEL_IN_STEREO
    const val ENCODING = AudioFormat.ENCODING_PCM_16BIT
    const val BYTES_PER_SAMPLE = 2
    const val BYTES_PER_FRAME = CHANNEL_COUNT * BYTES_PER_SAMPLE

    /** ~20ms of PCM. */
    const val FRAMES_PER_BUFFER = SAMPLE_RATE / 50
    const val BYTES_PER_BUFFER = FRAMES_PER_BUFFER * BYTES_PER_FRAME
}
