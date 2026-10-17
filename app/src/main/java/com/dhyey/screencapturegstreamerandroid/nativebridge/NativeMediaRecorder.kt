package com.dhyey.screencapturegstreamerandroid.nativebridge

import java.nio.ByteBuffer

/**
 * Thin JNI wrapper around the native GStreamer muxer (isofmp4mux + fdsink).
 */
class NativeMediaRecorder private constructor(private var handle: Long) {

    val isValid: Boolean get() = handle != 0L

    fun start() {
        if (handle != 0L) nativeStart(handle)
    }

    fun pushVideo(buffer: ByteBuffer, size: Int, tsUs: Long, flags: Int) {
        if (handle != 0L) nativePushVideo(handle, buffer, size, tsUs, flags)
    }

    fun pushAudio(buffer: ByteBuffer, size: Int, tsUs: Long, flags: Int) {
        if (handle != 0L) nativePushAudio(handle, buffer, size, tsUs, flags)
    }

    fun stop() {
        if (handle != 0L) nativeStop(handle)
    }

    fun destroy() {
        if (handle != 0L) {
            nativeDestroy(handle)
            handle = 0L
        }
    }

    private external fun nativeStart(handle: Long)

    private external fun nativePushVideo(
        handle: Long,
        buffer: ByteBuffer,
        size: Int,
        tsUs: Long,
        flags: Int,
    )

    private external fun nativePushAudio(
        handle: Long,
        buffer: ByteBuffer,
        size: Int,
        tsUs: Long,
        flags: Int,
    )

    private external fun nativeStop(handle: Long)

    private external fun nativeDestroy(handle: Long)

    companion object {
        init {
            System.loadLibrary("screencapturegstreamerandroid")
        }

        fun create(
            hasVideo: Boolean,
            hasAudio: Boolean,
            width: Int,
            height: Int,
            fps: Int,
            fd: Int,
            videoParser: String,
        ): NativeMediaRecorder? {
            val handle = nativeCreate(hasVideo, hasAudio, width, height, fps, fd, videoParser)
            return if (handle == 0L) null else NativeMediaRecorder(handle)
        }

        @JvmStatic
        private external fun nativeCreate(
            hasVideo: Boolean,
            hasAudio: Boolean,
            width: Int,
            height: Int,
            fps: Int,
            fd: Int,
            videoParser: String,
        ): Long
    }
}
