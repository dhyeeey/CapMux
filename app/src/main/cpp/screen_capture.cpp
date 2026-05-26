#include <android/log.h>
#include <jni.h>
#include <gst/gst.h>
#include <gst/app/gstappsrc.h>

#include <string>
#include <memory>
#include <thread>
#include <atomic>
#include <stdexcept>

#define TAG "ScreenCapNative"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN,  TAG, __VA_ARGS__)

// -------------------------------------------------------------------------
// Strict Deleters for Smart Pointers (RAII for GStreamer/GLib)
// -------------------------------------------------------------------------

template <typename T>
struct GstObjectDeleter {
    void operator()(T* p) const { if (p) gst_object_unref(p); }
};
template <typename T>
using GstObjPtr = std::unique_ptr<T, GstObjectDeleter<T>>;

struct GstCapsDeleter {
    void operator()(GstCaps* p) const { if (p) gst_caps_unref(p); }
};
using GstCapsPtr = std::unique_ptr<GstCaps, GstCapsDeleter>;

struct GSourceDeleter {
    void operator()(GSource* p) const { if (p) g_source_unref(p); }
};
using GSourcePtr = std::unique_ptr<GSource, GSourceDeleter>;

struct GErrorDeleter {
    void operator()(GError* p) const { if (p) g_error_free(p); }
};
using GErrorPtr = std::unique_ptr<GError, GErrorDeleter>;

struct GMainLoopDeleter {
    void operator()(GMainLoop* p) const { if (p) g_main_loop_unref(p); }
};
using GMainLoopPtr = std::unique_ptr<GMainLoop, GMainLoopDeleter>;

struct GMainContextDeleter {
    void operator()(GMainContext* p) const { if (p) g_main_context_unref(p); }
};
using GMainContextPtr = std::unique_ptr<GMainContext, GMainContextDeleter>;


// -------------------------------------------------------------------------
// OOP Wrapper for the GStreamer Pipeline
// -------------------------------------------------------------------------
class ScreenRecorder {
public:
    ScreenRecorder(int w, int h, int fps, int fd, int bitrate, const std::string& parserStr)
            : m_fd(fd)
    {
        LOGI("Init: Zero-Copy MediaCodec Pipeline -> fd=%d, parser=%s", fd, parserStr.c_str());

        // Use a leaky downstream queue (drops oldest buffers) to prevent latency buildup
        char pipelineStr[2048];
        snprintf(pipelineStr, sizeof(pipelineStr),
                "appsrc name=mysrc format=time is-live=true block=false do-timestamp=false ! "
                "%s ! "
                "queue max-size-buffers=60 max-size-bytes=0 max-size-time=0 leaky=downstream ! "
                "mpegtsmux ! "
                "fdsink fd=%d",
                parserStr.c_str(), m_fd);

        LOGI("Pipeline Launch String:\n%s", pipelineStr);

        GError* err = nullptr;
        m_pipeline.reset(gst_parse_launch(pipelineStr, &err));

        if (err || !m_pipeline) {
            std::string errMsg = err ? err->message : "Unknown error";
            LOGE("gst_parse_launch failed: %s", errMsg.c_str());
            if (err) g_error_free(err);
            throw std::runtime_error("Failed to parse GStreamer pipeline: " + errMsg);
        }

        m_appSrc.reset(GST_APP_SRC(gst_bin_get_by_name(GST_BIN(m_pipeline.get()), "mysrc")));
        if (!m_appSrc) {
            throw std::runtime_error("Could not find 'mysrc' element in pipeline");
        }

        // Determine caps based on parser
        std::string capsStr = "video/x-h264, stream-format=byte-stream, alignment=au";
        if (parserStr == "h265parse") {
            capsStr = "video/x-h265, stream-format=byte-stream, alignment=au";
        } else if (parserStr == "av1parse") {
            capsStr = "video/x-av1, stream-format=byte-stream, alignment=au";
        }

        GstCapsPtr srcCaps(gst_caps_from_string(capsStr.c_str()));
        g_object_set(m_appSrc.get(), "caps", srcCaps.get(), nullptr);

        m_context.reset(g_main_context_new());
        m_loop.reset(g_main_loop_new(m_context.get(), FALSE));

        GstObjPtr<GstBus> bus(gst_element_get_bus(m_pipeline.get()));
        GSourcePtr watch(gst_bus_create_watch(bus.get()));
        g_source_set_callback(watch.get(), (GSourceFunc)onBusMessage, this, nullptr);
        g_source_attach(watch.get(), m_context.get());

        m_loopThread = std::thread([this]() {
            LOGI("GLib main loop starting...");
            g_main_loop_run(m_loop.get());
            LOGI("GLib main loop exited.");
        });
    }

    ~ScreenRecorder() {
        LOGI("Destroying ScreenRecorder...");

        if (m_loop) {
            g_main_loop_quit(m_loop.get());
        }

        if (m_loopThread.joinable()) {
            m_loopThread.join();
        }

        if (m_pipeline) {
            gst_element_set_state(m_pipeline.get(), GST_STATE_NULL);
        }

        LOGI("ScreenRecorder fully destroyed.");
    }

    bool start() {
        LOGI("Setting pipeline to PLAYING...");
        GstStateChangeReturn ret = gst_element_set_state(m_pipeline.get(), GST_STATE_PLAYING);
        if (ret == GST_STATE_CHANGE_FAILURE) {
            LOGE("Failed to set pipeline to PLAYING state.");
            return false;
        }
        return true;
    }

