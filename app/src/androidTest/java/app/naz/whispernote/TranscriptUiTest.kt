package app.naz.whispernote

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.activity.ComponentActivity
import org.junit.Before
import androidx.test.platform.app.InstrumentationRegistry
import app.naz.whispernote.core.Note
import app.naz.whispernote.core.Segment
import app.naz.whispernote.ui.theme.WhisperNoteTheme
import org.junit.Rule
import org.junit.Test

class TranscriptUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @Before fun showWindow() { compose.activityRule.scenario.onActivity { it.showUiTestWindow() } }

    @Test fun interruptedTranscriptShowsSavedTextAndResumeTimestamp() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as WhisperApp
        val note = Note(title = "Interview", duration = 600_000, status = "Interrupted", checkpointMs = 182_000,
            segments = listOf(Segment(0, 182_000, "This text was already saved.")))
        val vm = NotesViewModel(app)
        compose.setContent { WhisperNoteTheme { Detail(note, "", vm) {} } }
        compose.onNodeWithText("Transcription incomplete").assertExists()
        compose.onNodeWithText("Resume from 03:02").assertExists()
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("This text was already saved."))
        compose.onNodeWithText("This text was already saved.").assertIsDisplayed()
    }

    @Test fun newFinalizedSegmentAppearsWithoutLeavingDetail() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as WhisperApp
        val note = mutableStateOf(Note(title = "Interview", duration = 600_000, status = "Transcribing"))
        val vm = NotesViewModel(app)
        compose.setContent { WhisperNoteTheme { Detail(note.value, "", vm) {} } }
        compose.runOnIdle { note.value = note.value.appendFinalized(Segment(0, 12000, "Newly recognized words.")) }
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Newly recognized words."))
        compose.onNodeWithText("Newly recognized words.").assertIsDisplayed()
    }
}
