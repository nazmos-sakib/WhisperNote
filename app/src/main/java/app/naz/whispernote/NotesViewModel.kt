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
    init { application.scope.launch { application.ready.await(); for(edit in edits) edit() } }
    val transcription=application.transcription
    val listeningProgress=PlaybackPositions(application)
    fun markCompleted(id: String, completed: Boolean) { listeningProgress.markCompleted(id,completed) }
    fun loadModel(model: String) {
        try {
            require(application.retries.drafts.value.values.none {it.busy}) {"Wait for re-transcription to finish."}
            require(models.ready(model)) { "Download this model from Models first." }
            transcription.begin("Loading model…")
            application.scope.launch {
                try { transcription.ensureLoaded(model) } catch(e: Exception) { error.value=e.message }
                finally { transcription.finish() }
            }
        } catch(e: Exception) { error.value=e.message }
    }
    fun unloadModel() { try { transcription.unload() } catch(e: Exception) { error.value=e.message } }
    val retryDrafts=application.retries.drafts
    fun retrySegment(noteId: String, segmentId: String, model: String, language: String) = editSafely {
        val note=requireNotNull(repo.get(noteId))
        require(application.retries.drafts.value.values.none {it.busy || it.status=="Ready"} && application.retries.get(noteId)==null) {"Review or discard the existing suggestion first."}
        require(models.ready(model)) {"Download the selected model in Models first."}
        require(language.isBlank() || language.matches(Regex("[a-z]{2,3}"))) {"Enter a language code such as de or en, or leave it blank for automatic detection."}
        val segment=note.segments.first {it.id==segmentId}
        require(segment.end>segment.start && note.audio.isNotBlank()) {"This segment has no available audio range."}
        val draft=SegmentRetry(noteId,segment,model,language)
        application.retries.start(draft)
        try {ProcessingService.start(application,noteId,retryId=draft.id)}
        catch(e: Exception) {application.retries.put(draft.copy(status="Failed",error=e.message))}
    }
    fun discardRetry(noteId: String) = editSafely {
        application.retries.remove(noteId)
    }
    fun acceptRetry(noteId: String, split: Boolean) = editSafely {
        val draft=requireNotNull(application.retries.get(noteId))
        repo.update(noteId) {it.acceptRetry(draft,split)}
        application.retries.remove(noteId)
    }
    val labels=repo.labels
    val transfer=MutableStateFlow<String?>(null)
    val importedNote=MutableStateFlow<String?>(null)
    fun createLabel(name: String) = editSafely { repo.createLabel(name) }
    fun renameLabel(old: String, name: String) = editSafely { repo.renameLabel(old,name) }
    fun deleteLabel(name: String) = editSafely { repo.deleteLabel(name) }
    fun setLabel(id: String, label: String, selected: Boolean) = editSafely { repo.setLabel(id,label,selected) }
    fun clearLabels(id: String) = editSafely { repo.clearLabels(id) }
    private fun editSafely(block: () -> Unit) { edits.trySend { try { block() } catch(e: Exception) { error.value=e.message } } }
    fun importArchive(uri: Uri) {
        if(transfer.value!=null) return
        transfer.value="Importing complete note…"
        application.scope.launch {
            var imported: Note?=null
            try {
                application.ready.await()
                imported=requireNotNull(application.contentResolver.openInputStream(uri)).use { NoteArchive.read(application,it) }
                val labels=imported.labels.map { repo.createLabel(it) }.toSet()
                repo.put(imported.copy(labels=labels)); importedNote.value=imported.id
            } catch(e: Exception) { imported?.audio?.let { Uri.parse(it).path?.let { path -> File(path).delete() } }; error.value=e.message }
            finally { transfer.value=null }
        }
    }
    fun libraryBackup(uri: Uri, restore: Boolean) {
        if(transfer.value!=null) return
        transfer.value=if(restore) "Restoring library…" else "Backing up library…"
        application.scope.launch {
            var staged: List<Note> = emptyList()
            try {
                application.ready.await()
                val barrier=CompletableDeferred<Unit>(); edits.send {barrier.complete(Unit)}; barrier.await()
                if(restore) {
                    val snapshot=requireNotNull(application.contentResolver.openInputStream(uri)).use {LibraryBackup.read(application,it)}
                    staged=snapshot.notes
                    repo.restore(staged,snapshot.labels); staged=emptyList()
                } else {
                    val snapshot=repo.notes.value
                    val temp=File.createTempFile("library-", ".zip",application.cacheDir)
                    try {
                        temp.outputStream().use {LibraryBackup.write(application,snapshot,it,repo.labels.value)}
                        requireNotNull(application.contentResolver.openOutputStream(uri,"wt")).use {out -> temp.inputStream().use {it.copyTo(out)}}
                    } finally {temp.delete()}
                }
            } catch(e: Exception) {LibraryBackup.cleanup(staged); error.value=e.message}
            finally {transfer.value=null}
        }
    }
    fun exportArchive(note: Note, destination: Uri?, shared: (File) -> Unit = {}) {
        if(transfer.value!=null) return
        transfer.value="Packing audio and transcript…"
        application.scope.launch {
            var temporary: File?=null
            var sharedSuccessfully=false
            try {
                application.ready.await()
                // Queue a barrier so preceding autosaved edits are included in the snapshot.
                val barrier=CompletableDeferred<Unit>(); edits.send { barrier.complete(Unit) }; barrier.await()
                val snapshot=requireNotNull(repo.get(note.id)) { "This note no longer exists." }
                temporary=File(application.cacheDir,"exports").apply { mkdirs() }.resolve("${java.util.UUID.randomUUID()}.whispernote")
                temporary.outputStream().use { NoteArchive.write(application,snapshot,it) }
                if(destination!=null) requireNotNull(application.contentResolver.openOutputStream(destination)).use { out -> temporary.inputStream().use { it.copyTo(out) } }
                else withContext(Dispatchers.Main) { shared(temporary); sharedSuccessfully=true }
            } catch(e: Exception) { error.value=e.message }
            finally { if(!sharedSuccessfully) temporary?.delete(); transfer.value=null }
        }
    }
    val notes=repo.notes
    val error=MutableStateFlow<String?>(null)
    val models=ModelManager(app)
    fun import(uri: Uri,model: String,label: String? = null) { application.scope.launch {
        try {
            application.ready.await()
            val name=application.contentResolver.query(uri,arrayOf(OpenableColumns.DISPLAY_NAME),null,null,null)?.use { if(it.moveToFirst()) it.getString(0) else null } ?: "Audio note"
            val note=Note(title=name.substringBeforeLast('.'),model=model,labels=listOfNotNull(label).toSet())
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
    fun importUrl(url: String,model: String,label: String? = null) { application.scope.launch {
        try { application.ready.await(); require(java.net.URL(url).protocol=="https") { "Enter a direct HTTPS media URL." }; val n=Note(title=Uri.parse(url).lastPathSegment?.substringBeforeLast('.')?.takeIf { it.isNotBlank() } ?: "Downloaded audio",source=url,model=model,labels=listOfNotNull(label).toSet()); repo.put(n); start(n.id) }
        catch(e: Exception) { error.value=e.message }
    } }
    fun start(id: String) { try { ProcessingService.start(application,id) } catch(e: Exception) { repo.update(id) { it.copy(status="Failed",error=e.message) } } }
    fun bookmark(id: String, position: Long, comment: String) = editSafely { repo.update(id) { it.copy(bookmarks=it.bookmarks + Bookmark(position.coerceIn(0,it.duration),comment.trim())) } }
    fun removeBookmark(id: String, bookmarkId: String) = editSafely { repo.update(id) { it.copy(bookmarks=it.bookmarks.filterNot { b -> b.id==bookmarkId }) } }
    fun saveTranslations(id: String, results: Map<String,SegmentTranslation>) = editSafely {
        repo.update(id) { note ->
            val saved=results.mapNotNull { (segmentId,t) -> if(t.text!=null && !t.pending && note.segments.any {it.id==segmentId && it.text==t.source}) SavedTranslation(segmentId,t.source,t.target,t.text) else null }
            note.copy(translations=note.translations.filterNot { old -> saved.any { it.segmentId==old.segmentId && it.target==old.target } } + saved)
        }
    }
    fun title(id: String,value: String) { edits.trySend { repo.update(id) { it.copy(title=value) } } }
    fun segment(id: String,segmentId: String,value: String) { edits.trySend { repo.update(id) { it.editSegment(segmentId,value) } } }
    fun clearSegment(id: String,segmentId: String) { edits.trySend { repo.update(id) { it.clearSegment(segmentId) } } }
    fun delete(n: Note,deleteAudio: Boolean) { application.scope.launch { if(deleteAudio && n.owned) Uri.parse(n.audio).path?.let { File(it).delete() }; repo.delete(n.id); application.retries.remove(n.id); PlaybackPositions(application).remove(n.id) } }
}
