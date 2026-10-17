# CapMux

**Cap**ture + **Mux** — Android media recorder that captures **screen**, **microphone**, and/or **internal (media) audio**, encodes with hardware `MediaCodec`, and muxes to fragmented MP4 / M4A with **GStreamer** (`isofmp4mux`) over JNI.

| | |
|---|---|
| **minSdk** | 28 (Android 9) |
| **target / compileSdk** | 36 |
| **Output (video)** | `Movies/ScreenCap/*.mp4` |
| **Output (audio-only)** | `Music/ScreenCap/*.m4a` |

---

## Features

### Sources (any combination)

- **Screen** — `MediaProjection` + `VirtualDisplay` → H.264 / H.265 hardware encode  
- **Microphone** — `AudioRecord` → AAC  
- **Media sound (internal)** — `AudioPlaybackCapture` (Android 10+) → AAC  
- Mic + internal are mixed in PCM before AAC when both are selected  

### Quality controls

Presets default to a lag-friendly **Recommended** profile. Options are filtered to what the device can actually do:

- Display size / short-edge tiers: 720p, 1080p, 1440p, 4K, Native  
- Frame rates up to the panel max (24 … 240 Hz where supported)  
- Bitrate tier: Low / Normal / High  
- Codec: H.264 or H.265 (HEVC) when a hardware encoder exists  

### Screen extras

- **Show taps** — tap indicators in the recording (Accessibility service on Android 13+, or system Show taps when writable)  
- Android’s own MediaProjection status indicator is used for “recording active” feedback (not drawn into the file)

---

## Architecture

```
UI (Compose) ── RecordingConfig ──► CaptureService (FGS)
                                      │
                    ┌─────────────────┼─────────────────┐
                    ▼                 ▼                 ▼
            ScreenVideoEncoder   AudioCapturePipeline   ScreenVisualAids
            (MediaCodec)         (mic / internal / mix) (optional taps)
                    │                 │
                    └────────┬────────┘
                             ▼
                    NativeMediaRecorder (JNI)
                             ▼
              GStreamer: appsrc → h264/h265parse / aacparse
                         → isofmp4mux → fdsink (MediaStore FD)
```

| Layer | Role |
|-------|------|
| Kotlin | Capture orchestration, UI, MediaCodec encode, permissions |
| C++ / GStreamer | Live mux to fMP4 on a file descriptor |

---

## Requirements

1. **Android Studio** (recent AGP / JDK 17+ as used by the project)  
2. **Android NDK + CMake** (for the native module)  
3. **GStreamer Android SDK** — universal build **1.28.8** (or set a custom path)

Place the SDK at the project root (default):

```text
mediacapture-android-gstreamer-app/
  gstreamer-1.0-android-universal-1.28.8/
  app/
  ...
```

Or override with either:

- Environment: `GSTREAMER_ROOT_ANDROID=/path/to/gstreamer-android-sdk`  
- `gradle.properties`: `gstreamer.root=/path/to/gstreamer-android-sdk`  

The GStreamer tree is gitignored; download it separately from the [GStreamer Android binaries](https://gstreamer.freedesktop.org/download/#android).

---

## Build & run

1. Open the project in Android Studio and sync Gradle.  
2. Ensure the GStreamer SDK path resolves (see above).  
3. Run **app** on a physical device (screen / internal audio need a real device).  
4. Grant prompts as needed: notifications, mic, MediaProjection, and for Show taps — Accessibility + display-over-apps (or Developer options → Show taps).

```bash
./gradlew :app:assembleDebug
```

---

## Using the app

1. Toggle **Screen** / **Microphone** / **Media sound**.  
2. With Screen on, pick a **Quality** preset or **Customize…** (resolution, fps, bitrate, codec).  
3. Optional: **Show taps**.  
4. **Start Recording** → accept the system capture dialog.  
5. Use other apps; recording continues in a foreground service.  
6. **Stop & Save** — file appears under Movies or Music → ScreenCap.

### Notes

- Internal audio requires **Android 10+**.  
- H.265 may need a moment of encoder warm-up on some SoCs; bitrate / keyframe handling is tuned to avoid the muddy opening seconds that VBR HEVC can show on UI content.  
- Higher presets (Native + high fps) increase GPU/encoder load and can make the device feel slower while recording.

---

## Project layout

```text
app/src/main/
  java/.../
    MainActivity.kt              # Compose UI
    CaptureService.kt            # Foreground capture service
    recording/                   # Config, quality, display caps
    video/                       # Screen encoder + sizing
    audio/                       # Mic, internal, mixer, AAC
    codec/                       # MediaCodec helpers
    overlay/                     # Tap visualization
    nativebridge/                # JNI wrapper
  cpp/
    jni_bridge.cpp
    media_recorder.cpp
    pipeline_builder.cpp
    CMakeLists.txt
```

---
