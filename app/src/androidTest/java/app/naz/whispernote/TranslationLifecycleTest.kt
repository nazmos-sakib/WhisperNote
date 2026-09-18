package app.naz.whispernote

import android.content.Intent
import android.graphics.Bitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import app.naz.whispernote.core.*
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.translate.TranslateRemoteModel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit

class TranslationLifecycleTest {
    @get:Rule val compose = createEmptyComposeRule()
    @Test fun realInlineTranslationSurvivesRecreationButNotLeavingNote() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext.applicationContext as WhisperApp
        runBlocking { app.ready.await() }
        Tasks.await(RemoteModelManager.getInstance().download(TranslateRemoteModel.Builder("de").build(), DownloadConditions.Builder().build()), 180, TimeUnit.SECONDS)
        val segment = Segment(0, 7000, "Guten Morgen. Vielen Dank für Ihre Hilfe.")
        val note = Note(title = "Translation lifecycle fixture", language = "de", status = "Completed", duration = 7000, segments = listOf(segment))
        app.repository.put(note)
        try {
            ActivityScenario.launch<MainActivity>(Intent(app, MainActivity::class.java).putExtra("noteId", note.id)).use { scenario ->
                scenario.onActivity { it.showUiTestWindow() }
                compose.waitUntil(10000) { runCatching { compose.onAllNodesWithTag("segment-translate-${segment.id}").fetchSemanticsNodes().isNotEmpty() }.getOrDefault(false) }
                compose.onNodeWithTag("segment-translate-${segment.id}").performScrollTo().performClick()
                compose.waitUntil(10000) { compose.onAllNodesWithText("Activate German → English").fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithText("Activate German → English").performScrollTo().performClick()
                compose.waitUntil(60000) { runCatching { compose.onAllNodesWithTag("translation-${segment.id}").fetchSemanticsNodes().isNotEmpty() }.getOrDefault(false) }
                compose.onNodeWithText(segment.text).assertExists()
                scenario.onActivity {
                    val state = ViewModelProvider(it)[TranslationViewModel::class.java].session.state.value
                    assertTrue(state.results.getValue(segment.id).text!!.lowercase().contains("thank"))
                }
                val screenshot = instrumentation.uiAutomation.takeScreenshot()
                File(app.getExternalFilesDir(null), "translation-inline.png").outputStream().use { screenshot.compress(Bitmap.CompressFormat.PNG, 100, it) }
                screenshot.recycle()
                scenario.recreate()
                scenario.onActivity { it.showUiTestWindow() }
                compose.waitUntil(10000) { runCatching { compose.onAllNodesWithTag("translation-${segment.id}").fetchSemanticsNodes().isNotEmpty() }.getOrDefault(false) }
                scenario.onActivity { assertNotNull(ViewModelProvider(it)[TranslationViewModel::class.java].session.state.value.active) }
                compose.onNodeWithContentDescription("Back").performClick()
                compose.waitForIdle()
                scenario.onActivity {
                    val state = ViewModelProvider(it)[TranslationViewModel::class.java].session.state.value
                    assertNull(state.active)
                    assertTrue(state.results.isEmpty())
                }
                compose.onNodeWithText(note.title).performClick()
                compose.onNodeWithTag("translation-${segment.id}").assertDoesNotExist()
                assertEquals(note, app.repository.get(note.id))
            }
        } finally { app.repository.delete(note.id) }
    }
}
