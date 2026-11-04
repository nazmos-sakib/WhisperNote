package app.naz.whispernote

import android.net.Uri
import androidx.test.platform.app.InstrumentationRegistry
import app.naz.whispernote.core.*
import org.junit.Assert.*
import org.junit.Test
import java.io.*
import java.util.UUID
import java.util.zip.*

class LibraryBackupTest {
    private val context=InstrumentationRegistry.getInstrumentation().targetContext
    @Test fun restorePreservesExtrasAndEmptyLabelsWithFreshIds() {
        val audio=File.createTempFile("backup-test", ".wav",context.cacheDir).apply {writeBytes(byteArrayOf(1,2,3))}
        var restored: LibrarySnapshot?=null
        val database="backup-${UUID.randomUUID()}.db"
        try {
            val segment=Segment(0,1000,"Hello")
            val original=Note(title="Lecture",audio=Uri.fromFile(audio).toString(),duration=1000,status="Completed",checkpointMs=1000,
                segments=listOf(segment),bookmarks=listOf(Bookmark(500,"Key point")),translations=listOf(SavedTranslation(segment.id,"Hello","de","Hallo")))
            val bytes=ByteArrayOutputStream().also {LibraryBackup.write(context,listOf(original),it,listOf("Empty folder"))}.toByteArray()
            val snapshot=LibraryBackup.read(context,ByteArrayInputStream(bytes));restored=snapshot
            val note=snapshot.notes.single()
            assertNotEquals(original.id,note.id);assertNotEquals(segment.id,note.segments.single().id)
            assertEquals(note.segments.single().id,note.translations.single().segmentId)
            assertEquals("Hallo",note.translations.single().text);assertEquals(500L,note.bookmarks.single().position)
            val repo=NoteRepository(context,database)
            try {repo.load();repo.put(original);repo.restore(snapshot.notes,snapshot.labels);repo.load()
                assertEquals(2,repo.notes.value.size);assertTrue("Empty folder" in repo.labels.value)
                assertEquals(note.bookmarks,repo.get(note.id)!!.bookmarks)
            } finally {repo.close()}
        } finally {audio.delete();restored?.let {LibraryBackup.cleanup(it.notes)};context.deleteDatabase(database)}
    }
    @Test fun badLaterEntryCleansEarlierImportedAudio() {
        val audio=File.createTempFile("backup-test", ".wav",context.cacheDir).apply {writeBytes(byteArrayOf(1,2,3))}
        try {
            val note=Note(title="Test",audio=Uri.fromFile(audio).toString())
            val archive=ByteArrayOutputStream().also {NoteArchive.write(context,note,it)}.toByteArray()
            val dir=File(context.filesDir,"audio").apply {mkdirs()}; val before=dir.list()!!.toSet()
            val bytes=ByteArrayOutputStream().also { out -> ZipOutputStream(out).use {zip ->
                zip.putNextEntry(ZipEntry("format"));zip.write("WhisperNoteLibrary/1".toByteArray());zip.closeEntry()
                zip.putNextEntry(ZipEntry("0.whispernote"));zip.write(archive);zip.closeEntry()
                zip.putNextEntry(ZipEntry("../bad"));zip.write(byteArrayOf(1));zip.closeEntry()
            } }.toByteArray()
            try {LibraryBackup.read(context,ByteArrayInputStream(bytes));fail("Invalid backup accepted")} catch(_: IllegalArgumentException) {}
            assertEquals(before,dir.list()!!.toSet())
        } finally {audio.delete()}
    }
}
