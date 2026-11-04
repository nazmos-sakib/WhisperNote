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
    private val signal = Channel<Unit>(Channel.CONFLATED)
    private val queue = ArrayDeque<Work>()
    @Volatile private var paused = false
    private var active: Work? = null
    @Volatile private var resumeAfterSwitch = false
    @Volatile private var waiting = false
    private val prepared = mutableMapOf<String, Pair<String,Long>>()
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
            for (tick in signal) {
              while (queue.isNotEmpty()) {
                val work = queue.removeFirst()
                waiting = queue.isNotEmpty()
                active = work
                paused = false
                var ownsModel = false
                try {
                    if (!work.modelOnly) {
                        // Existing full-file work may wait for a standalone model load or unload.
                        while (!app.transcription.tryBegin(if(work.retryId!=null) "Preparing re-transcription…" else "Preparing transcription…")) delay(100)
                        ownsModel = true
                    }
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
                                status = if (paused) "Paused" else if (e is CancellationException || note.checkpointMs > 0) "Interrupted" else "Failed",
                                error = if (e is CancellationException) "Processing stopped. Saved text and audio are safe." else e.message ?: "Processing failed"
                            )
                        }
                    }
                    if (e is CancellationException) throw e
                } finally {
                    if (ownsModel) app.transcription.finish()
                    if (work.modelOnly) ModelDownloads.progress.value = null
                    pending.remove(work)
                    active = null
                    ProcessingQueue.active.value = null
                    if (paused && resumeAfterSwitch && repo.get(work.id)?.status != "Completed") {
                        queue.addLast(work); pending.add(work)
                        repo.update(work.id) {it.copy(status="Queued",error=null)}
                    }
                    resumeAfterSwitch = false
                }
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
        val type = if (Build.VERSION.SDK_INT >= 35)
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING or ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        else if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0
        ServiceCompat.startForeground(this, 1, notification("Preparing WhisperNote", -1, id), type)
        val command = intent.getStringExtra("command")
        if (command == "pause") {
            if (active?.id == id) {resumeAfterSwitch=false; paused = true}
            else {
                queue.removeAll { it.id == id }; pending.removeAll { it.id == id }
                scope.launch(Dispatchers.IO) { repo.update(id) { it.copy(status="Paused",error=null) } }
            }
            signal.trySend(Unit)
            return START_NOT_STICKY
        }
        val work = Work(id, intent.getBooleanExtra("modelOnly", false),intent.getStringExtra("retryId"))
        if(work.modelOnly) {
            if(pending.add(work)) scope.launch {
                try {
                    withContext(Dispatchers.IO) {
                        val manager=ModelManager(this@ProcessingService)
                        val progress: (Int)->Unit = {ModelDownloads.progress.value=id to it}
                        if(id=="vad") manager.downloadVad(progress) else manager.download(id,progress)
                    }
                } catch(e: Exception) {ModelDownloads.error.value=e.message}
                finally {
                    ModelDownloads.progress.value=null; pending.remove(work)
                    if(pending.isEmpty()) {stopForeground(STOP_FOREGROUND_REMOVE);stopSelfResult(latestStartId)}
                }
            }
            return START_NOT_STICKY
        }
        if(work.retryId==null && active!=work) repo.progress(id,"Queued",null)
        if (command == "next" && active != work) {
            queue.remove(work)
            queue.addFirst(work); pending.add(work)
            if (active != null && active?.modelOnly == false && active?.retryId == null) {
                resumeAfterSwitch = true; paused = true
            }
        } else if (pending.add(work)) queue.addLast(work)
        waiting = queue.isNotEmpty()
        signal.trySend(Unit)
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
        repo.progress(id,status,progress)
        notifyProgress("$status · ${repo.get(id)?.title}", progress ?: -1, id)
    }

    private suspend fun process(id: String) {
        var note = repo.get(id) ?: return
        if (note.status == "Completed") return
        if (note.audio.isEmpty()) {
            state(id, "Downloading audio", 0)
            val file = File(filesDir, "audio").apply { mkdirs() }.resolve("$id.audio")
            Downloader.download(note.source ?: error("Missing audio source"), file) { check(!paused) {"Paused"}; state(id, "Downloading audio", it) }
            repo.update(id) { it.copy(audio = Uri.fromFile(file).toString(), owned = true) }
            note = repo.get(id)!!
        }
        val models = ModelManager(this)
        require(models.ready(note.model)) { "Download ${ModelCatalog.get(note.model).displayName} from Models, then resume. Your audio and saved text are safe." }
        val vadModel = if(models.skipSilence && models.vadReady) models.vadFile.path else ""
        val resumeMs = note.checkpointMs
        val started = SystemClock.elapsedRealtime()
        val pcm = File(cacheDir, "$id.pcm")
        try {
            state(id, "Preparing audio", if (note.duration > 0) (resumeMs * 100 / note.duration).toInt().coerceAtMost(99) else 0)
            val cached=prepared[id]?.takeIf {it.first==note.audio && pcm.isFile && pcm.length()>0}
            val duration = cached?.second ?: AudioDecoder(this).decode(Uri.parse(note.audio), pcm, cancelled={paused}).also {prepared[id]=note.audio to it}
            check(!paused) {"Paused"}
            require(resumeMs == 0L || duration == note.duration) { "The source audio duration changed. Restore the original file to resume safely." }
            require(resumeMs <= duration) { "The checkpoint is beyond the end of the source audio." }
            repo.update(id) { it.copy(duration = duration) }
            val job = currentCoroutineContext().job
            if (resumeMs < duration) {
                state(id, "Loading model")
                app.transcription.ensureLoaded(note.model)
                app.transcription.status("Transcribing…")
                val language = WhisperEngine().transcribe(models.file(note.model).path, pcm.path, resumeMs, note.language,
                    object : WhisperEngine.Listener {
                        override fun onProgress(progress: Int) {
                            job.ensureActive()
                            check(!paused) { "Paused. Your audio and saved text are safe." }
                            val elapsed = SystemClock.elapsedRealtime() - started
                            val processed = ((repo.get(id)?.checkpointMs ?: resumeMs) - resumeMs).coerceAtLeast(0)
                            val remaining = if (processed > 0 && elapsed > 10000) elapsed * (duration - resumeMs - processed).coerceAtLeast(0) / processed else null
                            ProcessingQueue.active.value = ProcessingEstimate(id, remaining)
                            val overall=if(vadModel.isNotEmpty()) (((resumeMs+processed)*100/duration).toInt()).coerceAtMost(99) else progress.coerceIn(0,99)
                            if (repo.get(id)?.progress != overall || repo.get(id)?.status != "Transcribing")
                                state(id, "Transcribing", overall)
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
                            // Yield only after a finalized segment is committed. Never cut a word at an arbitrary time.
                            if(waiting && end-resumeMs>=5*60*1000 && end<duration && !paused) {
                                resumeAfterSwitch=true; paused=true
                            }
                        }
                        override fun isCancelled() = !job.isActive || paused
                    }, vadModel)
                job.ensureActive()
                repo.update(id) { it.copy(language = language) }
            }
            job.ensureActive()
            repo.update(id) { it.copy(status = "Completed", progress = 100, checkpointMs = duration, error = null) }
        } finally { if(!paused) {pcm.delete();prepared.remove(id)} }
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
            app.transcription.status(if(status=="Transcribing") "Re-transcribing…" else "$status…")
            notifyProgress("$status · ${note.title}",-1,noteId)
        }
        try {
            update("Preparing audio")
            AudioDecoder(this).decode(Uri.parse(note.audio),pcm,draft.original.start,draft.original.end)
            update("Loading model")
            app.transcription.ensureLoaded(draft.model)
            app.transcription.status("Re-transcribing…")
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
            if (!app.transcription.foreground && app.retries.get(noteId)?.let {it.id==retryId && it.status=="Ready"}==true) {
                val intent=Intent(this,MainActivity::class.java).putExtra("noteId",noteId)
                    .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
                val notification=NotificationCompat.Builder(this,"processing")
                    .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                    .setContentTitle("Re-transcription completed")
                    .setContentText("Tap to review the new transcription")
                    .setAutoCancel(true)
                    .setContentIntent(PendingIntent.getActivity(this,noteId.hashCode(),intent,PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
                    .build()
                // Notification permission may be denied; the durable suggestion must stay Ready.
                runCatching { getSystemService(NotificationManager::class.java).notify(noteId.hashCode(),notification) }
            }
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
            prepared.keys.forEach {File(cacheDir,"$it.pcm").delete()}; prepared.clear()
            app.ready.await()
            retryWork.forEach { work -> app.retries.update(work.id,work.retryId!!) {if(it.busy) it.copy(status="Failed",error="Processing stopped. Your original text is unchanged.") else it} }
            outstanding.forEach { id -> repo.update(id) { if (it.busy) it.interrupted("Processing stopped. Resume to continue from the saved text.") else it } }
        }
        wakeLock?.let { if (it.isHeld) it.release() }
        super.onDestroy()
    }

    companion object {
        fun control(context: Context, id: String, command: String) {
            androidx.core.content.ContextCompat.startForegroundService(context,
                Intent(context, ProcessingService::class.java).putExtra("id", id).putExtra("command", command))
        }
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

data class ProcessingEstimate(val id: String, val remainingMs: Long?)
object ProcessingQueue { val active = kotlinx.coroutines.flow.MutableStateFlow<ProcessingEstimate?>(null) }
