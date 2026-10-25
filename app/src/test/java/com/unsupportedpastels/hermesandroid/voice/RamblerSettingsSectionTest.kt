package com.unsupportedpastels.hermesandroid.voice

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.text.AnnotatedString
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = "w400dp-h1000dp")
class RamblerSettingsSectionTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val idleGoogleSetup: RamblerGoogleSetupFactory = { RamblerGoogleSetupUi(onContinue = {}) }

    @Test
    fun manualKeySetupIsDefaultAndDoesNotComposeGoogleSetup() {
        val store = FakeRamblerSettingsStore()
        var googleCompositions = 0
        composeRule.setContent {
            RamblerSettingsSection(store, googleSetup = {
                googleCompositions++
                RamblerGoogleSetupUi(onContinue = {})
            })
        }

        composeRule.onNodeWithContentDescription("Gemini API key").assertIsDisplayed()
        composeRule.onNodeWithText("Get a Gemini API key").assertIsDisplayed()
        composeRule.onNodeWithText("Save key").assertIsDisplayed()
        composeRule.onNodeWithText("Continue with Google").assertDoesNotExist()
        composeRule.onNodeWithText("Use an API key instead").assertDoesNotExist()
        assertEquals(0, googleCompositions)
        assertNull(store.key)
    }

    @Test
    fun googleSetupIsAvailableOnlyWhenExplicitlyEnabled() {
        val store = FakeRamblerSettingsStore()
        var continues = 0
        composeRule.setContent {
            RamblerSettingsSection(store, showGoogleSetup = true, googleSetup = {
                RamblerGoogleSetupUi(onContinue = { continues++ })
            })
        }

        composeRule.onNodeWithText("Continue with Google").assertIsDisplayed().performClick()

        assertEquals(1, continues)
        assertNull(store.key)
        composeRule.onNodeWithContentDescription("Gemini API key").assertIsDisplayed()
        composeRule.onNodeWithText("Get a Gemini API key").assertIsDisplayed()
        composeRule.onNodeWithText("Save key").assertIsDisplayed()
    }

    @Test
    fun googleProvisioningShowsItsStageAndDisablesOtherCredentialActions() {
        val store = FakeRamblerSettingsStore("existing_key")
        var busy by mutableStateOf(false)
        composeRule.setContent {
            RamblerSettingsSection(store, showGoogleSetup = true, googleSetup = {
                RamblerGoogleSetupUi(
                    busy = busy,
                    status = if (busy) "Creating a separate Google project…" else null,
                    onContinue = {},
                )
            })
        }
        composeRule.onNodeWithContentDescription("Gemini API key").performTextInput("replacement_key")

        composeRule.runOnIdle { busy = true }

        composeRule.onNodeWithText("Creating a separate Google project…").assertIsDisplayed()
        composeRule.onNodeWithText("Continue with Google").assertIsNotEnabled()
        composeRule.onNodeWithContentDescription("Gemini API key").assertIsNotEnabled()
        composeRule.onNodeWithText("Save key").assertIsNotEnabled()
        composeRule.onNodeWithText("Get a Gemini API key").assertIsNotEnabled()
        composeRule.onNodeWithText("Remove key").assertIsNotEnabled()
        assertEquals("existing_key", store.key)
    }

    @Test
    fun googleSetupErrorLeavesTheDialogOpenUntilConfiguredCallbackSucceeds() {
        val store = FakeRamblerSettingsStore()
        var completeGoogleSetup: (() -> Unit)? = null
        var visible by mutableStateOf(true)
        var saved = 0
        composeRule.setContent {
            if (visible) {
                RamblerSetupDialog(
                    store = store,
                    onDismiss = { visible = false },
                    onSaved = { saved++; visible = false },
                    showGoogleSetup = true,
                    googleSetup = { configured ->
                        completeGoogleSetup = configured
                        RamblerGoogleSetupUi(
                            error = "Google needs you to accept its terms before setup can continue",
                            onContinue = {},
                        )
                    },
                )
            }
        }
        composeRule.onNodeWithText("Continue with Google").performClick()
        composeRule.onNodeWithText("Google needs you to accept its terms before setup can continue").assertIsDisplayed()
        composeRule.onNodeWithText("Set up Rambler dictation").assertIsDisplayed()
        assertEquals(0, saved)

        composeRule.runOnIdle {
            store.key = "provisioned_example_key"
            checkNotNull(completeGoogleSetup).invoke()
        }

        composeRule.onNodeWithText("Set up Rambler dictation").assertDoesNotExist()
        assertEquals(1, saved)
    }

    @Test
    fun keyCanBeSavedAndRemovedWithoutShowingTheExistingKey() {
        val store = FakeRamblerSettingsStore()
        var changes = 0
        composeRule.setContent { RamblerSettingsSection(store, onChanged = { changes++ }, googleSetup = idleGoogleSetup) }
        composeRule.onNodeWithText("Not configured").assertIsDisplayed()
        composeRule.onNodeWithText("Save key").assertIsNotEnabled()
        composeRule.onNodeWithContentDescription("Gemini API key").performTextInput("example_key")

        composeRule.onNodeWithText("Save key").performClick()

        composeRule.onNodeWithText("Configured").assertIsDisplayed()
        assertEquals("example_key", store.key)
        assertEquals(1, changes)
        assertBlankKeyField()
        composeRule.onNodeWithText("Remove key").performClick()
        composeRule.onNodeWithText("Not configured").assertIsDisplayed()
        assertNull(store.key)
        assertEquals(2, changes)
    }

    @Test
    fun existingKeyIsNeverLoadedIntoTheEditField() {
        val store = FakeRamblerSettingsStore("existing_key")
        composeRule.setContent { RamblerSettingsSection(store, googleSetup = idleGoogleSetup) }

        composeRule.onNodeWithText("Configured").assertIsDisplayed()
        composeRule.onNodeWithText("Remove key").assertIsDisplayed()
        assertBlankKeyField()
        composeRule.onNodeWithContentDescription("Gemini API key")
            .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Password))
    }

    @Test
    fun unsavedKeyIsDiscardedDuringStateRestoration() {
        val restoration = StateRestorationTester(composeRule)
        val store = FakeRamblerSettingsStore()
        restoration.setContent { RamblerSettingsSection(store, googleSetup = idleGoogleSetup) }
        composeRule.onNodeWithContentDescription("Gemini API key").performTextInput("unsaved_example_key")

        restoration.emulateSavedInstanceStateRestore()

        assertBlankKeyField()
        assertNull(store.key)
    }

    @Test
    fun failedSaveKeepsConfigurationUnchangedAndShowsNoSecretInTheError() {
        val store = FakeRamblerSettingsStore(failSave = true)
        composeRule.setContent { RamblerSettingsSection(store, googleSetup = idleGoogleSetup) }
        composeRule.onNodeWithContentDescription("Gemini API key").performTextInput("unsaved_example_key")

        composeRule.onNodeWithText("Save key").performClick()

        composeRule.onNodeWithText("Couldn't save the Gemini API key").assertIsDisplayed()
        composeRule.onNodeWithText("Not configured").assertIsDisplayed()
        assertNull(store.key)
    }

    @Test
    @Config(qualifiers = "w400dp-h600dp")
    fun setupDialogSavesAndInvokesTheContinuation() {
        val store = FakeRamblerSettingsStore()
        var saved = 0
        var googleCompositions = 0
        composeRule.setContent {
            RamblerSetupDialog(store, onDismiss = {}, onSaved = { saved++ }, googleSetup = {
                googleCompositions++
                RamblerGoogleSetupUi(onContinue = {})
            })
        }
        composeRule.onNodeWithText("Set up Rambler dictation").assertIsDisplayed()
        composeRule.onNodeWithText("Continue with Google").assertDoesNotExist()
        composeRule.onNodeWithContentDescription("Gemini API key").performScrollTo().performTextInput("example_key")
        composeRule.onNodeWithContentDescription("Gemini API key").performImeAction()

        composeRule.onNodeWithText("Save key").performScrollTo().performClick()

        assertEquals("example_key", store.key)
        assertEquals(1, saved)
        assertEquals(0, googleCompositions)
    }

    @Test
    @Config(qualifiers = "w400dp-h1000dp")
    fun compactWindowKeepsTheKeyControlsVisible() = assertControlsVisible()

    @Test
    @Config(qualifiers = "w610dp-h1000dp")
    fun mediumWindowKeepsTheKeyControlsVisible() = assertControlsVisible()

    @Test
    @Config(qualifiers = "w900dp-h1000dp")
    fun expandedWindowKeepsTheKeyControlsVisible() = assertControlsVisible()

    private fun assertControlsVisible() {
        composeRule.setContent {
            RamblerSettingsSection(FakeRamblerSettingsStore("example_key"), googleSetup = idleGoogleSetup)
        }
        composeRule.onNodeWithText("Rambler dictation").assertIsDisplayed()
        composeRule.onNodeWithText("Continue with Google").assertDoesNotExist()
        composeRule.onNodeWithText("Use an API key instead").assertDoesNotExist()
        composeRule.onNodeWithContentDescription("Gemini API key").assertIsDisplayed()
        composeRule.onNodeWithText("Get a Gemini API key").assertIsDisplayed()
        composeRule.onNodeWithText("Save key").assertIsDisplayed()
        composeRule.onNodeWithText("Remove key").assertIsDisplayed()
    }

    private fun assertBlankKeyField() {
        composeRule.onNodeWithContentDescription("Gemini API key").assert(
            SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("")),
        )
    }

    private class FakeRamblerSettingsStore(
        var key: String? = null,
        private val failSave: Boolean = false,
    ) : RamblerSettingsStore {
        override suspend fun loadApiKey() = key
        override suspend fun saveApiKey(apiKey: String) {
            if (failSave) error("failure contains $apiKey")
            key = apiKey
        }
        override suspend fun clearApiKey() { key = null }
    }
}
