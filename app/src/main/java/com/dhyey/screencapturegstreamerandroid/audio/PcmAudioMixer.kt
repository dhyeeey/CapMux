package com.dhyey.screencapturegstreamerandroid.audio

/**
 * Mixes two interleaved PCM16 LE streams into [out].
 * Missing/shorter inputs are treated as silence for the remainder.
 */
object PcmAudioMixer {
    fun mix(primary: ByteArray, primaryLen: Int, secondary: ByteArray, secondaryLen: Int, out: ByteArray): Int {
        val len = maxOf(primaryLen, secondaryLen).coerceAtMost(out.size) and 1.inv()
        var i = 0
        while (i < len) {
            val a = if (i + 1 < primaryLen) readSample(primary, i) else 0
            val b = if (i + 1 < secondaryLen) readSample(secondary, i) else 0
            val mixed = (a + b).coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            out[i] = (mixed and 0xFF).toByte()
            out[i + 1] = ((mixed shr 8) and 0xFF).toByte()
            i += 2
        }
        return len
    }

    private fun readSample(buf: ByteArray, offset: Int): Int {
        val lo = buf[offset].toInt() and 0xFF
        val hi = buf[offset + 1].toInt()
        return (hi shl 8) or lo
    }
}
