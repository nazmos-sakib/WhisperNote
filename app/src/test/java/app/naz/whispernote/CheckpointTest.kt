package app.naz.whispernote

import app.naz.whispernote.core.*
import org.junit.Assert.*
import org.junit.Test

class CheckpointTest {
    @Test fun checkpointAndTextAdvanceTogether() {
        val note = Note(title = "Interview", duration = 600_000)
            .appendFinalized(Segment(0, 182_000, "Saved words"))
        assertEquals(182_000, note.checkpointMs)
        assertEquals("Saved words", note.segments.single().text)
        val interrupted = note.interrupted("Stopped")
        assertFalse(interrupted.busy)
        assertTrue(interrupted.resumable)
        assertEquals(note.segments, interrupted.segments)
        assertEquals(note.checkpointMs, interrupted.checkpointMs)
    }

    @Test fun resumedAppendKeepsManualEditsAndOriginalTimestamps() {
        val saved = Note(title = "Edited title", duration = 600_000, checkpointMs = 182_000,
            segments = listOf(Segment(0, 182_000, "Manually corrected text")))
        val resumed = saved.appendFinalized(Segment(182_000, 190_000, "New words"))
        assertEquals(saved.segments.first(), resumed.segments.first())
        assertEquals(190_000, resumed.checkpointMs)
        assertEquals("Edited title", resumed.title)
    }

    @Test(expected = IllegalArgumentException::class)
    fun cannotAppendPreviouslyCommittedAudioAgain() {
        Note(title = "Note", duration = 600_000, checkpointMs = 182_000)
            .appendFinalized(Segment(181_000, 190_000, "Overlap"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun cannotSaveCheckpointPastAudioEnd() {
        Note(title = "Note", duration = 600_000)
            .appendFinalized(Segment(599_000, 601_000, "Invalid"))
    }

    @Test fun progressEstimateDoesNotAdvanceCheckpoint() {
        val note = Note(title = "Note", duration = 600_000, checkpointMs = 182_000)
        assertEquals(182_000, note.copy(progress = 95).checkpointMs)
    }
}
