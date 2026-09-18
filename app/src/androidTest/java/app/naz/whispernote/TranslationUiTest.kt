package app.naz.whispernote

import androidx.activity.ComponentActivity
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import app.naz.whispernote.core.*
import app.naz.whispernote.ui.theme.WhisperNoteTheme
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class TranslationUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @Before fun window() { compose.activityRule.scenario.onActivity { it.showUiTestWindow() } }

    @Test fun modelManagerShowsTenPairsAndProtectsSharedActivePack() {
        val manager = com.google.mlkit.common.model.RemoteModelManager.getInstance()
        com.google.android.gms.tasks.Tasks.await(manager.download(
            com.google.mlkit.nl.translate.TranslateRemoteModel.Builder("de").build(),
            com.google.mlkit.common.model.DownloadConditions.Builder().build()), 180, java.util.concurrent.TimeUnit.SECONDS)
        lateinit var vm: TranslationViewModel
        compose.setContent { WhisperNoteTheme {
            vm = androidx.lifecycle.viewmodel.compose.viewModel()
            TranslationModelsSheet(vm) {}
        } }
        compose.waitUntil(10000) { runCatching { !vm.models.value.checking && "de" in vm.models.value.downloaded }.getOrDefault(false) }
        TranslationCatalog.pairs.forEach { compose.onNodeWithText(it.label).assertExists() }
        compose.onNodeWithText("German → Hindi").performScrollTo().assertIsDisplayed()
        compose.runOnIdle { assertTrue(vm.activate(TranslationPair("de", "en"))) }
        compose.onNodeWithContentDescription("Remove German pack").performScrollTo().assertIsNotEnabled()
        compose.runOnIdle { vm.session.deactivate() }
        compose.onNodeWithContentDescription("Remove German pack").performClick()
        compose.onNodeWithText("Remove German pack?").assertExists()
        compose.onNodeWithText("These options will need another download:", substring=true).assertExists()
        compose.onNodeWithText("Cancel").performClick()
        compose.runOnIdle { assertTrue("de" in vm.models.value.downloaded) }
        compose.onNodeWithText("Translation models").performScrollTo()
        val instrumentation = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
        val bitmap = instrumentation.uiAutomation.takeScreenshot()
        java.io.File(instrumentation.targetContext.getExternalFilesDir(null), "translation-models.png").outputStream().use {
            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
        bitmap.recycle()
    }

    @Test fun ringInlineResultCollapseAndDeactivationKeepOriginalReadable() {
        var completion: ((Result<String>) -> Unit)? = null
        var suppliedText: String? = null
        val session = TranslationSession { object : SegmentTranslator {
            override fun translate(text: String, complete: (Result<String>) -> Unit) { suppliedText = text; completion = complete }
            override fun close() {}
        } }
        val segment = Segment(0, 5000, "Guten Morgen. Wie geht es Ihnen?", "translation-fixture")
        val note = Note(title = "Translation fixture", status = "Completed", segments = listOf(segment))
        var toolbarOpened = false
        compose.runOnUiThread { session.enter(note.id); session.activate(TranslationPair("de", "en")) }
        compose.setContent { WhisperNoteTheme {
            val state by session.state.collectAsState()
            TranscriptDetailContent(note, "", Playback(), {}, { _, _ -> }, {}, {}, { _, _ -> }, {}, {}, {}, {},
                translations = state, onTranslator = { toolbarOpened = true }, onTranslate = {
                    if (state.results[it.id]?.text != null) session.toggle(it.id) else session.request(it.id, it.text)
                })
        } }
        compose.onNodeWithTag("translation-toolbar").performClick()
        compose.runOnIdle { assertTrue(toolbarOpened) }
        compose.onNodeWithTag("segment-translate-${segment.id}").performScrollTo().performClick()
        compose.onNodeWithTag("translation-progress-${segment.id}").assertExists()
        compose.onNodeWithTag("segment-translate-${segment.id}").assertIsNotEnabled()
        compose.runOnIdle { assertEquals(segment.text, suppliedText); completion!!(Result.success("Good morning. How are you?")) }
        compose.onNodeWithTag("translation-progress-${segment.id}").assertDoesNotExist()
        compose.onNodeWithText(segment.text).assertExists()
        compose.onNodeWithText("Good morning. How are you?").assertExists()
        compose.onNodeWithContentDescription("Powered by Google Translate").assertExists()
        compose.runOnIdle { session.deactivate() }
        compose.onNodeWithText("Good morning. How are you?").assertExists()
        compose.onNodeWithTag("segment-translate-${segment.id}").performScrollTo().performClick()
        compose.onNodeWithText("Good morning. How are you?").assertDoesNotExist()
        compose.onNodeWithTag("segment-translate-${segment.id}").performClick()
        compose.onNodeWithText("Good morning. How are you?").assertExists()
        compose.runOnIdle { session.leave() }
        compose.onNodeWithText("Good morning. How are you?").assertDoesNotExist()
        compose.onNodeWithText(segment.text).assertExists()
    }
}
