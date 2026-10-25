package com.unsupportedpastels.hermesandroid.voice

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.unsupportedpastels.mercury.core.voice.RamblerBetaResult
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = "w400dp-h1000dp")
class RamblerBetaSettingsSectionTest {
    @get:Rule val rule = createComposeRule()
    private var failWrites = false
    private val beta = object : RamblerBetaSettings {
        override val enabled = MutableStateFlow(false)
        override suspend fun setEnabled(enabled: Boolean) {
            if (failWrites) error("synthetic persistence failure")
            this.enabled.value = enabled
        }
    }
    private var keyReads = 0
    private var googleSetups = 0
    private val store = object : RamblerSettingsStore {
        override suspend fun loadApiKey(): String? { keyReads++; return null }
        override suspend fun saveApiKey(apiKey: String) = Unit
        override suspend fun clearApiKey() = Unit
    }

    private fun render(enrollment: RamblerBetaEnrollmentFactory? = null) {
        rule.setContent {
            RamblerBetaSettingsSection(store, beta, googleSetup = {
                googleSetups++
                RamblerGoogleSetupUi(onContinue = {})
            }, enrollment = enrollment)
        }
    }

    @Test fun defaultOffKeepsManualKeyEntryAndDoesNotCreateGoogleSetup() {
        render()
        rule.onNodeWithContentDescription("Enable Google setup beta").assertIsOff()
        rule.onNodeWithText("Continue with Google").assertDoesNotExist()
        rule.onNodeWithText("Optional experimental Google account login and automatic Gemini key setup.")
            .assertIsDisplayed()
        rule.onNodeWithContentDescription("Gemini API key").assertIsDisplayed()
        rule.runOnIdle { assertEquals(0, googleSetups) }
    }

    @Test fun enableRevealsSetupAndDisableHidesIt() {
        render()
        rule.onNodeWithContentDescription("Enable Google setup beta").performClick().assertIsOn()
        rule.onNodeWithText("Continue with Google").assertIsDisplayed()
        rule.onNodeWithContentDescription("Gemini API key").assertExists()
        rule.onNodeWithContentDescription("Enable Google setup beta").performClick().assertIsOff()
        rule.onNodeWithText("Continue with Google").assertDoesNotExist()
    }

    @Test fun hostedWithdrawalTurnsOffLocalBetaAndHidesGoogleSetup() {
        var status by mutableStateOf(RamblerBetaResult.Enrolled)
        render(enrollment = {
            RamblerBetaEnrollmentUi(available = true, result = status,
                onWithdraw = { status = RamblerBetaResult.Withdrawn })
        })
        rule.onNodeWithContentDescription("Enable Google setup beta").performClick()
        rule.onNodeWithText("Leave beta").performClick()
        rule.onNodeWithContentDescription("Enable Google setup beta").assertIsOff()
        rule.onNodeWithText("Continue with Google").assertDoesNotExist()
    }

    @Test fun failedLocalOptOutDoesNotClaimOffAndCanBeRetried() {
        var status by mutableStateOf(RamblerBetaResult.Enrolled)
        render(enrollment = {
            RamblerBetaEnrollmentUi(available = true, result = status,
                onWithdraw = { status = RamblerBetaResult.Withdrawn })
        })
        rule.onNodeWithContentDescription("Enable Google setup beta").performClick()
        rule.runOnIdle { failWrites = true }
        rule.onNodeWithText("Leave beta").performClick()
        rule.onNodeWithContentDescription("Enable Google setup beta").assertIsOn()
        rule.onNodeWithText("Could not turn off Google setup beta. Turn the switch off to retry.")
            .assertIsDisplayed()
        rule.runOnIdle { failWrites = false }
        rule.onNodeWithContentDescription("Enable Google setup beta").performClick().assertIsOff()
    }
}
