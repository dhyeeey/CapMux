#include "media_recorder.h"

#include <android/log.h>
#include <jni.h>

#include <cstdlib>
#include <string>

#define TAG "MediaRecorderJni"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

namespace {

MediaRecorder* asRecorder(jlong handle) {
    return reinterpret_cast<MediaRecorder*>(handle);
}

}  // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_com_dhyey_screencapturegstreamerandroid_nativebridge_NativeMediaRecorder_nativeCreate(
    JNIEnv* env,
    jobject,
    jboolean hasVideo,
    jboolean hasAudio,
    jint width,
    jint height,
    jint fps,
    jint fd,
    jstring parserStr) {
    (void)width;
    (void)height;

    const char* chars = env->GetStringUTFChars(parserStr, nullptr);
    std::string parser = chars ? chars : "h264parse";
    if (chars) {
        env->ReleaseStringUTFChars(parserStr, chars);
    }

    PipelineConfig config;
    config.hasVideo = hasVideo == JNI_TRUE;
    config.hasAudio = hasAudio == JNI_TRUE;
    config.fd = fd;
    config.fps = fps > 0 ? fps : 30;
    config.videoParser = parser;

    try {
        return reinterpret_cast<jlong>(new MediaRecorder(config));
    } catch (const std::exception& e) {
        LOGE("nativeCreate failed: %s", e.what());
        return 0;
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_dhyey_screencapturegstreamerandroid_nativebridge_NativeMediaRecorder_nativeStart(
    JNIEnv*, jobject, jlong handle) {
    if (!handle) {
        return;
    }
    asRecorder(handle)->start();
}

extern "C" JNIEXPORT void JNICALL
Java_com_dhyey_screencapturegstreamerandroid_nativebridge_NativeMediaRecorder_nativePushVideo(
    JNIEnv* env, jobject, jlong handle, jobject buf, jint size, jlong tsUs, jint flags) {
    if (!handle) {
        return;
    }
    auto* ptr = static_cast<uint8_t*>(env->GetDirectBufferAddress(buf));
    if (!ptr) {
        return;
    }
    asRecorder(handle)->pushVideo(ptr, static_cast<size_t>(size), static_cast<uint64_t>(tsUs),
                                  static_cast<uint32_t>(flags));
}

extern "C" JNIEXPORT void JNICALL
Java_com_dhyey_screencapturegstreamerandroid_nativebridge_NativeMediaRecorder_nativePushAudio(
    JNIEnv* env, jobject, jlong handle, jobject buf, jint size, jlong tsUs, jint flags) {
    if (!handle) {
        return;
    }
    auto* ptr = static_cast<uint8_t*>(env->GetDirectBufferAddress(buf));
    if (!ptr) {
        return;
    }
    asRecorder(handle)->pushAudio(ptr, static_cast<size_t>(size), static_cast<uint64_t>(tsUs),
                                  static_cast<uint32_t>(flags));
}

extern "C" JNIEXPORT void JNICALL
Java_com_dhyey_screencapturegstreamerandroid_nativebridge_NativeMediaRecorder_nativeStop(
    JNIEnv*, jobject, jlong handle) {
    if (!handle) {
        return;
    }
    asRecorder(handle)->stop();
}

extern "C" JNIEXPORT void JNICALL
Java_com_dhyey_screencapturegstreamerandroid_nativebridge_NativeMediaRecorder_nativeDestroy(
    JNIEnv*, jobject, jlong handle) {
    if (!handle) {
        return;
    }
    delete asRecorder(handle);
}

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM*, void*) {
    setenv("GST_DEBUG", "2,isofmp4mux:5,h264parse:5,aacparse:5", 1);
    return JNI_VERSION_1_6;
}
