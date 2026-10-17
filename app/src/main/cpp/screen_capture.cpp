#include <android/log.h>
#include <gst/app/gstappsrc.h>
#include <gst/gst.h>
#include <jni.h>

#include <atomic>
#include <memory>
#include <stdexcept>
#include <string>
#include <cstring>

#define TAG "ScreenCapNative"

#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)

template<typename T>
struct GstObjectDeleter {
    void operator()(T* p) const {
        if (p) {
            gst_object_unref(p);
        }
    }
};

template<typename T>
using GstObjPtr = std::unique_ptr<T, GstObjectDeleter<T>>;

struct GstCapsDeleter {
    void operator()(GstCaps* p) const {
        if (p) {
            gst_caps_unref(p);
        }
    }
};

using GstCapsPtr = std::unique_ptr<GstCaps, GstCapsDeleter>;

class ScreenRecorder {
public:
    ScreenRecorder(
            int width,
            int height,
            int fps,
            int fd,
            const std::string& parser
    )
            : m_fps(fps),
              m_fd(fd) {

        gst_init(nullptr, nullptr);

        std::string parserString = parser;

        if (parser == "h264parse" ||
                parser == "h265parse") {

            parserString +=
                    " config-interval=-1 ";
        }

        char pipeline[4096];

        snprintf(
                pipeline,
                sizeof(pipeline),

                "appsrc name=mysrc "
                "is-live=true "
                "format=time "
                "block=false "
                "do-timestamp=false "

                "! %s "

                "! queue "
                "max-size-buffers=60 "
                "max-size-bytes=0 "
                "max-size-time=0 "
                "leaky=downstream "

                "! isofmp4mux "
                "fragment-duration=1000000000 "
                "chunk-mode=keyframe "
                "header-update-mode=rewrite "
                "send-force-keyunit=true "
                "write-mfra=true "
                "offset-to-zero=true "

                "! fdsink fd=%d",

                parserString.c_str(),
                m_fd
        );

        LOGI("Pipeline:\n%s", pipeline);

        GError* err = nullptr;

        m_pipeline.reset(
                gst_parse_launch(pipeline, &err));

        if (!m_pipeline || err) {
            std::string msg =
                    err ? err->message : "unknown";

            if (err) {
                g_error_free(err);
            }

            throw std::runtime_error(msg);
        }

        m_appSrc.reset(
                GST_APP_SRC(
                        gst_bin_get_by_name(
                                GST_BIN(m_pipeline.get()),
                                "mysrc")));

        if (!m_appSrc) {
            throw std::runtime_error(
                    "Failed to get appsrc");
        }

        GstCapsPtr caps(
                gst_caps_from_string(
                        "video/x-h264,"
                        "alignment=au"));

        g_object_set(
                m_appSrc.get(),
                "caps",
                caps.get(),
                nullptr);

        GstBus* bus =
                gst_element_get_bus(m_pipeline.get());

        gst_bus_add_watch(
                bus,

                [](GstBus*,
                        GstMessage* msg,
                        gpointer) -> gboolean {

                    switch (GST_MESSAGE_TYPE(msg)) {

                        case GST_MESSAGE_ERROR: {
                            GError* err = nullptr;
                            gchar* dbg = nullptr;

                            gst_message_parse_error(
                                    msg,
                                    &err,
                                    &dbg);

                            LOGE("GST ERROR: %s",
                                    err->message);

                            if (dbg) {
                                LOGE("DEBUG: %s", dbg);
                            }

                            g_error_free(err);
                            g_free(dbg);

                            break;
                        }

                        case GST_MESSAGE_EOS:
                            LOGI("GST EOS");
                            break;

                        default:
                            break;
                    }

                    return TRUE;
                },

                nullptr);

        gst_object_unref(bus);
    }

    ~ScreenRecorder() {
        if (m_pipeline) {
            gst_element_set_state(
                    m_pipeline.get(),
                    GST_STATE_NULL);
        }
    }

    bool start() {

        GstStateChangeReturn ret =
                gst_element_set_state(
                        m_pipeline.get(),
                        GST_STATE_PLAYING);

        if (ret == GST_STATE_CHANGE_FAILURE) {
            LOGE("Failed PLAYING state");
            return false;
        }

        LOGI("Pipeline started");

        return true;
    }

    void stop() {

        if (!m_eos.exchange(true)) {

            LOGI("Sending EOS");

            gst_app_src_end_of_stream(
                    m_appSrc.get());

            gst_element_get_state(
                    m_pipeline.get(),
                    nullptr,
                    nullptr,
                    5 * GST_SECOND);
        }
    }

