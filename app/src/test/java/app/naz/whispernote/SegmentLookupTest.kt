package app.naz.whispernote
import app.naz.whispernote.core.*
import org.junit.Assert.*
import org.junit.Test
class SegmentLookupTest {
    @Test fun findsSegmentsAtBoundariesAndLeavesGapsInactive() {
        val segments=listOf(Segment(100,200,"A"),Segment(200,300,"B"),Segment(400,500,"C"))
        assertEquals(-1,segmentAtPosition(segments,99))
        assertEquals(0,segmentAtPosition(segments,100))
        assertEquals(1,segmentAtPosition(segments,200))
        assertEquals(-1,segmentAtPosition(segments,300))
        assertEquals(2,segmentAtPosition(segments,499))
        assertEquals(-1,segmentAtPosition(segments,500))
        assertEquals(-1,segmentAtPosition(emptyList(),0))
    }
}
