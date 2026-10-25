package com.unsupportedpastels.hermesandroid.voice

import kotlinx.coroutines.flow.StateFlow

/** Lifecycle of app-owned composer dictation. */
enum class DeviceSpeechRecognizerState {
    Idle,
    Preparing,
    Listening,
    Restarting,
    Finishing,
}

/** Platform speech input seam shared by native controls and their permission coordinator. */
internal interface DeviceSpeechRecognizer {
    val state: StateFlow<DeviceSpeechRecognizerState>
    val isActive: Boolean

    fun start(
        currentDraft: String,
        onDraftChanged: (String) -> Unit,
        onError: (String) -> Unit,
    ): Boolean

    /** Stop capture and allow the provider to finish its transcript. */
    fun finish()

    /** Discard this dictation and restore the draft present at its start. */
    fun cancel() = finish()

    /** End immediately without changing the draft already visible to the user. */
    fun abort() = finish()
}
