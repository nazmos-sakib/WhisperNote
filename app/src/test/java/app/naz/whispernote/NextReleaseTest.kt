package app.naz.whispernote

import app.naz.whispernote.core.*
import org.junit.Assert.*
import org.junit.Test

class NextReleaseTest {
    @Test fun pausedNotesAreResumableAndNotBusy() {
        val note=Note(title="Lecture",status="Paused",checkpointMs=5000,duration=10000)
        assertFalse(note.busy);assertTrue(note.resumable)
    }
    @Test fun editsInvalidateOnlyTheirOwnTranslationsAndKeepBookmarks() {
        val first=Segment(0,1000,"Hello", "a"); val second=Segment(1000,2000,"Bye","b")
        val bookmark=Bookmark(500,"Follow up")
        val note=Note(title="Test",segments=listOf(first,second),bookmarks=listOf(bookmark),translations=listOf(
            SavedTranslation("a","Hello","de","Hallo"),SavedTranslation("b","Bye","de","Tschüss")))
        val edited=note.editSegment("a","Welcome")
        assertEquals(listOf("b"),edited.translations.map {it.segmentId})
        assertEquals(listOf(bookmark),edited.bookmarks)
    }
    @Test fun translatedExportOmitsStaleOrDeletedSources() {
        val note=Note(title="Test",segments=listOf(Segment(0,1000,"Hello","a")),translations=listOf(
            SavedTranslation("a","Hello","de","Hallo"),SavedTranslation("a","Old","fr","Stale"),SavedTranslation("gone","Bye","de","Deleted")))
        val output=Exporter.render(note,"translated")
        assertTrue(output.contains("Hallo")); assertFalse(output.contains("Stale")); assertFalse(output.contains("Deleted"))
    }
    @Test fun resumedAppendPreservesExtras() {
        val note=Note(title="Test",duration=10000,status="Paused",checkpointMs=1000,bookmarks=listOf(Bookmark(500,"Important")))
        val next=note.appendFinalized(Segment(1000,2000,"Next"))
        assertEquals(note.bookmarks,next.bookmarks); assertEquals(2000L,next.checkpointMs)
    }
}
