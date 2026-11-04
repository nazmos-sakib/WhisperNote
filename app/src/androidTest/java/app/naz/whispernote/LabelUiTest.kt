package app.naz.whispernote

import androidx.activity.ComponentActivity
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import app.naz.whispernote.core.Note
import app.naz.whispernote.ui.theme.WhisperNoteTheme
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class LabelUiTest {
    @get:Rule val compose=createAndroidComposeRule<ComponentActivity>()
    @Before fun showWindow() {compose.activityRule.scenario.onActivity {it.showUiTestWindow()}}
    @Test fun canAddAnotherLabelWithoutRemovingTheFirst() {
        var selected by mutableStateOf(setOf("Lecture"))
        compose.setContent {WhisperNoteTheme {
            LabelPicker(listOf("Lecture","Favorite"),selected,
                {label,checked -> selected=if(checked) selected+label else selected-label},
                {selected=emptySet()},{},{})
        }}
        compose.onNodeWithText("Favorite").performClick()
        compose.runOnIdle {assertEquals(setOf("Lecture","Favorite"),selected)}
        compose.onNodeWithText("Lecture").performClick()
        compose.runOnIdle {assertEquals(setOf("Favorite"),selected)}
        compose.onNodeWithText("Remove all labels").performClick()
        compose.runOnIdle {assertTrue(selected.isEmpty())}
    }
    @Test fun createLabelTrimsNameAndRejectsBlank() {
        var result=""
        compose.setContent {WhisperNoteTheme {LabelNameDialog(null,{result=it},{})}}
        compose.onNodeWithText("Create",substring=false).assertIsNotEnabled()
        compose.onNode(hasSetTextAction()).performTextInput("  Season 2  ")
        compose.onNodeWithText("Create",substring=false).performClick()
        compose.runOnIdle {assertEquals("Season 2",result)}
    }
    @Test fun drawerFiltersLabelledNotesAndShowsCardLabel() {
        val app=androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as WhisperApp
        kotlinx.coroutines.runBlocking {app.ready.await()}
        val label="UITest-${java.util.UUID.randomUUID()}"
        val note=Note(title="Labelled fixture",status="Completed",labels=setOf(label))
        val other=Note(title="Unlabelled fixture",status="Completed")
        try {
            app.repository.createLabel(label);app.repository.put(note);app.repository.put(other)
            val vm=NotesViewModel(app)
            compose.setContent {WhisperNoteTheme {WhisperNote(null,vm)}}
            compose.onNodeWithContentDescription("Open navigation drawer").performClick()
            compose.onNodeWithText("$label · 1").performClick()
            compose.onNodeWithText("Labelled fixture").assertIsDisplayed()
            compose.onNodeWithText("Unlabelled fixture").assertDoesNotExist()
            compose.onNodeWithText("Labelled fixture").performClick()
            compose.onNodeWithContentDescription("Show note actions").performClick()
            compose.onNodeWithContentDescription("Change label").performClick()
            compose.onNodeWithText("Remove all labels",substring=false).performClick()
            compose.waitUntil(5000) {app.repository.get(note.id)?.labels?.isEmpty()==true}
        } finally {app.repository.delete(note.id);app.repository.delete(other.id);app.repository.deleteLabel(label)}
    }
}
