#pragma once

#include "gst_utils.h"
#include "pipeline_builder.h"

#include <gst/app/gstappsrc.h>

#include <atomic>
#include <cstdint>
#include <string>

class MediaRecorder {
public:
    MediaRecorder(const PipelineConfig& config);
    ~MediaRecorder();

    MediaRecorder(const MediaRecorder&) = delete;
    MediaRecorder& operator=(const MediaRecorder&) = delete;

    bool start();
    void stop();

    void pushVideo(const uint8_t* data, size_t size, uint64_t tsUs, uint32_t flags);
    void pushAudio(const uint8_t* data, size_t size, uint64_t tsUs, uint32_t flags);

private:
    void pushToAppSrc(
        GstAppSrc* appsrc,
        const uint8_t* data,
        size_t size,
        uint64_t tsUs,
        uint32_t flags,
        GstClockTime duration,
        GstClockTime& firstPts,
        bool intraOnly);

    /** Apply AAC AudioSpecificConfig from MediaCodec CSD into appsrc caps. */
    bool setAacCapsFromCodecData(const uint8_t* data, size_t size);

    PipelineConfig m_config;
    GstClockTime m_videoFirstPts = GST_CLOCK_TIME_NONE;
    GstClockTime m_audioFirstPts = GST_CLOCK_TIME_NONE;
    std::atomic<bool> m_eos{false};
    bool m_audioCapsReady = false;

    GstObjPtr<GstElement> m_pipeline;
    GstObjPtr<GstAppSrc> m_videoSrc;
    GstObjPtr<GstAppSrc> m_audioSrc;
};
