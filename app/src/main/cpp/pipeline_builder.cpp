#include "pipeline_builder.h"

#include <sstream>
#include <stdexcept>

std::string videoAppsrcCaps(const std::string& parser) {
    if (parser == "h265parse") {
        return "video/x-h265,alignment=au";
    }
    if (parser == "av1parse") {
        return "video/x-av1,alignment=au";
    }
    return "video/x-h264,alignment=au";
}

namespace {

std::string withParserConfig(const std::string& parser) {
    if (parser == "h264parse" || parser == "h265parse" || parser == "av1parse") {
        return parser + " config-interval=-1";
    }
    return parser;
}

std::string muxProperties(bool hasVideo) {
    // Audio-only has no video keyframes; duration chunking writes fragments reliably.
    // With video, keep keyframe-aligned fragments for progressive playback.
    if (hasVideo) {
        return "fragment-duration=1000000000 "
               "chunk-mode=keyframe "
               "header-update-mode=rewrite "
               "send-force-keyunit=true "
               "write-mfra=true "
               "offset-to-zero=true";
    }
    return "fragment-duration=1000000000 "
           "chunk-mode=duration "
           "header-update-mode=rewrite "
           "write-mfra=true "
           "offset-to-zero=true";
}

}  // namespace

std::string buildMuxPipeline(const PipelineConfig& config) {
    if (!config.hasVideo && !config.hasAudio) {
        throw std::runtime_error("Pipeline needs at least video or audio");
    }
    if (config.fd < 0) {
        throw std::runtime_error("Invalid output file descriptor");
    }

    std::ostringstream out;

    if (config.hasVideo) {
        out << "appsrc name=vsrc is-live=true format=time block=false do-timestamp=false"
            << " ! " << withParserConfig(config.videoParser)
            << " ! queue max-size-buffers=60 max-size-bytes=0 max-size-time=0 leaky=downstream"
            << " ! mux. ";
    }

    if (config.hasAudio) {
        // Caps (incl. AAC codec_data) are set at runtime from MediaCodec CSD.
        out << "appsrc name=asrc is-live=true format=time block=false do-timestamp=false"
            << " ! aacparse"
            << " ! queue max-size-buffers=60 max-size-bytes=0 max-size-time=0 leaky=downstream"
            << " ! mux. ";
    }

    out << "isofmp4mux name=mux " << muxProperties(config.hasVideo)
        << " ! fdsink fd=" << config.fd;

    return out.str();
}
