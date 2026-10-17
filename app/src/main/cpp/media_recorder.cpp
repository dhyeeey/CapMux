#include "media_recorder.h"

#include <android/log.h>

#include <cstring>
#include <stdexcept>

#define TAG "MediaRecorderNative"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)

namespace {
constexpr int kAacSamplesPerFrame = 1024;
constexpr int kDefaultAudioSampleRate = 48000;
constexpr int kDefaultAudioChannels = 2;
constexpr uint32_t kFlagCodecConfig = 2;
}  // namespace

MediaRecorder::MediaRecorder(const PipelineConfig& config) : m_config(config) {
    gst_init(nullptr, nullptr);

    const std::string pipelineStr = buildMuxPipeline(m_config);
    LOGI("Pipeline:\n%s", pipelineStr.c_str());

    GError* err = nullptr;
    m_pipeline.reset(gst_parse_launch(pipelineStr.c_str(), &err));
    if (!m_pipeline || err) {
        std::string msg = err ? err->message : "unknown";
        if (err) {
            g_error_free(err);
        }
        throw std::runtime_error(msg);
    }

    if (m_config.hasVideo) {
        m_videoSrc.reset(GST_APP_SRC(gst_bin_get_by_name(GST_BIN(m_pipeline.get()), "vsrc")));
        if (!m_videoSrc) {
            throw std::runtime_error("Failed to get video appsrc");
        }
        const std::string capsStr = videoAppsrcCaps(m_config.videoParser);
        GstCapsPtr caps(gst_caps_from_string(capsStr.c_str()));
        g_object_set(m_videoSrc.get(), "caps", caps.get(), nullptr);
        LOGI("Video appsrc caps: %s (parser=%s)", capsStr.c_str(), m_config.videoParser.c_str());
    }

    if (m_config.hasAudio) {
        m_audioSrc.reset(GST_APP_SRC(gst_bin_get_by_name(GST_BIN(m_pipeline.get()), "asrc")));
        if (!m_audioSrc) {
            throw std::runtime_error("Failed to get audio appsrc");
        }
        // Do NOT set stream-format=raw caps here — aacparse requires codec_data first.
        // Caps are applied from MediaCodec CSD in pushAudio().
    }

    GstBus* bus = gst_element_get_bus(m_pipeline.get());
    gst_bus_add_watch(
        bus,
        [](GstBus*, GstMessage* msg, gpointer) -> gboolean {
            switch (GST_MESSAGE_TYPE(msg)) {
                case GST_MESSAGE_ERROR: {
                    GError* e = nullptr;
                    gchar* dbg = nullptr;
                    gst_message_parse_error(msg, &e, &dbg);
                    LOGE("GST ERROR: %s", e->message);
                    if (dbg) {
                        LOGE("DEBUG: %s", dbg);
                    }
                    g_error_free(e);
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

MediaRecorder::~MediaRecorder() {
    if (m_pipeline) {
        gst_element_set_state(m_pipeline.get(), GST_STATE_NULL);
    }
}

bool MediaRecorder::start() {
    const GstStateChangeReturn ret =
        gst_element_set_state(m_pipeline.get(), GST_STATE_PLAYING);
    if (ret == GST_STATE_CHANGE_FAILURE) {
        LOGE("Failed PLAYING state");
        return false;
    }
    LOGI("Pipeline started");
    return true;
}

void MediaRecorder::stop() {
    if (m_eos.exchange(true)) {
        return;
    }

    LOGI("Sending EOS");
    if (m_videoSrc) {
        gst_app_src_end_of_stream(m_videoSrc.get());
    }
    if (m_audioSrc) {
        gst_app_src_end_of_stream(m_audioSrc.get());
    }

    gst_element_get_state(m_pipeline.get(), nullptr, nullptr, 5 * GST_SECOND);
}

void MediaRecorder::pushVideo(
    const uint8_t* data, size_t size, uint64_t tsUs, uint32_t flags) {
    if (!m_videoSrc) {
        return;
    }
    const GstClockTime duration =
        gst_util_uint64_scale_int(1, GST_SECOND, m_config.fps > 0 ? m_config.fps : 30);
    pushToAppSrc(
        m_videoSrc.get(), data, size, tsUs, flags, duration, m_videoFirstPts, /*intraOnly=*/false);
}

bool MediaRecorder::setAacCapsFromCodecData(const uint8_t* data, size_t size) {
    if (!m_audioSrc || !data || size == 0) {
        return false;
    }

    GstBuffer* csd = gst_buffer_new_allocate(nullptr, size, nullptr);
    GstMapInfo map;
    gst_buffer_map(csd, &map, GST_MAP_WRITE);
    memcpy(map.data, data, size);
    gst_buffer_unmap(csd, &map);

    GstCaps* caps = gst_caps_new_simple(
        "audio/mpeg",
        "mpegversion", G_TYPE_INT, 4,
        "stream-format", G_TYPE_STRING, "raw",
        "rate", G_TYPE_INT, kDefaultAudioSampleRate,
        "channels", G_TYPE_INT, kDefaultAudioChannels,
        "codec_data", GST_TYPE_BUFFER, csd,
        nullptr);
    gst_buffer_unref(csd);

    if (!caps) {
        LOGE("Failed to build AAC caps with codec_data");
        return false;
    }

    g_object_set(m_audioSrc.get(), "caps", caps, nullptr);
    gst_caps_unref(caps);
    m_audioCapsReady = true;
    LOGI("AAC appsrc caps set with codec_data (%zu bytes)", size);
    return true;
}

void MediaRecorder::pushAudio(
    const uint8_t* data, size_t size, uint64_t tsUs, uint32_t flags) {
    if (!m_audioSrc || !data || size == 0 || m_eos) {
        return;
    }

    // MediaCodec BUFFER_FLAG_CODEC_CONFIG — AudioSpecificConfig for aacparse.
    if (flags & kFlagCodecConfig) {
        setAacCapsFromCodecData(data, size);
        return;
    }

    if (!m_audioCapsReady) {
        LOGW("Dropping AAC AU before codec_data (%zu bytes)", size);
        return;
    }

    const GstClockTime duration = gst_util_uint64_scale_int(
        kAacSamplesPerFrame, GST_SECOND, kDefaultAudioSampleRate);
    // AAC is intra-only; isofmp4mux rejects delta-unit flags on such streams.
    pushToAppSrc(
        m_audioSrc.get(), data, size, tsUs, flags, duration, m_audioFirstPts, /*intraOnly=*/true);
}

void MediaRecorder::pushToAppSrc(
    GstAppSrc* appsrc,
    const uint8_t* data,
    size_t size,
    uint64_t tsUs,
    uint32_t flags,
    GstClockTime duration,
    GstClockTime& firstPts,
    bool intraOnly) {
    if (m_eos || !appsrc || !data || size == 0) {
        return;
    }

    const uint64_t tsNs = tsUs * 1000ULL;
    if (firstPts == GST_CLOCK_TIME_NONE) {
        firstPts = tsNs;
    }
    const GstClockTime pts = tsNs - firstPts;

    GstBuffer* buffer = gst_buffer_new_allocate(nullptr, size, nullptr);
    GstMapInfo map;
    gst_buffer_map(buffer, &map, GST_MAP_WRITE);
    memcpy(map.data, data, size);
    gst_buffer_unmap(buffer, &map);

    GST_BUFFER_PTS(buffer) = pts;
    GST_BUFFER_DTS(buffer) = pts;
    GST_BUFFER_DURATION(buffer) = duration;

    if (flags & kFlagCodecConfig) {
        GST_BUFFER_FLAG_SET(buffer, GST_BUFFER_FLAG_HEADER);
    }

    if (intraOnly || (flags & 1)) {
        GST_BUFFER_FLAG_UNSET(buffer, GST_BUFFER_FLAG_DELTA_UNIT);
    } else {
        GST_BUFFER_FLAG_SET(buffer, GST_BUFFER_FLAG_DELTA_UNIT);
    }

    const GstFlowReturn ret = gst_app_src_push_buffer(appsrc, buffer);
    if (ret != GST_FLOW_OK) {
        LOGW("push failed: %s", gst_flow_get_name(ret));
    }
}
