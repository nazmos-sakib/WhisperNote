package app.naz.whispernote

import app.naz.whispernote.core.Note
import app.naz.whispernote.core.Segment
import org.junit.Assert.*
import org.junit.Test

class SegmentEditingTest {
    @Test fun deletingTextKeepsAudioAndResumeCheckpoint() {
        val segment = Segment(0, 182000, "Saved text")
        val note = Note(title = "Test", audio = "content://audio/1", checkpointMs = 182000, segments = listOf(segment))
        val deleted = note.deleteSegment(segment.id)
        assertTrue(deleted.segments.isEmpty())
        assertEquals(note.audio, deleted.audio)
        assertEquals(note.checkpointMs, deleted.checkpointMs)
    }
    @Test fun editStillTargetsSameSegmentAfterEarlierDeletion() {
        val first = Segment(0, 1000, "First")
        val second = Segment(1000, 2000, "Second")
        val note = Note(title = "Test", segments = listOf(first, second)).deleteSegment(first.id).editSegment(second.id, "Corrected")
        assertEquals(listOf(second.copy(text = "Corrected")), note.segments)
        assertEquals(note, note.editSegment(first.id, "Stale edit"))
    }
}
