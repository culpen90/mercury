package com.unsupportedpastels.hermesandroid.ui

import androidx.activity.ComponentDialog
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Text
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.unsupportedpastels.hermesandroid.theme.HermesAndroidTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = "w400dp-h900dp")
@OptIn(ExperimentalMaterial3Api::class)
class ScrollableBottomSheetTest {
    @get:Rule val compose = createComposeRule()

    private var dismissals = 0

    @Test fun closeRemainsAvailableAfterScrolling() {
        showSheet()
        compose.onNodeWithTag("Sheet list").performScrollToIndex(29)
        compose.onNodeWithText("Option 29").assertIsDisplayed()
        compose.onNodeWithText("Close").assertIsDisplayed().performClick()
        assertDismissed()
    }

    @Test fun backDismissesWithoutSheetDragging() {
        showSheet()
        compose.runOnIdle {
            val dialog = ShadowDialog.getLatestDialog() as ComponentDialog
            dialog.onBackPressedDispatcher.onBackPressed()
        }
        assertDismissed()
    }

    @Test fun tappingOutsideTheSheetDismissesIt() {
        showSheet()
        compose.onNode(hasContentDescription("Close sheet") and !hasText("Close"))
            .performTouchInput { click(Offset(center.x, height * 0.25f)) }
        assertDismissed()
    }

    @Test fun accessibilityDismissActionRemainsAvailable() {
        showSheet()
        compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.Dismiss))
            .performSemanticsAction(SemanticsActions.Dismiss)
        assertDismissed()
    }

    private fun showSheet() {
        val visible = mutableStateOf(true)
        compose.setContent {
            HermesAndroidTheme {
                Box(Modifier.fillMaxSize()) { Text("Background") }
                if (visible.value) {
                    ScrollableBottomSheet(onDismissRequest = {
                        dismissals++
                        visible.value = false
                    }) {
                        LazyColumn(Modifier.fillMaxWidth().height(200.dp).testTag("Sheet list")) {
                            items(30) { index ->
                                Text("Option $index", Modifier.fillMaxWidth().padding(16.dp))
                            }
                        }
                    }
                }
            }
        }
        compose.onNodeWithText("Option 0").assertIsDisplayed()
    }

    private fun assertDismissed() {
        compose.onNodeWithTag("Sheet list").assertDoesNotExist()
        compose.runOnIdle { assertEquals(1, dismissals) }
    }
}
