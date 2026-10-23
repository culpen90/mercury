package com.unsupportedpastels.hermesandroid.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.unsupportedpastels.hermesandroid.theme.HermesAndroidTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class ProfileSpeedOptionsTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun normalRemainsEnabledAndFastRequiresExplicitCapability() {
        var advertisedFastSupport by mutableStateOf<Boolean?>(null)
        composeRule.setContent {
            HermesAndroidTheme {
                ProfileSpeedOptions(
                    speed = "normal",
                    fastSupported = advertisedFastSupport == true,
                    enabled = true,
                    error = null,
                    onSelect = {},
                )
            }
        }

        composeRule.onNodeWithContentDescription("Set default speed to normal")
            .assertIsDisplayed().assertIsEnabled()
        composeRule.onNodeWithContentDescription("Set default speed to fast")
            .assertIsDisplayed().assertIsNotEnabled()

        composeRule.runOnIdle { advertisedFastSupport = false }
        composeRule.onNodeWithContentDescription("Set default speed to normal").assertIsEnabled()
        composeRule.onNodeWithContentDescription("Set default speed to fast").assertIsNotEnabled()

        composeRule.runOnIdle { advertisedFastSupport = true }
        composeRule.onNodeWithContentDescription("Set default speed to normal").assertIsEnabled()
        composeRule.onNodeWithContentDescription("Set default speed to fast").assertIsEnabled()
    }

    @Test
    fun choosingNormalAndFastPublishesCanonicalSpeedAndSelectionSemantics() {
        var speed by mutableStateOf("normal")
        val selectedSpeeds = mutableListOf<String>()
        composeRule.setContent {
            HermesAndroidTheme {
                ProfileSpeedOptions(
                    speed = speed,
                    fastSupported = true,
                    enabled = true,
                    error = null,
                    onSelect = { selectedSpeeds += it; speed = it },
                )
            }
        }

        composeRule.onNodeWithContentDescription("Set default speed to normal").assertIsSelected()
        composeRule.onNodeWithContentDescription("Set default speed to fast")
            .assertIsNotSelected().performClick().assertIsSelected()
        composeRule.onNodeWithContentDescription("Set default speed to normal")
            .assertIsNotSelected().performClick().assertIsSelected()
        composeRule.onNodeWithContentDescription("Set default speed to fast").assertIsNotSelected()

        composeRule.runOnIdle { assertEquals(listOf("fast", "normal"), selectedSpeeds) }
    }

    @Test
    fun unavailableSpeedLeavesBothOptionsUnselected() {
        composeRule.setContent {
            HermesAndroidTheme {
                ProfileSpeedOptions(
                    speed = null,
                    fastSupported = true,
                    enabled = true,
                    error = null,
                    onSelect = {},
                )
            }
        }

        composeRule.onNodeWithContentDescription("Set default speed to normal").assertIsNotSelected()
        composeRule.onNodeWithContentDescription("Set default speed to fast").assertIsNotSelected()
        composeRule.onNodeWithText("Choose a speed to replace the current or unavailable setting.")
            .assertIsDisplayed()
    }

    @Test
    fun savingDisablesBothSpeedOptionsAndPreservesSelection() {
        var saving by mutableStateOf(false)
        composeRule.setContent {
            HermesAndroidTheme {
                ProfileSpeedOptions(
                    speed = "fast",
                    fastSupported = true,
                    enabled = !saving,
                    error = null,
                    onSelect = {},
                )
            }
        }

        composeRule.onNodeWithContentDescription("Set default speed to normal").assertIsEnabled()
        composeRule.onNodeWithContentDescription("Set default speed to fast")
            .assertIsEnabled().assertIsSelected()

        composeRule.runOnIdle { saving = true }

        composeRule.onNodeWithContentDescription("Set default speed to normal")
            .assertIsNotEnabled().assertIsNotSelected()
        composeRule.onNodeWithContentDescription("Set default speed to fast")
            .assertIsNotEnabled().assertIsSelected()
    }
}
