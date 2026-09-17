package app.naz.whispernote

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.activity.ComponentActivity
import org.junit.Before
import app.naz.whispernote.ui.theme.WhisperNoteTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class ModelPickerTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @Before fun showWindow() { compose.activityRule.scenario.onActivity { it.showUiTestWindow() } }

    @Test fun chooseCompactSmallAndSwitchBackToStandard() {
        val selected = mutableStateOf("tiny")
        compose.setContent { WhisperNoteTheme { ModelPicker(selected.value) { selected.value = it } } }
        compose.onNodeWithText("Compact (Q5)").performClick()
        compose.onNodeWithText("Small").performClick()
        compose.runOnIdle { assertEquals("small-q5_1", selected.value) }
        compose.onNodeWithText("Small · Compact (Q5) · 190.1 MB").assertIsDisplayed()
        compose.onNodeWithText("Standard").performClick()
        compose.runOnIdle { assertEquals("small", selected.value) }
    }
}
