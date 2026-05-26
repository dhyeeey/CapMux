package com.dhyey.screencapturegstreamerandroid

import android.app.Application
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

class ScreenCaptureViewModel(app: Application) : AndroidViewModel(app) {

    enum class State { IDLE, CAPTURING }

    // State is driven by CaptureState, which the service updates when capture
    // actually starts/stops — so the UI is always in sync with reality.
    val state = CaptureState.isCapturing
        .map { if (it) State.CAPTURING else State.IDLE }
        .stateIn(viewModelScope, SharingStarted.Eagerly, State.IDLE)

    val projectionManager: MediaProjectionManager
        get() = getApplication<Application>()
            .getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

    fun onPermissionGranted(resultCode: Int, data: Intent) {
        val ctx = getApplication<Application>()
        Intent(ctx, CaptureService::class.java).also {
            it.action = CaptureService.ACTION_START
            it.putExtra(CaptureService.EXTRA_RESULT_CODE, resultCode)
            it.putExtra(CaptureService.EXTRA_RESULT_DATA, data)
            ctx.startForegroundService(it)
        }
        // Do NOT set state here — CaptureState.isCapturing is set by the
        // service when it actually starts, keeping UI and notification in sync.
    }

    fun stopCapture() {
        val ctx = getApplication<Application>()
        Intent(ctx, CaptureService::class.java).also {
            it.action = CaptureService.ACTION_STOP
            ctx.startService(it)
        }
        // State will flip to IDLE when the service finishes cleanup.
    }
}