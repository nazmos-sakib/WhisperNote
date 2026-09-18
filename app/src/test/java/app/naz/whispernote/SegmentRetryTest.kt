package app.naz.whispernote
import app.naz.whispernote.core.*
import org.junit.Assert.*
import org.junit.Test
class SegmentRetryTest {
    private val target=Segment(1000,5000,"Original")
    private val note=Note(title="Lesson",checkpointMs=9000,segments=listOf(Segment(0,1000,"Before"),target,Segment(5000,9000,"After")))
    private val draft=SegmentRetry(note.id,target,"tiny","en",status="Ready",result=listOf(Segment(1200,2500,"One"),Segment(3000,4500,"Two")))
    @Test fun replacingTextKeepsIdentityAndTimestamps() {
        val updated=note.acceptRetry(draft,false)
        assertEquals(target.copy(text="One Two"),updated.segments[1]);assertEquals(note.checkpointMs,updated.checkpointMs)
        assertEquals(note.segments.first(),updated.segments.first());assertEquals(note.segments.last(),updated.segments.last())
    }
    @Test fun usingNewSegmentsPreservesGapsAsEmptyRanges() {
        val updated=note.acceptRetry(draft,true)
        assertEquals(listOf(0L,1000L,1200L,2500L,3000L,4500L,5000L),updated.segments.map {it.start})
        assertEquals(listOf("Before","","One","","Two","","After"),updated.segments.map {it.text})
        assertEquals(note.checkpointMs,updated.checkpointMs)
    }
    @Test fun staleSuggestionCannotOverwriteManualEdits() {
        try {note.editSegment(target.id,"My correction").acceptRetry(draft,false);fail("Overwrote edits")} catch(_:IllegalArgumentException) {}
    }
    @Test fun exportsSkipEmptyTextAndNumberSubtitlesConsecutively() {
        val cleared=note.clearSegment(target.id)
        val srt=Exporter.render(cleared,"srt")
        assertFalse(srt.contains("00:00:01,000 --> 00:00:05,000"))
        assertTrue(srt.contains("2\n00:00:05,000"));assertFalse(Exporter.render(cleared,"md").contains("**00:01**"))
    }
}
