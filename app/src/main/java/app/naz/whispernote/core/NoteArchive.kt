package app.naz.whispernote.core

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.io.*
import java.util.UUID
import java.util.zip.*

/** Versioned portable note. Entry names are fixed; untrusted paths are never extracted. */
object NoteArchive {
    const val MIME = "application/vnd.whispernote"
    private const val MAX_AUDIO = 4L * 1024 * 1024 * 1024
    private const val MAX_METADATA = 16L * 1024 * 1024
    fun write(context: Context, note: Note, output: OutputStream) {
        require(note.audio.isNotBlank()) { "The audio is not available yet." }
        val manifest = JSONObject().apply {
            put("format", "WhisperNote"); put("version", 1); put("title", note.title)
            put("duration", note.duration); put("created", note.created); put("modified", note.modified)
            put("language", note.language); put("model", note.model); put("label", note.label)
            put("status", if(note.busy) "Interrupted" else note.status); put("checkpointMs", note.checkpointMs)
            put("segments", JSONArray().apply { note.segments.forEach { s -> put(JSONObject().put("start",s.start).put("end",s.end).put("text",s.text)) } })
        }.toString().toByteArray(Charsets.UTF_8)
        require(manifest.size <= MAX_METADATA) { "This transcript is too large to export." }
        ZipOutputStream(output).use { zip ->
            zip.putNextEntry(ZipEntry("note.json")); zip.write(manifest); zip.closeEntry()
            zip.putNextEntry(ZipEntry("audio"))
            requireNotNull(context.contentResolver.openInputStream(Uri.parse(note.audio))) { "Cannot read the original audio." }.use { copyLimited(it,zip,MAX_AUDIO) }
            zip.closeEntry()
        }
    }
    fun read(context: Context, input: InputStream): Note {
        val id=UUID.randomUUID().toString()
        val file=File(context.filesDir,"audio").apply { mkdirs() }.resolve("$id.audio")
        var metadata: ByteArray?=null
        var audio=false
        try {
            ZipInputStream(input).use { zip ->
                var entry=zip.nextEntry
                while(entry!=null) {
                    require(!entry.isDirectory) { "Invalid note archive." }
                    when(entry.name) {
                        "note.json" -> { require(metadata==null) { "Duplicate metadata." }; metadata=ByteArrayOutputStream().also { copyLimited(zip,it,MAX_METADATA) }.toByteArray() }
                        "audio" -> { require(!audio) { "Duplicate audio." }; file.outputStream().use { copyLimited(zip,it,MAX_AUDIO) }; audio=true }
                        else -> error("Unexpected archive entry.")
                    }
                    zip.closeEntry(); entry=zip.nextEntry
                }
            }
            require(audio && file.length()>0 && metadata!=null) { "The file must contain a transcript and audio." }
            val j=JSONObject(String(metadata!!,Charsets.UTF_8))
            require(j.getString("format")=="WhisperNote" && j.getInt("version")==1) { "Unsupported WhisperNote file version." }
            val duration=j.getLong("duration"); val checkpoint=j.getLong("checkpointMs")
            require(duration>=0 && checkpoint in 0..duration) { "Invalid recording duration." }
            val segments=j.getJSONArray("segments").let { a -> List(a.length()) { i ->
                val s=a.getJSONObject(i); val start=s.getLong("start"); val end=s.getLong("end")
                require(start>=0 && end>=start && end<=duration) { "Invalid segment timestamps." }
                Segment(start,end,s.getString("text"))
            } }
            require(segments.zipWithNext().all { (a,b) -> a.start<=b.start }) { "Segments must be in timestamp order." }
            val status=j.getString("status")
            require(status in listOf("Completed","Interrupted","Failed")) { "Invalid transcription state." }
            val label=if(j.isNull("label")) null else j.getString("label").takeIf { it.isNotBlank() }
            require(label==null || label.length<=60) { "Invalid label." }
            return Note(id=id,title=j.getString("title"),audio=Uri.fromFile(file).toString(),owned=true,
                duration=duration,created=j.getLong("created"),modified=j.getLong("modified"),language=j.getString("language"),
                model=j.getString("model"),status=status,checkpointMs=checkpoint,segments=segments,label=label,
                error=if(status!="Completed") "Imported partial transcript. Download its model to resume." else null)
        } catch(e: Exception) { file.delete(); throw e }
    }
    private fun copyLimited(input: InputStream, output: OutputStream, limit: Long) {
        val buffer=ByteArray(64*1024); var total=0L
        while(true) { val count=input.read(buffer); if(count<0) break; total+=count
            require(total<=limit) { "Archive exceeds the supported size limit." }; output.write(buffer,0,count) }
    }
}
