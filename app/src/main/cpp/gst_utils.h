#pragma once

#include <gst/gst.h>
#include <memory>

template <typename T>
struct GstObjectDeleter {
    void operator()(T* p) const {
        if (p) {
            gst_object_unref(p);
        }
    }
};

template <typename T>
using GstObjPtr = std::unique_ptr<T, GstObjectDeleter<T>>;

struct GstCapsDeleter {
    void operator()(GstCaps* p) const {
        if (p) {
            gst_caps_unref(p);
        }
    }
};

using GstCapsPtr = std::unique_ptr<GstCaps, GstCapsDeleter>;
