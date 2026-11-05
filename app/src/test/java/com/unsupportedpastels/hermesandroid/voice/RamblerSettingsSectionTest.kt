package com.unsupportedpastels.hermesandroid.voice

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

    @Test
    fun manualSetupHasNoGoogleOrMercuryAccountRequirement() {
        val store = FakeRamblerSettingsStore()
        composeRule.setContent { RamblerSettingsSection(store) }

        composeRule.onNodeWithContentDescription("Gemini API key").assertIsDisplayed()
        composeRule.onNodeWithText("Save key").assertIsDisplayed()
        composeRule.onNodeWithText("Continue with Google").assertDoesNotExist()
        composeRule.onNodeWithText("Continue with GitHub").assertDoesNotExist()
        composeRule.onNodeWithText("Continue with Discord").assertDoesNotExist()
        assertNull(store.key)
    }

    @Test
    fun legacyManagedKeyRequiresExplicitPersonalEntryAndCanBeRemoved() {
        val store = FakeRamblerSettingsStore(legacyManagedKey = true)
        composeRule.setContent { RamblerSettingsSection(store) }
        composeRule.onNodeWithText("Not configured").assertIsDisplayed()
        composeRule.onNodeWithText(
            "Automatic Google setup has been removed. Enter your own Gemini API key to continue dictation. You manage its billing and quotas.",
        ).assertIsDisplayed()
        composeRule.onNodeWithText("Save key").assertIsNotEnabled()
        assertBlankKeyField()

        composeRule.onNodeWithText("Remove key").performClick()

        assertEquals(false, store.legacyManagedKey)
        composeRule.onNodeWithText("Remove key").assertDoesNotExist()
        composeRule.onNodeWithText(
            "Automatic Google setup has been removed. Enter your own Gemini API key to continue dictation. You manage its billing and quotas.",
        ).assertDoesNotExist()
    }

    @Test
    fun personalKeySaveClearsLegacyMigrationNotice() {
        val store = FakeRamblerSettingsStore(legacyManagedKey = true)
        composeRule.setContent { RamblerSettingsSection(store) }
        composeRule.onNodeWithContentDescription("Gemini API key").performTextInput("personal_key")

        composeRule.onNodeWithText("Save key").performClick()

        composeRule.onNodeWithText("Configured").assertIsDisplayed()
        assertEquals("personal_key", store.key)
        assertEquals(false, store.legacyManagedKey)
        composeRule.onNodeWithText(
            "Automatic Google setup has been removed. Enter your own Gemini API key to continue dictation. You manage its billing and quotas.",
        ).assertDoesNotExist()
        assertBlankKeyField()
    }

    @Test
    fun keyCanBeSavedAndRemovedWithoutShowingTheExistingKey() {
        val store = FakeRamblerSettingsStore()
        var changes = 0
        composeRule.setContent { RamblerSettingsSection(store, onChanged = { changes++ }) }
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
        composeRule.setContent { RamblerSettingsSection(store) }

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
        restoration.setContent { RamblerSettingsSection(store) }
        composeRule.onNodeWithContentDescription("Gemini API key").performTextInput("unsaved_example_key")

        restoration.emulateSavedInstanceStateRestore()

        assertBlankKeyField()
        assertNull(store.key)
    }

    @Test
    fun failedSaveKeepsConfigurationUnchangedAndShowsNoSecretInTheError() {
        val store = FakeRamblerSettingsStore(failSave = true)
        composeRule.setContent { RamblerSettingsSection(store) }
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
        composeRule.setContent {
            RamblerSetupDialog(store, onDismiss = {}, onSaved = { saved++ })
        }
        composeRule.onNodeWithText("Set up Rambler dictation").assertIsDisplayed()
        composeRule.onNodeWithText("Continue with Google").assertDoesNotExist()
        composeRule.onNodeWithContentDescription("Gemini API key").performScrollTo().performTextInput("example_key")
        composeRule.onNodeWithContentDescription("Gemini API key").performImeAction()

        composeRule.onNodeWithText("Save key").performScrollTo().performClick()

        assertEquals("example_key", store.key)
        assertEquals(1, saved)
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
            RamblerSettingsSection(FakeRamblerSettingsStore("example_key"))
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
        var legacyManagedKey: Boolean = false,
    ) : RamblerSettingsStore {
        override suspend fun loadApiKey() = if (legacyManagedKey) null else key
        override suspend fun requiresPersonalApiKey() = legacyManagedKey
        override suspend fun saveApiKey(apiKey: String) {
            if (failSave) error("failure contains $apiKey")
            key = apiKey
            legacyManagedKey = false
        }
        override suspend fun clearApiKey() { key = null; legacyManagedKey = false }
    }
}
