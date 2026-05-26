cmake_minimum_required(VERSION 3.22.1)

project("screencapturegstreamerandroid")

# Ensure GSTREAMER_ROOT_ANDROID is set
if(NOT DEFINED GSTREAMER_ROOT_ANDROID)
    set(GSTREAMER_ROOT_ANDROID $ENV{GSTREAMER_ROOT_ANDROID})
endif()

if(NOT GSTREAMER_ROOT_ANDROID)
    message(FATAL_ERROR "GSTREAMER_ROOT_ANDROID is not set. Please set it in build.gradle or as an environment variable.")
endif()

# Map Android ABI to GStreamer architecture folder names
if(ANDROID_ABI STREQUAL "armeabi-v7a")
    set(GST_ARCH "armv7")
elseif(ANDROID_ABI STREQUAL "arm64-v8a")
    set(GST_ARCH "arm64")
elseif(ANDROID_ABI STREQUAL "x86")
    set(GST_ARCH "x86")
elseif(ANDROID_ABI STREQUAL "x86_64")
    set(GST_ARCH "x86_64")
else()
    message(FATAL_ERROR "Unsupported ABI: ${ANDROID_ABI}")
endif()

set(GSTREAMER_ROOT "${GSTREAMER_ROOT_ANDROID}/${GST_ARCH}")
set(ENV{GSTREAMER_ROOT} "${GSTREAMER_ROOT}")

# IMPORTANT: Allow CMake to find libraries/headers outside the NDK sysroot
set(CMAKE_FIND_ROOT_PATH_MODE_LIBRARY BOTH)
set(CMAKE_FIND_ROOT_PATH_MODE_INCLUDE BOTH)
set(CMAKE_FIND_ROOT_PATH_MODE_PACKAGE BOTH)

# Add GStreamer root to search paths
list(APPEND CMAKE_PREFIX_PATH "${GSTREAMER_ROOT}")
list(APPEND CMAKE_FIND_ROOT_PATH "${GSTREAMER_ROOT}")

# Help FindGStreamer.cmake find the plugin static libraries (.a files)
# In Android universal binaries, plugins are often in lib/gstreamer-1.0
list(APPEND CMAKE_LIBRARY_PATH 
    "${GSTREAMER_ROOT}/lib"
    "${GSTREAMER_ROOT}/lib/gstreamer-1.0"
)

# Set PKG_CONFIG_LIBDIR so GStreamer can find its .pc files correctly for the target.
# On Windows, FindGStreamer.cmake sets PKG_CONFIG_DONT_DEFINE_PREFIX=1 which causes
# pkgconf to emit doubled paths like 'lib/lib/gstreamer-1.0'. We merge both pkgconfig
# dirs into PKG_CONFIG_LIBDIR (using semicolon, the Windows separator) and clear PATH.
set(ENV{PKG_CONFIG_LIBDIR} "${GSTREAMER_ROOT}/lib/pkgconfig;${GSTREAMER_ROOT}/lib/gstreamer-1.0/pkgconfig;${GSTREAMER_ROOT}/lib/gio/modules/pkgconfig")
unset(ENV{PKG_CONFIG_PATH})
set(ENV{PKG_CONFIG_DONT_DEFINE_PREFIX} 1)

# ── Windows/pkgconf workaround ────────────────────────────────────────────────
# FindGStreamer._gst_find_library() uses NO_DEFAULT_PATH + NO_CMAKE_FIND_ROOT_PATH
# and only searches the HINTS from PC_*_STATIC_LIBRARY_DIRS. On Windows those are
# broken (doubled 'lib/lib/'). BUT the function returns immediately if the cache
# variable is already DEFINED (line 111: "if (DEFINED ${GST_LOCAL_LIB}) return()").
# So we pre-seed every _gst_<name> cache variable by globbing the real lib dirs,
# which makes find_library() never get called for those libraries.
set(_gst_lib_dirs
    "${GSTREAMER_ROOT}/lib"
    "${GSTREAMER_ROOT}/lib/gstreamer-1.0"
)
foreach(_gst_lib_dir IN LISTS _gst_lib_dirs)
    file(GLOB _gst_lib_files "${_gst_lib_dir}/lib*.a")
    foreach(_gst_lib_file IN LISTS _gst_lib_files)
        get_filename_component(_gst_lib_basename "${_gst_lib_file}" NAME_WE)
        # Strip the leading 'lib' prefix to get the bare library name
        string(REGEX REPLACE "^lib" "" _gst_lib_name "${_gst_lib_basename}")
        # Compute the cache var name the same way FindGStreamer.cmake does:
        # string(MAKE_C_IDENTIFIER "_gst_${LOCAL_LIB}" GST_LOCAL_LIB)
        string(MAKE_C_IDENTIFIER "_gst_${_gst_lib_name}" _gst_cache_var)
        if(NOT DEFINED ${_gst_cache_var})
            set(${_gst_cache_var} "${_gst_lib_file}" CACHE FILEPATH "Pre-seeded by CMakeLists.txt" FORCE)
        endif()
    endforeach()
endforeach()
unset(_gst_lib_dirs)
unset(_gst_lib_files)
unset(_gst_lib_file)
unset(_gst_lib_basename)
unset(_gst_lib_name)
unset(_gst_cache_var)

set(GStreamer_ROOT_DIR "${GSTREAMER_ROOT}")

# Fix: FindGStreamerMobile.cmake's else() branch prepends "${CMAKE_CURRENT_SOURCE_DIR}/../"
# to these vars when they are already defined. So we must pass RELATIVE paths only
# (relative to the parent of the cpp dir, i.e. relative to main/).
# "java/"   → main/cpp/../java/   → main/java/   ✓
# "assets/" → main/cpp/../assets/ → main/assets/ ✓
set(GStreamer_JAVA_SRC_DIR "java/")
set(GStreamer_ASSETS_DIR "assets/")

# Add GStreamer's CMake modules to search path
list(APPEND CMAKE_MODULE_PATH "${GSTREAMER_ROOT}/share/cmake")

# Include the plugins list from GStreamer's ndk-build integration
include("${GSTREAMER_ROOT}/share/gst-android/ndk-build/plugins.cmake")

set(GSTREAMER_PLUGINS
        ${GSTREAMER_PLUGINS_CORE}
        ${GSTREAMER_PLUGINS_SYS}
        ${GSTREAMER_PLUGINS_CODECS}
        ${GSTREAMER_PLUGINS_PLAYBACK}
        ${GSTREAMER_PLUGINS_NET}
        ${GSTREAMER_PLUGINS_CODECS_GPL}

        app
        videoconvertscale  # Reverted back to the unified plugin name
        videorate
        videoparsersbad
        isomp4
        x264
        androidmedia
        mpegtsmux
)

# Standard Android libraries
find_library(ANDROID_LIB android REQUIRED)
find_library(LOG_LIB log REQUIRED)

set(GStreamer_USE_STATIC_LIBS ON)

# Locate GStreamer and its components
# We use GStreamerMobile which is designed for Android static builds
find_package(GStreamerMobile
        COMPONENTS
        ${GSTREAMER_PLUGINS}
        fonts
        ca_certificates
        REQUIRED
)

add_library(${CMAKE_PROJECT_NAME} SHARED
        screen_capture.cpp
)

target_link_libraries(${CMAKE_PROJECT_NAME}
        PUBLIC
        GStreamer::mobile
        ${ANDROID_LIB}
        ${LOG_LIB}
)

set_target_properties(${CMAKE_PROJECT_NAME}
        PROPERTIES
        C_VISIBILITY_PRESET hidden
        CXX_VISIBILITY_PRESET hidden
)
