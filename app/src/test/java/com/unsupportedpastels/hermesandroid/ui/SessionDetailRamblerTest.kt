package com.unsupportedpastels.hermesandroid.ui

import android.Manifest
import android.app.Application
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.unsupportedpastels.hermesandroid.app.DurableSessionId
import com.unsupportedpastels.hermesandroid.app.SessionSummary
import com.unsupportedpastels.hermesandroid.gateway.ChatSessionSnapshot
import com.unsupportedpastels.hermesandroid.theme.HermesAndroidTheme
import com.unsupportedpastels.hermesandroid.voice.DeviceSpeechRecognizer
import com.unsupportedpastels.hermesandroid.voice.DeviceSpeechRecognizerState
import com.unsupportedpastels.hermesandroid.voice.RamblerSettingsStore
import com.unsupportedpastels.hermesandroid.voice.RamblerBetaSettings
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class SessionDetailRamblerTest {
    @get:Rule val rule = createComposeRule()
    private val draft = mutableStateOf("Existing draft")
    private val scope = mutableStateOf("origin/default/session")
    private var sends = 0
    private val recognizer = FakeRecognizer()
    private val beta = object : RamblerBetaSettings {
        override val enabled = MutableStateFlow(true)
        override suspend fun setEnabled(enabled: Boolean) { this.enabled.value = enabled }
    }
    private var keyReads = 0

    private inner class FakeStore(var key: String?) : RamblerSettingsStore {
        override suspend fun loadApiKey(): String? { keyReads++; return key }
        override suspend fun saveApiKey(apiKey: String) { key = apiKey }
        override suspend fun clearApiKey() { key = null }
    }

    private class FakeRecognizer : DeviceSpeechRecognizer {
        override val state = MutableStateFlow(DeviceSpeechRecognizerState.Idle)
        override val isActive get() = state.value != DeviceSpeechRecognizerState.Idle
        var starts = 0
        var aborts = 0
        var draftCallback: ((String) -> Unit)? = null
        override fun start(currentDraft: String, onDraftChanged: (String) -> Unit, onError: (String) -> Unit): Boolean {
            starts++
            draftCallback = onDraftChanged
            state.value = DeviceSpeechRecognizerState.Listening
            return true
        }
        override fun finish() { state.value = DeviceSpeechRecognizerState.Finishing }
        override fun abort() {
            if (isActive) aborts++
            state.value = DeviceSpeechRecognizerState.Idle
            draftCallback = null
        }
    }

    private fun render(key: String? = "test-key", enabled: Boolean = false) {
        beta.enabled.value = enabled
        shadowOf(ApplicationProvider.getApplicationContext<Application>())
            .grantPermissions(Manifest.permission.RECORD_AUDIO)
        val store = FakeStore(key)
        rule.setContent {
            HermesAndroidTheme {
                SessionDetailScreen(
                    session = SessionSummary(id = DurableSessionId("session"), title = "Session", preview = ""),
                    chat = ChatSessionSnapshot(), voiceInputScopeKey = scope.value,
                    ramblerSettingsStore = store, ramblerBetaSettings = beta, speechRecognizer = recognizer,
                    draft = draft.value, onDraftChanged = { draft.value = it }, canSend = true,
                    attachments = emptyList(), hostReferences = emptyList(),
                    onAddAttachments = { emptyList() }, onRemoveAttachment = {}, onRemoveHostReference = {},
                    onSend = { sends++ }, onSteer = {}, onReasoningSelected = {}, onFastSelected = {},
                    onOpenModelPicker = {}, onClarificationResponse = { _, _, _ -> },
                    onApprovalResponse = { _, _ -> }, onBlockingResponse = { _, _, _ -> },
                    showStop = false, stopping = false, onStop = {}, onLoadSessionInsights = {},
                    maintenanceAvailable = false, maintenanceEnabled = false, onCompressSession = {},
                    onUndoSession = {}, onBranchSession = { _, _ -> }, showBack = false, onBack = {},
                    onLoadManagedImage = { Result.failure(Exception("unused")) },
                    onLoadHostFiles = { Result.failure(Exception("unused")) },
                    onLoadManagedFile = { Result.failure(Exception("unused")) }, onAttachHostReference = {},
                )
            }
        }
    }

    @Test fun manualKeyDictationWorksWithGoogleSetupBetaOff() {
        render(enabled = false)
        rule.onNodeWithContentDescription("Voice input").assertIsEnabled().performClick()
        rule.onNodeWithText("Continue with Google").assertDoesNotExist()
        rule.runOnIdle { assertEquals(1, recognizer.starts); assertEquals(1, keyReads) }
    }

    @Test fun disablingGoogleSetupBetaDoesNotInterruptRegularDictation() {
        render(enabled = true)
        rule.onNodeWithContentDescription("Voice input").performClick()
        rule.runOnIdle {
            recognizer.draftCallback?.invoke("Existing draft dictated")
            beta.enabled.value = false
        }
        rule.onNodeWithContentDescription("Stop voice input").assertIsEnabled()
        rule.runOnIdle {
            assertEquals(0, recognizer.aborts)
            assertEquals("Existing draft dictated", draft.value)
            assertEquals(0, sends)
        }
    }

    @Test fun firstUseOpensKeySetupWithoutStartingRecognition() {
        render(key = null)
        rule.onNodeWithContentDescription("Voice input").assertIsEnabled().performClick()
        rule.onNodeWithText("Continue with Google").assertDoesNotExist()
        rule.onNodeWithContentDescription("Gemini API key").assertIsDisplayed()
        rule.runOnIdle { assertEquals(0, recognizer.starts); assertEquals("Existing draft", draft.value) }
    }

    @Test fun finalTranscriptMustArriveBeforeSendBecomesAvailable() {
        render()
        rule.onNodeWithContentDescription("Voice input").performClick()
        rule.runOnIdle {
            assertEquals(1, recognizer.starts)
            recognizer.draftCallback?.invoke("Existing draft partial")
        }
        rule.onNodeWithContentDescription("Send message").assertIsNotEnabled()
        rule.onNodeWithText("Existing draft partial").assertIsNotEnabled()
        rule.onNodeWithContentDescription("Stop voice input").performClick()
        rule.onNodeWithContentDescription("Send message").assertIsNotEnabled()
        rule.runOnIdle {
            recognizer.draftCallback?.invoke("Existing draft corrected.")
            recognizer.state.value = DeviceSpeechRecognizerState.Idle
            assertEquals(0, sends)
        }
        rule.onNodeWithContentDescription("Send message").assertIsEnabled().performClick()
        rule.runOnIdle { assertEquals(1, sends) }
    }

    @Test fun connectionScopeChangeStopsRecognitionAndPreservesVisibleDraft() {
        render()
        rule.onNodeWithContentDescription("Voice input").performClick()
        rule.runOnIdle {
            recognizer.draftCallback?.invoke("Existing draft dictated")
            scope.value = "another-origin/default/session"
        }
        rule.runOnIdle {
            assertEquals(1, recognizer.aborts)
            assertEquals("Existing draft dictated", draft.value)
            assertEquals(0, sends)
        }
    }
}
