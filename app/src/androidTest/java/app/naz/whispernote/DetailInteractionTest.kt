package app.naz.whispernote

import androidx.activity.ComponentActivity
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import app.naz.whispernote.core.*
import app.naz.whispernote.ui.theme.WhisperNoteTheme
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class DetailInteractionTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @Before fun showWindow() { compose.activityRule.scenario.onActivity { it.showUiTestWindow() } }
    private val note = mutableStateOf(Note(title = "Example interview", duration = 300000, status = "Completed",
        checkpointMs = 300000, segments = List(30) { Segment(it * 10000L, (it + 1) * 10000L,
            "Paragraph $it. Some useful words in this audio note, with enough text to read while listening.", "s$it") }))
    private val playback = mutableStateOf(Playback(ready = true, playing = true, position = 1000, duration = 300000))
    private val list = LazyListState()
    private var toggles = 0
    private fun show() {
        compose.setContent { WhisperNoteTheme { TranscriptDetailContent(note.value, "", playback.value,
            {}, { id, text -> note.value = note.value.editSegment(id, text) },
            { id -> note.value = note.value.deleteSegment(id) }, { toggles++ }, { _, _ -> }, {}, {}, {}, {}, list) } }
    }
    private fun assertCentered(id: String) {
        compose.waitForIdle()
        compose.runOnIdle {
            val info = list.layoutInfo
            val item = info.visibleItemsInfo.first { it.key == id }
            assertEquals((info.viewportStartOffset + info.viewportEndOffset) / 2f, item.offset + item.size / 2f, 4f)
        }
    }
    @Test fun followingCentersFirstMiddleAndLastAndTouchStopsFollowing() {
        show()
        compose.onNodeWithTag("follow-playback-chip").performClick()
        compose.onNodeWithTag("follow-playback-toggle").assertIsOn()
        assertCentered("s0")
        compose.runOnIdle { playback.value = playback.value.copy(position = 151000) }
        assertCentered("s15")
        compose.runOnIdle { playback.value = playback.value.copy(position = 291000) }
        assertCentered("s29")
        compose.onNodeWithTag("transcript-list").performTouchInput { swipeDown() }
        compose.onNodeWithTag("follow-playback-toggle").assertIsOff()
        var index = 0; var offset = 0
        compose.runOnIdle { index = list.firstVisibleItemIndex; offset = list.firstVisibleItemScrollOffset; playback.value = playback.value.copy(position = 101000) }
        compose.runOnIdle { assertEquals(index, list.firstVisibleItemIndex); assertEquals(offset, list.firstVisibleItemScrollOffset) }
    }
    @Test fun playerCollapsesAndRemainsUsableAfterScrolling() {
        show()
        val expanded = compose.onNodeWithTag("audio-player").fetchSemanticsNode().boundsInRoot.height
        compose.onNodeWithTag("transcript-list").performTouchInput { swipeUp() }
        compose.onNodeWithTag("compact-player").assertIsDisplayed()
        compose.onNodeWithContentDescription("Pause").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(1, toggles) }
        assertTrue(compose.onNodeWithTag("audio-player").fetchSemanticsNode().boundsInRoot.height < expanded)
    }
    @Test fun segmentDeletionRequiresConfirmationAndPreservesProgress() {
        show()
        compose.onNodeWithTag("transcript-list").performScrollToNode(hasTestTag("segment-s0"))
        compose.onAllNodesWithContentDescription("Delete segment")[0].performClick()
        compose.onNodeWithText("Cancel").performClick()
        compose.runOnIdle { assertEquals(30, note.value.segments.size) }
        compose.onAllNodesWithContentDescription("Delete segment")[0].performClick()
        compose.onNodeWithText("Delete", substring = false).performClick()
        compose.runOnIdle { assertEquals(29, note.value.segments.size); assertEquals("s1", note.value.segments.first().id); assertEquals(300000L, note.value.checkpointMs) }
    }
}