    void pushEncodedFrame(
            const uint8_t* data,
            size_t size,
            uint64_t tsUs,
            uint32_t flags) {

        if (m_eos) {
            return;
        }

        uint64_t tsNs =
                tsUs * 1000ULL;

        if (m_firstPts == GST_CLOCK_TIME_NONE) {
            m_firstPts = tsNs;
        }

        GstClockTime pts =
                tsNs - m_firstPts;

        GstBuffer* buffer =
                gst_buffer_new_allocate(
                        nullptr,
                        size,
                        nullptr);

        GstMapInfo map;

        gst_buffer_map(
                buffer,
                &map,
                GST_MAP_WRITE);

        memcpy(map.data,data,size);

        gst_buffer_unmap(buffer,&map);

        GST_BUFFER_PTS(buffer) = pts;
        GST_BUFFER_DTS(buffer) = pts;

        GST_BUFFER_DURATION(buffer) =
                gst_util_uint64_scale_int(
                        1,
                        GST_SECOND,
                        m_fps);

        // codec config
        if (flags & 2) {
            GST_BUFFER_FLAG_SET(
                    buffer,
                    GST_BUFFER_FLAG_HEADER);
        }

        // keyframe handling
        if (flags & 1) {
            GST_BUFFER_FLAG_UNSET(buffer,GST_BUFFER_FLAG_DELTA_UNIT);
        } else {
            GST_BUFFER_FLAG_SET(buffer,GST_BUFFER_FLAG_DELTA_UNIT);
        }

//        LOGI(
//                "push frame size=%zu pts=%llu flags=%u",
//                size,
//                (unsigned long long) pts,
//                flags);

        GstFlowReturn ret =
                gst_app_src_push_buffer(
                        m_appSrc.get(),
                        buffer);

        if (ret != GST_FLOW_OK) {
            LOGW("push failed: %s",
                    gst_flow_get_name(ret));
        }
    }

private:
    int m_fps;
    int m_fd;

    GstClockTime m_firstPts =
            GST_CLOCK_TIME_NONE;

    std::atomic<bool> m_eos{false};
    GstObjPtr<GstElement> m_pipeline;
    GstObjPtr<GstAppSrc> m_appSrc;
};

extern "C"
JNIEXPORT jlong JNICALL
Java_com_dhyey_screencapturegstreamerandroid_CaptureService_nativeCreate(
        JNIEnv* env,
        jobject,
        jint width,
        jint height,
        jint fps,
        jint fd,
        jint bitrate,
        jstring parserStr) {

    const char* chars =
            env->GetStringUTFChars(
                    parserStr,
                    nullptr);

    std::string parser(chars);

    env->ReleaseStringUTFChars(
            parserStr,
            chars);

    try {

        auto* rec =
                new ScreenRecorder(
                        width,
                        height,
                        fps,
                        fd,
                        parser);

        return reinterpret_cast<jlong>(rec);

    } catch (const std::exception& e) {

        LOGE("nativeCreate failed: %s",
                e.what());

        return 0;
    }
}

extern "C"
JNIEXPORT void JNICALL
Java_com_dhyey_screencapturegstreamerandroid_CaptureService_nativeStart(
        JNIEnv*,
        jobject,
        jlong handle) {

    if (!handle) {
        return;
    }

    reinterpret_cast<ScreenRecorder*>(handle)
            ->start();
}

extern "C"
JNIEXPORT void JNICALL
Java_com_dhyey_screencapturegstreamerandroid_CaptureService_nativePushEncodedFrame(
        JNIEnv* env,
        jobject,
        jlong handle,
        jobject buf,
        jint size,
        jlong tsUs,
        jint flags) {

    if (!handle) {
        return;
    }

    auto* ptr =
            static_cast<uint8_t*>(
                    env->GetDirectBufferAddress(buf));

    if (!ptr) {
        return;
    }

    reinterpret_cast<ScreenRecorder*>(handle)
            ->pushEncodedFrame(
                    ptr,
                    size,
                    tsUs,
                    flags);
}

extern "C"
JNIEXPORT void JNICALL
Java_com_dhyey_screencapturegstreamerandroid_CaptureService_nativeStop(
        JNIEnv*,
        jobject,
        jlong handle) {

    if (!handle) {
        return;
    }

    reinterpret_cast<ScreenRecorder*>(handle)
            ->stop();
}

extern "C"
JNIEXPORT void JNICALL
Java_com_dhyey_screencapturegstreamerandroid_CaptureService_nativeDestroy(
        JNIEnv*,
        jobject,
        jlong handle) {

    if (!handle) {
        return;
    }

    delete reinterpret_cast<ScreenRecorder*>(handle);
}

JNIEXPORT jint JNICALL
JNI_OnLoad(JavaVM*, void*) {
    setenv("GST_DEBUG","2,isofmp4mux:6,h264parse:6,h265parse:6",1);
    return JNI_VERSION_1_6;
}