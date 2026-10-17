package com.dhyey.screencapturegstreamerandroid.recording

import kotlinx.coroutines.flow.MutableStateFlow

object CaptureState {
    val isCapturing = MutableStateFlow(false)
    val activeConfig = MutableStateFlow<RecordingConfig?>(null)
}
