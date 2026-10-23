package com.unsupportedpastels.hermesandroid.ui

import android.graphics.Bitmap
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.printToString
import androidx.test.espresso.Espresso.closeSoftKeyboard
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.unsupportedpastels.hermesandroid.gateway.AuthenticationState
import com.unsupportedpastels.hermesandroid.gateway.ConnectionState
import com.unsupportedpastels.hermesandroid.gateway.HermesGatewaySnapshot
import com.unsupportedpastels.hermesandroid.gateway.ModelCapabilities
import com.unsupportedpastels.hermesandroid.gateway.ModelOptions
import com.unsupportedpastels.hermesandroid.gateway.ModelProviderOption
import com.unsupportedpastels.hermesandroid.gateway.ModelSelection
import com.unsupportedpastels.hermesandroid.gateway.ModelSwitchResult
import com.unsupportedpastels.hermesandroid.navigation.SettingsModelRoute
import com.unsupportedpastels.hermesandroid.theme.HermesAndroidTheme
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Exercise the real settings and picker touch path with a local, synthetic host catalog. */
@RunWith(AndroidJUnit4::class)
class ProfileSpeedInstrumentedTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun profileSpeedCanReturnToNormalAndRemainsSelectedWhenPickerReopens() {
        val fastModel = ModelSelection("fixture", "Fast fixture model")
        val normalModel = ModelSelection("fixture", "Normal fixture model")
        var snapshot by mutableStateOf(HermesGatewaySnapshot(
            connectionState = ConnectionState.Connected,
            authenticationState = AuthenticationState.Authenticated,
            profileSpeed = "fast",
            defaultModelOptions = ModelOptions(
                current = fastModel,
                providers = listOf(ModelProviderOption(
                    slug = "fixture",
                    name = "Fixture provider",
                    models = listOf(fastModel.model, normalModel.model),
                    capabilities = mapOf(
                        fastModel.model to ModelCapabilities(fast = true),
                        normalModel.model to ModelCapabilities(fast = false),
                    ),
                )),
                profile = "default",
            ),
        ))
        val selectedSpeeds = mutableListOf<String>()
        compose.setContent {
            HermesAndroidTheme {
                HermesApp(
                    snapshot = snapshot,
                    initialRoute = SettingsModelRoute,
                    onSetProfileSpeed = { speed ->
                        selectedSpeeds += speed
                        snapshot = snapshot.copy(profileSpeed = speed)
                        Result.success(Unit)
                    },
                    onSetProfileDefaultModel = { selection, _ ->
                        snapshot = snapshot.copy(
                            defaultModelOptions = snapshot.defaultModelOptions?.copy(current = selection),
                        )
                        ModelSwitchResult(accepted = true)
                    },
                )
            }
        }

        // Start on the model settings destination; local UI tests cover navigation from Home.
        compose.waitForIdle()
        try {
            compose.waitUntil(10_000) {
                compose.onAllNodes(hasContentDescription("Change default model"))
                    .fetchSemanticsNodes().isNotEmpty()
            }
        } catch (error: Throwable) {
            runCatching {
                capture("profile-speed-settings-bootstrap.png")
                val context = InstrumentationRegistry.getInstrumentation().targetContext
                File(context.getExternalFilesDir(null), "profile-speed-settings-semantics.txt")
                    .writeText(compose.onRoot(useUnmergedTree = true).printToString())
            }
            throw error
        }
        compose.onNodeWithContentDescription("Set default speed to normal")
            .performScrollTo().assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithContentDescription("Set default speed to fast")
            .assertIsDisplayed().assertIsEnabled().assertIsSelected()
        compose.onNodeWithContentDescription("Change default model")
            .performScrollTo().performTouchInput { click() }
        compose.waitForIdle()

        val inPicker = hasAnyAncestor(isDialog())
        val normalSpeed = hasContentDescription("Set default speed to normal") and inPicker
        val fastSpeed = hasContentDescription("Set default speed to fast") and inPicker
        val closePicker = hasContentDescription("Close sheet") and hasText("Close") and inPicker

        compose.onNode(normalSpeed).assertIsDisplayed().assertIsEnabled().assertIsNotSelected()
            .performTouchInput { click() }.assertIsSelected()
        compose.onNode(fastSpeed).assertIsDisplayed().assertIsEnabled().assertIsNotSelected()
        compose.runOnIdle { assertEquals("normal", snapshot.profileSpeed) }
        capture("profile-speed-normal-picker.png")

        compose.onNode(closePicker).performTouchInput { click() }
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Change default model")
            .performScrollTo().performTouchInput { click() }
        compose.waitForIdle()
        compose.onNode(normalSpeed).assertIsDisplayed().assertIsSelected()
        compose.onNode(fastSpeed).assertIsNotSelected().performTouchInput { click() }.assertIsSelected()
        compose.runOnIdle { assertEquals("fast", snapshot.profileSpeed) }

        compose.onNode(hasContentDescription("Search models") and inPicker)
            .performTouchInput { click() }.performTextInput("Normal fixture")
        compose.onNode(hasContentDescription("Select Fixture provider ${normalModel.model}") and inPicker)
            .performScrollTo().assertIsDisplayed()
        capture("profile-speed-search-with-keyboard.png")
        closeSoftKeyboard()
        compose.onNode(hasContentDescription("Select Fixture provider ${normalModel.model}") and inPicker)
            .performScrollTo().performTouchInput { click() }
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Set default speed to normal")
            .performScrollTo().assertIsEnabled()
        compose.onNodeWithContentDescription("Set default speed to fast")
            .assertIsNotEnabled().assertIsSelected()
        compose.runOnIdle { assertEquals(normalModel, snapshot.defaultModelOptions?.current) }

        compose.onNodeWithContentDescription("Change default model")
            .performScrollTo().performTouchInput { click() }
        compose.waitForIdle()
        compose.onNode(fastSpeed).assertIsDisplayed().assertIsNotEnabled()
        compose.onNode(normalSpeed).assertIsEnabled().performTouchInput { click() }.assertIsSelected()
        compose.runOnIdle {
            assertEquals("normal", snapshot.profileSpeed)
            assertEquals(listOf("normal", "fast", "normal"), selectedSpeeds)
        }
        capture("profile-speed-normal-only-model.png")
    }

    private fun capture(name: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
        val screenshot = File(instrumentation.targetContext.getExternalFilesDir(null), name)
        screenshot.outputStream().use { output ->
            instrumentation.uiAutomation.takeScreenshot().compress(Bitmap.CompressFormat.PNG, 100, output)
        }
    }
}
