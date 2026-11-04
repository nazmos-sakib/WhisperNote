package app.naz.whispernote.core

import android.content.Context
import android.net.Uri
import java.io.*
import java.util.zip.*

/** Nested portable notes, staged before committing any library changes. */
data class LibrarySnapshot(val notes: List<Note>, val labels: List<String>)

object LibraryBackup {
    fun write(context: Context, notes: List<Note>, output: OutputStream, labels: List<String> = notes.flatMap {it.labels}.distinct()) {
        require(notes.isNotEmpty() || labels.isNotEmpty()) { "Your library is empty." }
        ZipOutputStream(output).use { zip ->
            zip.putNextEntry(ZipEntry("format")); zip.write("WhisperNoteLibrary/1".toByteArray()); zip.closeEntry()
            zip.putNextEntry(ZipEntry("labels.json")); zip.write(org.json.JSONArray(labels).toString().toByteArray()); zip.closeEntry()
            notes.forEachIndexed { index,note ->
                val temp=File.createTempFile("backup-", ".whispernote",context.cacheDir)
                try {
                    temp.outputStream().use { NoteArchive.write(context,note,it) }
                    zip.putNextEntry(ZipEntry("$index.whispernote")); temp.inputStream().use { it.copyTo(zip) }; zip.closeEntry()
                } finally {temp.delete()}
            }
        }
    }
    fun read(context: Context, input: InputStream): LibrarySnapshot {
        val notes=mutableListOf<Note>()
        var labels: List<String>?=null
        try {
            ZipInputStream(input).use { zip ->
                require(zip.nextEntry?.name=="format") { "Not a WhisperNote library backup." }
                val marker=ByteArray(19)
                var count=0
                while(count<marker.size) { val n=zip.read(marker,count,marker.size-count); if(n<0) break; count+=n }
                require(String(marker,0,count)=="WhisperNoteLibrary/1" && zip.read()==-1) { "Unsupported library backup." }
                zip.closeEntry()
                val names=mutableSetOf<String>()
                var entry=zip.nextEntry
                while(entry!=null) {
                    if(entry.name=="labels.json") {
                        require(labels==null && !entry.isDirectory) {"Invalid labels entry."}
                        val out=ByteArrayOutputStream(); val buffer=ByteArray(4096)
                        while(true) {val n=zip.read(buffer);if(n<0) break;require(out.size()+n<=1024*1024) {"Labels exceed size limit."};out.write(buffer,0,n)}
                        val a=org.json.JSONArray(out.toString("UTF-8"))
                        labels=List(a.length()) {a.getString(it).also {name -> require(name.isNotBlank() && name.length<=60)}}
                        zip.closeEntry();entry=zip.nextEntry;continue
                    }
                    require(!entry.isDirectory && entry.name.matches(Regex("[0-9]+\\.whispernote")) && names.add(entry.name) && names.size<=10000) { "Invalid backup entry." }
                    val temp=File.createTempFile("restore-", ".whispernote",context.cacheDir)
                    try {
                        temp.outputStream().use { out ->
                            val buffer=ByteArray(65536); var total=0L
                            while(true) {val n=zip.read(buffer); if(n<0) break; total+=n; require(total<=5L*1024*1024*1024) {"Backup note exceeds size limit."}; out.write(buffer,0,n)}
                        }
                        notes+=temp.inputStream().use {NoteArchive.read(context,it)}
                    } finally {temp.delete()}
                    zip.closeEntry(); entry=zip.nextEntry
                }
            }
            require(notes.isNotEmpty() || !labels.isNullOrEmpty()) { "The backup contains no notes or labels." }
            return LibrarySnapshot(notes,labels ?: emptyList())
        } catch(e: Exception) {cleanup(notes); throw e}
    }
    fun cleanup(notes: List<Note>) {notes.forEach {Uri.parse(it.audio).path?.let {path -> File(path).delete()}}}
}
