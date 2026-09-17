package app.naz.whispernote

import android.net.Uri
import androidx.test.platform.app.InstrumentationRegistry
import app.naz.whispernote.core.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.*
import java.util.UUID
import java.util.zip.*

class NoteOrganizationTest {
    private val context=InstrumentationRegistry.getInstrumentation().targetContext
    @Test fun labelsRenameDeleteAndPersistWithoutDeletingNotes() {
        val db="labels-${UUID.randomUUID()}.db"
        try {
            val repo=NoteRepository(context,db); repo.load()
            val label=repo.createLabel(" German season 1 ")
            assertEquals(label,repo.createLabel("GERMAN SEASON 1"))
            val note=Note(title="Episode one",segments=listOf(Segment(0,1000,"Guten Tag")))
            repo.put(note);repo.assignLabel(note.id,label);repo.renameLabel(label,"German lessons");repo.close()
            val reopened=NoteRepository(context,db);reopened.load()
            assertEquals("German lessons",reopened.get(note.id)!!.label)
            reopened.deleteLabel("German lessons")
            assertNull(reopened.get(note.id)!!.label)
            assertEquals(note.segments,reopened.get(note.id)!!.segments)
            assertTrue(reopened.labels.value.isEmpty());reopened.close()
        } finally {context.deleteDatabase(db)}
    }
    @Test fun completeNoteRoundTripIncludesIdenticalAudioAndTimestampedEdits() {
        val audio=File(context.cacheDir,"roundtrip-${UUID.randomUUID()}.wav")
        InstrumentationRegistry.getInstrumentation().context.assets.open("jfk.wav").use { input -> audio.outputStream().use {input.copyTo(it)} }
        var imported: Note?=null
        try {
            val original=Note(title="German lesson",audio=Uri.fromFile(audio).toString(),duration=11000,created=1234,modified=5678,
                language="de",model="base-q5_1",status="Completed",checkpointMs=11000,label="Season 1",
                segments=listOf(Segment(120,2500,"Corrected words"),Segment(3100,10000,"Second sentence")))
            val bytes=ByteArrayOutputStream().also {NoteArchive.write(context,original,it)}.toByteArray()
            imported=NoteArchive.read(context,ByteArrayInputStream(bytes))
            assertNotEquals(original.id,imported.id)
            assertEquals(original.title,imported.title);assertEquals(original.label,imported.label)
            assertEquals(original.created,imported.created);assertEquals(original.modified,imported.modified)
            assertEquals(original.model,imported.model);assertEquals(original.language,imported.language)
            assertEquals(original.checkpointMs,imported.checkpointMs);assertEquals(original.status,imported.status)
            assertEquals(original.segments.map {Triple(it.start,it.end,it.text)},imported.segments.map {Triple(it.start,it.end,it.text)})
            assertArrayEquals(audio.readBytes(),File(Uri.parse(imported.audio).path!!).readBytes())
        } finally {audio.delete();imported?.audio?.let {File(Uri.parse(it).path!!).delete()}}
    }
    @Test fun malformedArchiveCleansCopiedAudio() {
        val directory=File(context.filesDir,"audio").apply {mkdirs()}
        val before=directory.list()!!.toSet()
        val bytes=ByteArrayOutputStream().also { output -> ZipOutputStream(output).use { zip ->
            zip.putNextEntry(ZipEntry("audio"));zip.write(byteArrayOf(1,2,3));zip.closeEntry()
            zip.putNextEntry(ZipEntry("../outside"));zip.write(byteArrayOf(1));zip.closeEntry()
        } }.toByteArray()
        try {NoteArchive.read(context,ByteArrayInputStream(bytes));fail("Unsafe archive accepted")} catch(_: Exception) {}
        assertEquals(before,directory.list()!!.toSet())
    }
    @Test fun existingUnlabelledNotesRemainReadable() {
        val db="legacy-${UUID.randomUUID()}.db"
        try {
            val repo=NoteRepository(context,db);repo.load();val note=Note(title="Old note");repo.put(note)
            val raw=repo.readableDatabase.rawQuery("SELECT document FROM notes",null).use {it.moveToFirst();JSONObject(it.getString(0))}
            raw.remove("label")
            repo.writableDatabase.execSQL("UPDATE notes SET document=?",arrayOf(raw.toString()));repo.load()
            assertNull(repo.get(note.id)!!.label);repo.close()
        } finally {context.deleteDatabase(db)}
    }
}
