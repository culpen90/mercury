package com.unsupportedpastels.hermesandroid.ui

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipeUp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.unsupportedpastels.hermesandroid.app.DurableSessionId
import com.unsupportedpastels.hermesandroid.connection.ModelPickerState
import com.unsupportedpastels.hermesandroid.gateway.ModelOptions
import com.unsupportedpastels.hermesandroid.gateway.ModelProviderOption
import com.unsupportedpastels.hermesandroid.theme.HermesAndroidTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = "w400dp-h900dp")
class ModelPickerScrollingTest {
    @get:Rule val compose = createComposeRule()

    private val options = ModelOptions(
        current = null,
        providers = listOf(ModelProviderOption("fixture", "Fixture", (0..49).map { "Model %02d".format(it) })),
    )

    @Test fun composerPickerKeepsEdgeSwipesInsideList() = checkPicker(session = false)
    @Test fun sessionPickerKeepsEdgeSwipesInsideList() = checkPicker(session = true)

    @Test @Config(qualifiers = "w610dp-h900dp")
    fun mediumComposerPickerKeepsEdgeSwipesInsideList() = checkPicker(session = false)

    @Test @Config(qualifiers = "w610dp-h900dp")
    fun mediumSessionPickerKeepsEdgeSwipesInsideList() = checkPicker(session = true)

    @Test @Config(qualifiers = "w900dp-h900dp")
    fun expandedComposerPickerKeepsEdgeSwipesInsideList() = checkPicker(session = false)

    @Test @Config(qualifiers = "w900dp-h900dp")
    fun expandedSessionPickerKeepsEdgeSwipesInsideList() = checkPicker(session = true)

    private fun checkPicker(session: Boolean) {
        var dismissals = 0
        compose.setContent {
            HermesAndroidTheme {
                if (session) {
                    ModelPickerSheet(
                        state = ModelPickerState.Ready(DurableSessionId("fixture"), options),
                        onDismiss = { dismissals++ }, onRetry = {}, onSelected = {}, onConfirm = {},
                    )
                } else {
                    ModelPickerSheet(
                        options = options, current = null, recents = emptyList(), reasoningOverrides = emptyMap(),
                        profileDefaultEffort = null, query = "", onQueryChange = {},
                        onDismiss = { dismissals++ }, onSetReasoning = { _, _ -> }, onSelect = {},
                    )
                }
            }
        }
        val heading = compose.onNodeWithText(if (session) "Choose model" else "Choose a model")
        val list = compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.ScrollToIndex))
        heading.assertIsDisplayed()
        val top = heading.fetchSemanticsNode().boundsInWindow.top

        // A real finger drag at the first item must not move or dismiss the sheet.
        repeat(2) { list.performTouchInput { swipeDown(durationMillis = 500) } }
        heading.assertIsDisplayed()
        assertEquals("List edge drag moved its sheet", top, heading.fetchSemanticsNode().boundsInWindow.top, 1f)
        compose.runOnIdle { assertEquals("List edge drag dismissed its sheet", 0, dismissals) }

        list.performTouchInput { swipeUp(durationMillis = 500) }
        compose.onNodeWithText("Model 00").assertIsNotDisplayed()
        list.performScrollToIndex(if (session) 49 else 50)
        compose.onNodeWithText("Model 49").assertIsDisplayed()
        repeat(2) { list.performTouchInput { swipeUp(durationMillis = 500) } }
        assertEquals(top, heading.fetchSemanticsNode().boundsInWindow.top, 1f)
        list.performTouchInput { swipeDown(durationMillis = 500) }
        list.performScrollToIndex(0)
        compose.onNodeWithText("Model 00").assertIsDisplayed()
        compose.runOnIdle { assertEquals(0, dismissals) }
    }
}
