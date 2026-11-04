package app.naz.whispernote.core

import android.app.*
import android.content.*
import android.content.pm.ServiceInfo
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import app.naz.whispernote.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.File

/** Recording owns its foreground service independently of transcription and playback. */
class RecordingService : Service() {
    private var recorder: MediaRecorder?=null
    private var file: File?=null
    private var model="tiny-q5_1"
    private var label: String?=null
    private var paused=false
    override fun onBind(intent: Intent?): IBinder?=null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            when(intent?.action) {
                "stop" -> finish()
                "pause" -> {recorder?.pause(); paused=true; state.value="Paused"; notifyRecording()}
                "resume" -> {recorder?.resume(); paused=false; state.value="Recording"; notifyRecording()}
                else -> if(recorder==null) {
                    model=intent?.getStringExtra("model") ?: model; label=intent?.getStringExtra("label")
                    if(Build.VERSION.SDK_INT>=26) getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel("recording","Recording",NotificationManager.IMPORTANCE_LOW))
                    state.value="Recording"; notifyRecording()
                    val audio=File(filesDir,"audio").apply {mkdirs()}.resolve("recording-${java.util.UUID.randomUUID()}.m4a")
                    file=audio
                    @Suppress("DEPRECATION")
                    val r=if(Build.VERSION.SDK_INT>=31) MediaRecorder(this) else MediaRecorder()
                    recorder=r
                    r.setAudioSource(MediaRecorder.AudioSource.MIC)
                    r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                    r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                    r.setAudioSamplingRate(44100); r.setAudioEncodingBitRate(128000)
                    r.setOutputFile(audio.path); r.prepare(); r.start()
                }
            }
        } catch(e: Exception) {
            error.value=e.message ?: "Recording failed."
            recorder?.release(); recorder=null
            state.value=null; stopForeground(STOP_FOREGROUND_REMOVE); stopSelf()
        }
        return START_NOT_STICKY
    }
    private fun notifyRecording() {
        val open=PendingIntent.getActivity(this,0,Intent(this,MainActivity::class.java),PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        fun action(name: String)=PendingIntent.getService(this,name.hashCode(),Intent(this,RecordingService::class.java).setAction(name),PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notification=NotificationCompat.Builder(this,"recording").setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("WhisperNote · ${state.value}").setContentText("Tap Stop to save and transcribe")
            .setContentIntent(open).setOngoing(true)
            .addAction(0,if(paused) "Resume" else "Pause",action(if(paused) "resume" else "pause"))
            .addAction(0,"Stop and save",action("stop")).build()
        ServiceCompat.startForeground(this,3,notification,if(Build.VERSION.SDK_INT>=30) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0)
    }
    private fun finish() {
        val r=recorder ?: return
        r.stop(); r.release(); recorder=null
        val audio=requireNotNull(file)
        val app=application as WhisperApp
        app.scope.launch {
            try {
                app.ready.await()
                val note=Note(title="Recording ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm",java.util.Locale.getDefault()).format(java.util.Date())}",audio=Uri.fromFile(audio).toString(),owned=true,model=model,labels=listOfNotNull(label).filter {it in app.repository.labels.value}.toSet(),status="Paused")
                app.repository.put(note)
                withContext(Dispatchers.Main) {runCatching {ProcessingService.start(this@RecordingService,note.id)}.onFailure {error.value="Audio saved. Open the note to start transcription."}}
            } catch(e: Exception) {error.value="Recording retained at ${audio.name}: ${e.message}"}
        }
        state.value=null; stopForeground(STOP_FOREGROUND_REMOVE); stopSelf()
    }
    override fun onDestroy() {
        if(recorder!=null) runCatching {finish()}.onFailure {recorder?.release(); recorder=null; state.value=null; error.value="Recording interrupted."}
        super.onDestroy()
    }
    companion object {
        val state=MutableStateFlow<String?>(null)
        val error=MutableStateFlow<String?>(null)
        fun start(context: Context, model: String, label: String?) {
            androidx.core.content.ContextCompat.startForegroundService(context,Intent(context,RecordingService::class.java).putExtra("model",model).putExtra("label",label))
        }
        fun command(context: Context, action: String) {context.startService(Intent(context,RecordingService::class.java).setAction(action))}
    }
}