    void stop() {
        if (!m_isEos.exchange(true)) {
            LOGI("Sending EOS to appsrc (Frames recorded: %llu)", (unsigned long long)m_framesIn.load());
            if (m_appSrc) {
                gst_app_src_end_of_stream(m_appSrc.get());
            }
        }
    }

    void pushEncodedFrame(const uint8_t* data, size_t size, uint64_t tsUs, uint32_t flags) {
        if (m_isEos || !m_appSrc) return;

        uint64_t tsNs = tsUs * 1000;

        if (m_firstTsNs == 0) {
            m_firstTsNs = tsNs;
        }

        uint64_t pts = (tsNs >= m_firstTsNs) ? (tsNs - m_firstTsNs) : 0;

        GstBuffer* gb = gst_buffer_new_allocate(nullptr, size, nullptr);
        GstMapInfo map;
        gst_buffer_map(gb, &map, GST_MAP_WRITE);
        memcpy(map.data, data, size);
        gst_buffer_unmap(gb, &map);

        GST_BUFFER_PTS(gb) = pts;

        if (flags & 2) { // BUFFER_FLAG_CODEC_CONFIG = 2
            GST_BUFFER_PTS(gb) = GST_CLOCK_TIME_NONE;
        }

        GstFlowReturn ret = gst_app_src_push_buffer(m_appSrc.get(), gb);
        if (ret != GST_FLOW_OK) {
            LOGW("appsrc buffer drop, reason: %s", gst_flow_get_name(ret));
        } else {
            m_framesIn++;
        }
    }

private:
    int m_fd;
    uint64_t m_firstTsNs = 0;

    std::atomic<bool> m_isEos{false};
    std::atomic<uint64_t> m_framesIn{0};

    GstObjPtr<GstElement> m_pipeline;
    GstObjPtr<GstAppSrc>  m_appSrc;
    GMainContextPtr       m_context;
    GMainLoopPtr          m_loop;
    std::thread           m_loopThread;

    static gboolean onBusMessage(GstBus* bus, GstMessage* msg, gpointer data) {
        return static_cast<ScreenRecorder*>(data)->handleMessage(msg);
    }

    gboolean handleMessage(GstMessage* msg) {
        switch (GST_MESSAGE_TYPE(msg)) {
            case GST_MESSAGE_EOS:
                LOGI("Bus: EOS received. MPEG-TS generation complete.");
                g_main_loop_quit(m_loop.get());
                break;

            case GST_MESSAGE_ERROR: {
                GError* e = nullptr; gchar* d = nullptr;
                gst_message_parse_error(msg, &e, &d);
                LOGE("Bus ERROR from '%s': %s", GST_OBJECT_NAME(GST_MESSAGE_SRC(msg)), e->message);
                if (d) LOGE("Debug info: %s", d);
                g_error_free(e); g_free(d);
                g_main_loop_quit(m_loop.get());
                break;
            }

            case GST_MESSAGE_WARNING: {
                GError* e = nullptr; gchar* d = nullptr;
                gst_message_parse_warning(msg, &e, &d);
                LOGW("Bus WARNING from '%s': %s", GST_OBJECT_NAME(GST_MESSAGE_SRC(msg)), e->message);
                g_error_free(e); g_free(d);
                break;
            }
            default:
                break;
        }
        return TRUE;
    }
};


// -------------------------------------------------------------------------
// JNI Entry Points
// -------------------------------------------------------------------------

extern "C" JNIEXPORT jlong JNICALL
Java_com_dhyey_screencapturegstreamerandroid_CaptureService_nativeCreate(
        JNIEnv* env, jobject /*thiz*/,
        jint w, jint h, jint fps, jint out_fd, jint bitrate_bps, jstring gst_parser) {

    const char* parserStr = env->GetStringUTFChars(gst_parser, nullptr);
    std::string parser(parserStr);
    env->ReleaseStringUTFChars(gst_parser, parserStr);

    try {
        auto* rec = new ScreenRecorder(w, h, fps, out_fd, bitrate_bps, parser);
        return reinterpret_cast<jlong>(rec);
    } catch (const std::exception& e) {
        LOGE("Failed to create pipeline: %s", e.what());
        return 0;
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_dhyey_screencapturegstreamerandroid_CaptureService_nativeStart(
        JNIEnv* /*env*/, jobject /*thiz*/, jlong handle) {
    if (handle) {
        reinterpret_cast<ScreenRecorder*>(handle)->start();
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_dhyey_screencapturegstreamerandroid_CaptureService_nativePushEncodedFrame(
        JNIEnv* env, jobject /*thiz*/,
        jlong handle, jobject buf, jint size, jlong ts_us, jint flags) {

    if (!handle) return;

    uint8_t* px = static_cast<uint8_t*>(env->GetDirectBufferAddress(buf));
    if (px) {
        reinterpret_cast<ScreenRecorder*>(handle)->pushEncodedFrame(px, size, ts_us, flags);
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_dhyey_screencapturegstreamerandroid_CaptureService_nativeStop(
        JNIEnv* /*env*/, jobject /*thiz*/, jlong handle) {
    if (handle) {
        reinterpret_cast<ScreenRecorder*>(handle)->stop();
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_dhyey_screencapturegstreamerandroid_CaptureService_nativeDestroy(
        JNIEnv* /*env*/, jobject /*thiz*/, jlong handle) {
    if (handle) {
        delete reinterpret_cast<ScreenRecorder*>(handle);
    }
}

JNIEXPORT jint JNI_OnLoad(JavaVM* vm, void* /*reserved*/) {
    LOGI("Native Library Loaded.");
    return JNI_VERSION_1_6;
}