package app.naz.whispernote

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.naz.whispernote.core.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class ResumeTranscriptionTest {
    @Test fun liveSegmentSurvivesInterruptionAndResumesWithoutReplacingEdits() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val modelId = InstrumentationRegistry.getArguments().getString("resumeModel") ?: "tiny"
        (context.applicationContext as WhisperApp).ready.await()
        val dbName = "checkpoint-instrumentation.db"
        var repo = NoteRepository(context, dbName)
        val wav = File(context.cacheDir, "resume-fixture.wav")
        val pcm = File(context.cacheDir, "resume-fixture.pcm")
        val longPcm = File(context.cacheDir, "resume-long-fixture.pcm")
        var returned = false
        var interrupted = false
        try {
            instrumentation.context.assets.open("jfk.wav").use { input -> wav.outputStream().use { input.copyTo(it) } }
            AudioDecoder(context).decode(Uri.fromFile(wav), pcm)
            longPcm.outputStream().use { out -> repeat(3) { pcm.inputStream().use { it.copyTo(out) } } }
            val duration = longPcm.length() / 4 * 1000 / 16000
            val models = ModelManager(context)
            models.download(modelId) {}
            val note = Note(title = "Resume test", duration = duration, status = "Transcribing", model = modelId)
            repo.put(note)
            try {
                WhisperEngine().transcribe(models.file(modelId).path, longPcm.path, 0, "en", object : WhisperEngine.Listener {
                    override fun onProgress(progress: Int) = Unit
                    override fun onSegment(start: Long, end: Long, text: String) {
                        assertFalse("Segment must arrive while inference is still running", returned)
                        repo.update(note.id) { it.appendFinalized(Segment(start, end, text.trim())) }
                        assertEquals(end, repo.notes.value.single().checkpointMs)
                        interrupted = true // Simulates cancellation immediately after a committed native callback.
                    }
                    override fun isCancelled() = interrupted
                })
                fail("Expected interrupted inference")
            } catch (expected: IllegalStateException) { assertTrue(interrupted) }
            finally { returned = true }
            val partial = repo.get(note.id)!!
            assertTrue(partial.checkpointMs in 1 until duration)
            assertEquals(1, partial.segments.size)
            // Reopen the database: this is the state available to a fresh app process.
            repo.close()
            repo = NoteRepository(context, dbName)
            repo.load()
            repo.recoverInterrupted()
            assertEquals("Interrupted", repo.get(note.id)!!.status)
            assertEquals(partial.segments, repo.get(note.id)!!.segments)
            assertEquals(partial.checkpointMs, repo.get(note.id)!!.checkpointMs)
            repo.update(note.id) { it.copy(segments = it.segments.map { s -> s.copy(text = "User's correction") }) }
            val saved = repo.get(note.id)!!
            val starts = mutableListOf<Long>()
            WhisperEngine().transcribe(models.file(modelId).path, longPcm.path, saved.checkpointMs, "en", object : WhisperEngine.Listener {
                override fun onProgress(progress: Int) { assertTrue(progress in (saved.checkpointMs * 100 / duration).toInt()..99) }
                override fun onSegment(start: Long, end: Long, text: String) {
                    assertTrue(start >= saved.checkpointMs)
                    starts.add(start)
                    repo.update(note.id) { it.appendFinalized(Segment(start, end, text.trim())) }
                }
                override fun isCancelled() = false
            })
            assertTrue(starts.isNotEmpty())
            assertEquals(saved.segments.single(), repo.get(note.id)!!.segments.first())
            val all = repo.get(note.id)!!.segments
            assertTrue(all.zipWithNext().all { (a, b) -> a.end <= b.start })
            assertTrue(all.drop(1).joinToString(" ") { it.text }.contains("country", ignoreCase = true))
        } finally {
            repo.close()
            context.deleteDatabase(dbName)
            wav.delete(); pcm.delete(); longPcm.delete()
        }
    }
}
