#pragma once

#include <string>

struct PipelineConfig {
    bool hasVideo = false;
    bool hasAudio = false;
    int fd = -1;
    int fps = 30;
    std::string videoParser = "h264parse";
};

/** Builds a gst_parse_launch pipeline string for isofmp4mux + fdsink. */
std::string buildMuxPipeline(const PipelineConfig& config);

/** Caps for the video appsrc matching [PipelineConfig::videoParser]. */
std::string videoAppsrcCaps(const std::string& parser);
