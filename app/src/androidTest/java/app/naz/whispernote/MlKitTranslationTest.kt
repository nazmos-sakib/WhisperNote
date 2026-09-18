package app.naz.whispernote

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.translate.*
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Real SDK smoke test: model download, shared-pack inventory, inference and release. */
@RunWith(AndroidJUnit4::class)
class MlKitTranslationTest {
    @Test fun germanToBengaliAndHindiUsesSharedGermanPack() {
        val manager = RemoteModelManager.getInstance()
        for (code in listOf("de", "bn", "hi")) {
            Tasks.await(manager.download(TranslateRemoteModel.Builder(code).build(), DownloadConditions.Builder().build()), 180, TimeUnit.SECONDS)
        }
        for ((target, range) in listOf("bn" to '\u0980'..'\u09ff', "hi" to '\u0900'..'\u097f')) {
            val translator = Translation.getClient(TranslatorOptions.Builder().setSourceLanguage("de").setTargetLanguage(target).build())
            try {
                val result = Tasks.await(translator.translate("Vielen Dank für Ihre Hilfe."), 60, TimeUnit.SECONDS)
                assertTrue("Expected $target script: $result", result.any { it in range })
            } finally { translator.close() }
        }
    }
    @Test fun germanModelDownloadsAndTranslatesOnDevice() {
        val manager = RemoteModelManager.getInstance()
        val model = TranslateRemoteModel.Builder("de").build()
        val existed = Tasks.await(manager.isModelDownloaded(model), 15, TimeUnit.SECONDS)
        Tasks.await(manager.download(model, DownloadConditions.Builder().build()), 180, TimeUnit.SECONDS)
        assertTrue(Tasks.await(manager.getDownloadedModels(TranslateRemoteModel::class.java), 15, TimeUnit.SECONDS).any { it.language == "de" })
        val translator = Translation.getClient(TranslatorOptions.Builder().setSourceLanguage("de").setTargetLanguage("en").build())
        try {
            val result = Tasks.await(translator.translate("Guten Morgen. Vielen Dank für Ihre Hilfe."), 60, TimeUnit.SECONDS)
            assertTrue("Unexpected translation: $result", result.lowercase().contains("thank"))
            assertFalse(result.contains("Vielen Dank"))
        } finally { translator.close() }
        // Exercise removal only for a pack installed by this test; leave German ready for the user.
        if (!existed) {
            Tasks.await(manager.deleteDownloadedModel(model), 30, TimeUnit.SECONDS)
            assertFalse(Tasks.await(manager.isModelDownloaded(model), 15, TimeUnit.SECONDS))
            Tasks.await(manager.download(model, DownloadConditions.Builder().build()), 180, TimeUnit.SECONDS)
        }
    }
}
