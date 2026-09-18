package app.naz.whispernote.core

import android.app.*
import android.content.*
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.*
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import app.naz.whispernote.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import java.io.File

class ProcessingService : Service() {
    private data class Work(val id: String, val modelOnly: Boolean, val retryId: String? = null)
    // Queue ownership and service shutdown run on Main so a new start cannot race stopSelf.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val queue = Channel<Work>(Channel.UNLIMITED)
    private val pending = linkedSetOf<Work>()
    private val app get() = application as WhisperApp
    private val repo get() = app.repository
    private var worker: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var latestStartId = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "WhisperNote:processing")
            .apply { acquire(6 * 60 * 60 * 1000L) }
        if (Build.VERSION.SDK_INT >= 26) getSystemService(NotificationManager::class.java)
            .createNotificationChannel(NotificationChannel("processing", "Audio processing", NotificationManager.IMPORTANCE_LOW))
        worker = scope.launch {
            app.ready.await()
            for (work in queue) {
                try {
                    withContext(Dispatchers.IO) {
                        if (work.modelOnly) ModelManager(this@ProcessingService).download(work.id) {
                            ModelDownloads.progress.value = work.id to it
                            notifyProgress("${if (it == 100) "Verifying" else "Downloading"} ${ModelCatalog.get(work.id).displayName}", it, null)
                        } else if(work.retryId!=null) retrySegment(work.id,work.retryId) else process(work.id)
                    }
                } catch (e: Exception) {
                    withContext(NonCancellable + Dispatchers.IO) {
                        if (work.modelOnly) ModelDownloads.error.value = e.message ?: "Download failed"
                        else if(work.retryId!=null) app.retries.update(work.id,work.retryId) {it.copy(status="Failed",error=e.message ?: "Retranscription stopped. Original text is unchanged.")}
                        else repo.update(work.id) { note ->
                            if (note.status == "Completed") note
                            else note.copy(
                                status = if (e is CancellationException || note.checkpointMs > 0) "Interrupted" else "Failed",
                                error = if (e is CancellationException) "Processing stopped. Saved text and audio are safe." else e.message ?: "Processing failed"
                            )
                        }
                    }
                    if (e is CancellationException) throw e
                } finally {
                    if (work.modelOnly) ModelDownloads.progress.value = null
                    pending.remove(work)
                }
                if (pending.isEmpty()) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelfResult(latestStartId)
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val id = intent?.getStringExtra("id") ?: return START_NOT_STICKY
        latestStartId = startId
        val work = Work(id, intent.getBooleanExtra("modelOnly", false),intent.getStringExtra("retryId"))
        val type = if (Build.VERSION.SDK_INT >= 35)
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING or ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        else if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0
        ServiceCompat.startForeground(this, 1, notification("Preparing WhisperNote", -1, if (work.modelOnly) null else id), type)
        if (pending.add(work)) queue.trySend(work)
        return START_NOT_STICKY
    }

    private fun notification(label: String, progress: Int, id: String?): Notification {
        val intent = Intent(this, MainActivity::class.java).putExtra("noteId", id).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return NotificationCompat.Builder(this, "processing")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now).setContentTitle("WhisperNote").setContentText(label)
            .setContentIntent(PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            .setOnlyAlertOnce(true).setOngoing(true).setProgress(100, progress.coerceAtLeast(0), progress < 0).build()
    }

    private fun notifyProgress(label: String, progress: Int, id: String?) {
        getSystemService(NotificationManager::class.java).notify(1, notification(label, progress, id))
    }

    private fun state(id: String, status: String, progress: Int? = null) {
        repo.update(id) { it.copy(status = status, progress = progress ?: it.progress, error = null) }
        notifyProgress("$status · ${repo.get(id)?.title}", progress ?: -1, id)
    }

    private suspend fun process(id: String) {
        var note = repo.get(id) ?: return
        if (note.status == "Completed") return
        if (note.audio.isEmpty()) {
            state(id, "Downloading audio", 0)
            val file = File(filesDir, "audio").apply { mkdirs() }.resolve("$id.audio")
            Downloader.download(note.source ?: error("Missing audio source"), file) { state(id, "Downloading audio", it) }
            repo.update(id) { it.copy(audio = Uri.fromFile(file).toString(), owned = true) }
            note = repo.get(id)!!
        }
        val models = ModelManager(this)
        require(models.ready(note.model)) { "Download ${ModelCatalog.get(note.model).displayName} from Models, then resume. Your audio and saved text are safe." }
        val resumeMs = note.checkpointMs
        val pcm = File(cacheDir, "$id.pcm")
        try {
            state(id, "Preparing audio", if (note.duration > 0) (resumeMs * 100 / note.duration).toInt().coerceAtMost(99) else 0)
            val duration = AudioDecoder(this).decode(Uri.parse(note.audio), pcm)
            require(resumeMs == 0L || duration == note.duration) { "The source audio duration changed. Restore the original file to resume safely." }
            require(resumeMs <= duration) { "The checkpoint is beyond the end of the source audio." }
            repo.update(id) { it.copy(duration = duration) }
            val job = currentCoroutineContext().job
            if (resumeMs < duration) {
                state(id, "Loading model")
                val language = WhisperEngine().transcribe(models.file(note.model).path, pcm.path, resumeMs, note.language,
                    object : WhisperEngine.Listener {
                        override fun onProgress(progress: Int) {
                            job.ensureActive()
                            state(id, "Transcribing", progress.coerceIn(0, 99))
                        }
                        override fun onLanguage(language: String) {
                            job.ensureActive()
                            repo.update(id) { it.copy(language = language) }
                        }
                        override fun onSegment(start: Long, end: Long, text: String) {
                            job.ensureActive()
                            // One SQLite row write commits BOTH text and the resume cursor before returning to JNI.
                            // Read the latest note here so concurrent title/segment edits are retained.
                            repo.update(id) { it.appendFinalized(Segment(start, end, text.trim())) }
                        }
                        override fun isCancelled() = !job.isActive
                    })
                job.ensureActive()
                repo.update(id) { it.copy(language = language) }
            }
            job.ensureActive()
            repo.update(id) { it.copy(status = "Completed", progress = 100, checkpointMs = duration, error = null) }
        } finally { pcm.delete() }
    }

    private suspend fun retrySegment(noteId: String, retryId: String) {
        val draft=app.retries.get(noteId)?.takeIf {it.id==retryId && it.busy} ?: return
        val note=repo.get(noteId) ?: error("Note no longer exists.")
        require(!note.busy) {"Wait for the full transcription to finish before retrying a segment."}
        require(note.segments.any {it==draft.original}) {"The segment changed. Generate a new suggestion."}
        val models=ModelManager(this)
        require(models.ready(draft.model)) {"Download the selected model from Models first."}
        val pcm=File(cacheDir,"retry-$retryId.pcm")
        val job=currentCoroutineContext().job
        fun update(status: String, progress: Int=0) {
            job.ensureActive()
            check(app.retries.get(noteId)?.let {it.id==retryId && it.busy}==true) {"Retranscription cancelled."}
            app.retries.update(noteId,retryId) {it.copy(status=status,progress=progress)}
            notifyProgress("$status · ${note.title}",progress,noteId)
        }
        try {
            update("Preparing audio")
            AudioDecoder(this).decode(Uri.parse(note.audio),pcm,draft.original.start,draft.original.end)
            update("Loading model")
            val result=mutableListOf<Segment>()
            WhisperEngine().transcribe(models.file(draft.model).path,pcm.path,0,draft.language,object: WhisperEngine.Listener {
                override fun onProgress(progress: Int) {update("Transcribing",progress)}
                override fun onSegment(start: Long,end: Long,text: String) {
                    job.ensureActive()
                    val from=(draft.original.start+start).coerceAtLeast(draft.original.start)
                    val to=(draft.original.start+end).coerceAtMost(draft.original.end)
                    if(to>from) result.add(Segment(from,to,text.trim()))
                }
                override fun isCancelled() = !job.isActive || app.retries.get(noteId)?.let {it.id!=retryId || !it.busy}!=false
            })
            job.ensureActive()
            app.retries.update(noteId,retryId) {it.copy(status="Ready",progress=100,result=result.toList())}
        } finally {pcm.delete()}
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        scope.cancel("Android stopped this long-running job")
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        val retryWork=pending.filter {it.retryId!=null}.toList()
        val outstanding = pending.filter { !it.modelOnly && it.retryId==null }.map { it.id }
        scope.cancel()
        // A normal service shutdown also preserves queued jobs as retryable. Abrupt process death is
        // recovered by WhisperApp on the next launch, before new work may start.
        app.scope.launch {
            worker?.join()
            app.ready.await()
            retryWork.forEach { work -> app.retries.update(work.id,work.retryId!!) {if(it.busy) it.copy(status="Failed",error="Processing stopped. Your original text is unchanged.") else it} }
            outstanding.forEach { id -> repo.update(id) { if (it.busy) it.interrupted("Processing stopped. Resume to continue from the saved text.") else it } }
        }
        wakeLock?.let { if (it.isHeld) it.release() }
        super.onDestroy()
    }

    companion object {
        fun start(context: Context, id: String, modelOnly: Boolean = false, retryId: String? = null) {
            androidx.core.content.ContextCompat.startForegroundService(context,
                Intent(context, ProcessingService::class.java).putExtra("id", id).putExtra("modelOnly", modelOnly).putExtra("retryId",retryId))
        }
    }
}

object ModelDownloads {
    val progress = kotlinx.coroutines.flow.MutableStateFlow<Pair<String, Int>?>(null)
    val error = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)
}
