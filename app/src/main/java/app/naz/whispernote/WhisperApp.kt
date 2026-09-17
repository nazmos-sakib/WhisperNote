package app.naz.whispernote

import android.app.Application
import app.naz.whispernote.core.NoteRepository
import kotlinx.coroutines.*

class WhisperApp : Application() {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val repository by lazy { NoteRepository(this) }
    val ready = CompletableDeferred<Unit>()

    override fun onCreate() {
        super.onCreate()
        scope.launch {
            try {
                repository.load()
                repository.recoverInterrupted()
                ready.complete(Unit)
            } catch (e: Exception) { ready.completeExceptionally(e) }
        }
    }
}
