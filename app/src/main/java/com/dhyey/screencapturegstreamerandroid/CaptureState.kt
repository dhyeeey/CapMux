package com.dhyey.screencapturegstreamerandroid

import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Singleton that holds the true capture state, updated by [CaptureService].
 * Both the service and the ViewModel read/write this so the UI always reflects
 * the actual service lifecycle rather than optimistic ViewModel guesses.
 */
object CaptureState {
    val isCapturing = MutableStateFlow(false)
}
