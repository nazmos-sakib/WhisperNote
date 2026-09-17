package app.naz.whispernote

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import app.naz.whispernote.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.File

class NotesViewModel(app: Application): AndroidViewModel(app) {
    private val application=app as WhisperApp
    private val repo=application.repository
    private val edits=kotlinx.coroutines.channels.Channel<()->Unit>(kotlinx.coroutines.channels.Channel.UNLIMITED)
    init { application.scope.launch { for(edit in edits) edit() } }
    val notes=repo.notes
    val error=MutableStateFlow<String?>(null)
    val models=ModelManager(app)
    fun import(uri: Uri,model: String) { application.scope.launch {
        try {
            application.ready.await()
            val name=application.contentResolver.query(uri,arrayOf(OpenableColumns.DISPLAY_NAME),null,null,null)?.use { if(it.moveToFirst()) it.getString(0) else null } ?: "Audio note"
            val note=Note(title=name.substringBeforeLast('.'),model=model)
            var owned=false; var audio=uri
            try { application.contentResolver.takePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            catch(_: SecurityException) {
                val file=File(application.filesDir,"audio").apply { mkdirs() }.resolve("${note.id}.audio")
                try { application.contentResolver.openInputStream(uri)!!.use { input -> file.outputStream().use { input.copyTo(it) } } }
                catch(e: Exception) { file.delete(); throw e }
                audio=Uri.fromFile(file); owned=true
            }
            repo.put(note.copy(audio=audio.toString(),owned=owned)); start(note.id)
        } catch(e: Exception) { error.value=e.message }
    } }
    fun importUrl(url: String,model: String) { application.scope.launch {
        try { application.ready.await(); require(java.net.URL(url).protocol=="https") { "Enter a direct HTTPS media URL." }; val n=Note(title=Uri.parse(url).lastPathSegment?.substringBeforeLast('.')?.takeIf { it.isNotBlank() } ?: "Downloaded audio",source=url,model=model); repo.put(n); start(n.id) }
        catch(e: Exception) { error.value=e.message }
    } }
    fun start(id: String) { try { ProcessingService.start(application,id) } catch(e: Exception) { repo.update(id) { it.copy(status="Failed",error=e.message) } } }
    fun title(id: String,value: String) { edits.trySend { repo.update(id) { it.copy(title=value) } } }
    fun segment(id: String,segmentId: String,value: String) { edits.trySend { repo.update(id) { it.editSegment(segmentId,value) } } }
    fun deleteSegment(id: String,segmentId: String) { edits.trySend { repo.update(id) { it.deleteSegment(segmentId) } } }
    fun delete(n: Note,deleteAudio: Boolean) { application.scope.launch { if(deleteAudio && n.owned) Uri.parse(n.audio).path?.let { File(it).delete() }; repo.delete(n.id) } }
}
