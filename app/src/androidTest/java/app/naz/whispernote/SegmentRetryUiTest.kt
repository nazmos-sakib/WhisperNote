package app.naz.whispernote

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import app.naz.whispernote.core.*
import app.naz.whispernote.ui.theme.WhisperNoteTheme
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class SegmentRetryUiTest {
    @get:Rule val compose=createAndroidComposeRule<ComponentActivity>()
    @Before fun window() {compose.activityRule.scenario.onActivity {it.showUiTestWindow()}}
    @Test fun previewRequiresExplicitAcceptanceAndKeepsOriginalBoundaries() {
        val app=InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as WhisperApp
        runBlocking {app.ready.await()}
        val segment=Segment(1000,6000,"Old words")
        val note=Note(title="Retry preview fixture",status="Completed",duration=6000,checkpointMs=6000,segments=listOf(segment))
        val draft=SegmentRetry(note.id,segment,"base","en",status="Ready",result=listOf(Segment(1000,3000,"New"),Segment(3000,6000,"words")))
        val vm=NotesViewModel(app)
        try {
            app.repository.put(note);app.retries.put(draft)
            compose.setContent {WhisperNoteTheme {SegmentRetryDialog(note,segment,draft,vm,{})}}
            compose.onNodeWithText("Old words").assertExists()
            compose.runOnIdle {assertEquals(note,app.repository.get(note.id))}
            compose.onNodeWithText("Replace text").performScrollTo().performClick()
            compose.waitUntil(5000) {app.repository.get(note.id)?.segments?.single()?.text=="New words"}
            assertEquals(segment.copy(text="New words"),app.repository.get(note.id)!!.segments.single())
            assertEquals(6000L,app.repository.get(note.id)!!.checkpointMs)
        } finally {app.retries.remove(note.id);app.repository.delete(note.id)}
    }
}
