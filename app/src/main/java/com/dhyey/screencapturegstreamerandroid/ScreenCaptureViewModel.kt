package com.dhyey.screencapturegstreamerandroid

import android.app.Application
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.dhyey.screencapturegstreamerandroid.codec.CodecManager
import com.dhyey.screencapturegstreamerandroid.codec.VideoCodec
import com.dhyey.screencapturegstreamerandroid.recording.BitrateTier
import com.dhyey.screencapturegstreamerandroid.recording.CaptureFps
import com.dhyey.screencapturegstreamerandroid.recording.CaptureQuality
import com.dhyey.screencapturegstreamerandroid.recording.CaptureResolution
import com.dhyey.screencapturegstreamerandroid.recording.CaptureState
import com.dhyey.screencapturegstreamerandroid.recording.DisplayCapabilities
import com.dhyey.screencapturegstreamerandroid.recording.QualityPreset
import com.dhyey.screencapturegstreamerandroid.recording.RecordingConfig
import com.dhyey.screencapturegstreamerandroid.recording.VideoCodecOption
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update

class ScreenCaptureViewModel(app: Application) : AndroidViewModel(app) {

    enum class State { IDLE, CAPTURING }

    val displayCaps: DisplayCapabilities =
        DisplayCapabilities.from(getApplication())

    val availableResolutions: List<CaptureResolution> =
        displayCaps.availableResolutions()

    val availableFpsOptions: List<CaptureFps> =
        displayCaps.availableFpsOptions()

    val hevcAvailable: Boolean =
        CodecManager.findHardwareEncoder(VideoCodec.HEVC.mime) != null

    private val _config = MutableStateFlow(
        RecordingConfig(quality = CaptureQuality.defaultFor(displayCaps).let { q ->
            if (q.codec == VideoCodecOption.H265 && !hevcAvailable) {
                q.copy(codec = VideoCodecOption.H264)
            } else {
                q
            }
        }),
    )
    val config: StateFlow<RecordingConfig> = _config.asStateFlow()

    val state = CaptureState.isCapturing
        .map { if (it) State.CAPTURING else State.IDLE }
        .stateIn(viewModelScope, SharingStarted.Eagerly, State.IDLE)

    val projectionManager: MediaProjectionManager
        get() = getApplication<Application>()
            .getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

    fun setCaptureScreen(enabled: Boolean) {
        _config.update {
            it.copy(
                captureScreen = enabled,
                showTaps = if (enabled) it.showTaps else false,
            )
        }
    }

    fun setCaptureMic(enabled: Boolean) {
        _config.update { it.copy(captureMic = enabled) }
    }

    fun setCaptureInternalAudio(enabled: Boolean) {
        _config.update { it.copy(captureInternalAudio = enabled) }
    }

    fun setShowTaps(enabled: Boolean) {
        _config.update { it.copy(showTaps = enabled && it.captureScreen) }
    }

    fun setQualityPreset(preset: QualityPreset) {
        if (preset == QualityPreset.CUSTOM) {
            _config.update { it.copy(quality = it.quality.copy(preset = QualityPreset.CUSTOM)) }
            return
        }
        var quality = CaptureQuality.fromPreset(preset, displayCaps)
        if (quality.codec == VideoCodecOption.H265 && !hevcAvailable) {
            quality = quality.copy(codec = VideoCodecOption.H264)
        }
        _config.update { it.copy(quality = quality) }
    }

    fun setResolution(resolution: CaptureResolution) {
        if (resolution !in availableResolutions) return
        updateQualityCustom { it.copy(resolution = resolution) }
    }

    fun setFps(fps: CaptureFps) {
        if (fps !in availableFpsOptions) return
        updateQualityCustom { it.copy(fps = fps) }
    }

    fun setBitrate(bitrate: BitrateTier) {
        updateQualityCustom { it.copy(bitrate = bitrate) }
    }

    fun setCodec(codec: VideoCodecOption) {
        val resolved = if (codec == VideoCodecOption.H265 && !hevcAvailable) {
            VideoCodecOption.H264
        } else {
            codec
        }
        updateQualityCustom { it.copy(codec = resolved) }
    }

    private fun updateQualityCustom(transform: (CaptureQuality) -> CaptureQuality) {
        _config.update { cfg ->
            val next = transform(cfg.quality)
                .clampedTo(displayCaps)
                .copy(preset = QualityPreset.CUSTOM)
            cfg.copy(quality = next)
        }
    }

    fun canStart(): Boolean = _config.value.isValid()

    /** Start without MediaProjection (mic-only). */
    fun startWithoutProjection() {
        startService(resultCode = null, data = null)
    }

    fun onPermissionGranted(resultCode: Int, data: Intent) {
        startService(resultCode, data)
    }

    private fun startService(resultCode: Int?, data: Intent?) {
        val cfg = _config.value
        if (!cfg.isValid()) return

        val ctx = getApplication<Application>()
        Intent(ctx, CaptureService::class.java).also { intent ->
            intent.action = CaptureService.ACTION_START
            cfg.writeToIntent(intent)
            if (resultCode != null && data != null) {
                intent.putExtra(CaptureService.EXTRA_RESULT_CODE, resultCode)
                intent.putExtra(CaptureService.EXTRA_RESULT_DATA, data)
            }
            ctx.startForegroundService(intent)
        }
    }

    fun stopCapture() {
        val ctx = getApplication<Application>()
        Intent(ctx, CaptureService::class.java).also {
            it.action = CaptureService.ACTION_STOP
            ctx.startService(it)
        }
    }
}
