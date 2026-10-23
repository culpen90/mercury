package com.unsupportedpastels.hermesandroid.ui

import android.graphics.Bitmap
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipeUp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.unsupportedpastels.hermesandroid.app.DurableSessionId
import com.unsupportedpastels.hermesandroid.connection.ModelPickerState
import com.unsupportedpastels.hermesandroid.gateway.ModelOptions
import com.unsupportedpastels.hermesandroid.gateway.ModelProviderOption
import com.unsupportedpastels.hermesandroid.gateway.ModelSelection
import com.unsupportedpastels.hermesandroid.theme.HermesAndroidTheme
import java.io.File
import kotlin.math.roundToInt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Synthetic catalogs exercise native touch dispatch without contacting or changing a host. */
@RunWith(AndroidJUnit4::class)
class SheetScrollingInstrumentedTest {
    @get:Rule
    val compose = createComposeRule()

    private val models = (1..50).map { "Fixture model %02d".format(it) }
    private val options = ModelOptions(
        current = ModelSelection("fixture", models.first()),
        providers = listOf(ModelProviderOption("fixture", "Fixture provider", models)),
    )

    @Test
    fun composerModelListScrollsWithoutMovingOrDismissingSheet() {
        exercisePicker(sessionPicker = false)
    }

    @Test
    fun sessionModelListScrollsWithoutMovingOrDismissingSheet() {
        exercisePicker(sessionPicker = true)
    }

    private fun exercisePicker(sessionPicker: Boolean) {
        var dismissals = 0
        var selected: ModelSelection? = null
        val title = if (sessionPicker) "Choose model" else "Choose a model"
        val fixtureName = if (sessionPicker) "session-model-sheet" else "composer-model-sheet"
        compose.setContent {
            HermesAndroidTheme {
                if (sessionPicker) {
                    ModelPickerSheet(
                        state = ModelPickerState.Ready(DurableSessionId("scroll-fixture"), options),
                        onDismiss = { dismissals++ },
                        onRetry = {},
                        onSelected = { selected = it },
                        onConfirm = {},
                    )
                } else {
                    ModelPickerSheet(
                        options = options,
                        current = options.current,
                        recents = emptyList(),
                        reasoningOverrides = emptyMap(),
                        profileDefaultEffort = null,
                        query = "",
                        onQueryChange = {},
                        onDismiss = { dismissals++ },
                        onSetReasoning = { _, _ -> },
                        onSelect = { selected = it },
                    )
                }
            }
        }

        compose.onNodeWithText(title).assertIsDisplayed()
        val titleTop = compose.onNodeWithText(title).fetchSemanticsNode().boundsInRoot.top
        val list = compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange))
        fun assertSheetStillAnchored() {
            compose.onNodeWithText(title).assertIsDisplayed()
            assertEquals(titleTop, compose.onNodeWithText(title).fetchSemanticsNode().boundsInRoot.top, 1f)
            compose.runOnIdle { assertEquals(0, dismissals) }
        }

        // Native injection avoids asking Compose to become idle while an overscroll is
        // still held. Compare the title pixels before release to catch a sheet that
        // follows the finger and then snaps back at the end of a complete swipe.
        val listBounds = list.fetchSemanticsNode().boundsInRoot
        val titleBounds = compose.onNodeWithText(title).fetchSemanticsNode().boundsInRoot
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val beforePull = automation.takeScreenshot()
        val downTime = SystemClock.uptimeMillis()
        val heldY = listBounds.top + listBounds.height * 0.75f
        fun inject(action: Int, y: Float) {
            val event = MotionEvent.obtain(
                downTime, SystemClock.uptimeMillis(), action, listBounds.center.x, y, 0,
            ).apply { source = InputDevice.SOURCE_TOUCHSCREEN }
            try {
                assertTrue(automation.injectInputEvent(event, true))
            } finally {
                event.recycle()
            }
        }
        val autoAdvance = compose.mainClock.autoAdvance
        compose.mainClock.autoAdvance = false
        try {
            inject(MotionEvent.ACTION_DOWN, listBounds.top + listBounds.height * 0.25f)
            inject(MotionEvent.ACTION_MOVE, listBounds.center.y)
            inject(MotionEvent.ACTION_MOVE, heldY)
            compose.mainClock.advanceTimeBy(64)
            SystemClock.sleep(64)
            val heldPull = automation.takeScreenshot()
            val x = titleBounds.left.roundToInt()
            val y = titleBounds.top.roundToInt()
            val width = titleBounds.width.roundToInt()
            val height = titleBounds.height.roundToInt()
            val beforeTitle = Bitmap.createBitmap(beforePull, x, y, width, height)
            val heldTitle = Bitmap.createBitmap(heldPull, x, y, width, height)
            saveScreenshot(heldPull, "$fixtureName-held.png")
            assertTrue("The sheet title moved while the list was being pulled", beforeTitle.sameAs(heldTitle))
        } finally {
            try {
                inject(MotionEvent.ACTION_UP, heldY)
            } finally {
                compose.mainClock.autoAdvance = autoAdvance
            }
        }
        assertSheetStillAnchored()

        // Pull beyond the first item: formerly this handed the drag to the parent sheet.
        repeat(2) {
            list.performTouchInput { swipeDown(durationMillis = 600) }
            assertSheetStillAnchored()
        }
        capture("$fixtureName-top.png")

        // Real touch gestures must move the catalog in both directions while its title stays put.
        list.performTouchInput { swipeUp(durationMillis = 600) }
        assertSheetStillAnchored()
        compose.onNodeWithText(models.first()).assertIsNotDisplayed()
        list.performTouchInput { swipeDown(durationMillis = 600) }
        assertSheetStillAnchored()
        list.performScrollToNode(hasText(models.first()))
        compose.onNodeWithText(models.first()).assertIsDisplayed()

        list.performScrollToNode(hasText(models.last()))
        compose.onNodeWithText(models.last()).assertIsDisplayed()
        repeat(2) {
            list.performTouchInput { swipeUp(durationMillis = 600) }
            assertSheetStillAnchored()
        }
        capture("$fixtureName-bottom.png")
        compose.onNodeWithText(models.last()).performClick()
        compose.runOnIdle { assertEquals(ModelSelection("fixture", models.last()), selected) }
        compose.onNodeWithText("Close").performClick()
        compose.runOnIdle { assertEquals(1, dismissals) }
    }

    private fun capture(name: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
        saveScreenshot(instrumentation.uiAutomation.takeScreenshot(), name)
    }

    private fun saveScreenshot(bitmap: Bitmap, name: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val screenshot = File(instrumentation.targetContext.getExternalFilesDir(null), name)
        screenshot.outputStream().use { output ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
        }
    }
}
