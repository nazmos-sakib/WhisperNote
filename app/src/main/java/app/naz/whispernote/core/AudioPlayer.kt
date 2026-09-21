package app.naz.whispernote.core
import android.content.Context
import android.media.MediaPlayer
import android.net.Uri
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow

data class Playback(val ready: Boolean=false,val playing: Boolean=false,val position: Long=0,val duration: Long=0,val error: String?=null,val speed: Float=1f)
class AudioPlayer(
    context: Context,
    uri: String,
    initialPosition: Long = 0,
    private val savePosition: (Long) -> Unit = {}
) {
    val state=MutableStateFlow(Playback())
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
    private val player=MediaPlayer()
    private var prepared=false
    private var seeking=false
    private var released=false
    private var lastSavedPosition: Long?=null
    private var ticksSinceSave=0
    init {
        try {
            player.setAudioAttributes(android.media.AudioAttributes.Builder().setUsage(android.media.AudioAttributes.USAGE_MEDIA).setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH).build())
            player.setWakeMode(context.applicationContext, android.os.PowerManager.PARTIAL_WAKE_LOCK)
            player.setDataSource(context,Uri.parse(uri))
            player.setOnSeekCompleteListener {
                seeking=false
                state.value=state.value.copy(ready=true,position=it.currentPosition.toLong())
                checkpoint()
            }
            player.setOnPreparedListener {
                prepared=true
                val duration=it.duration.toLong().coerceAtLeast(0)
                val restored=initialPosition.coerceIn(0,duration)
                state.value=Playback(ready=restored==0L,position=restored,duration=duration)
                if(restored>0) { seeking=true; it.seekTo(restored.toInt()) }
            }
            player.setOnCompletionListener {
                state.value=state.value.copy(playing=false,position=state.value.duration)
                checkpoint()
            }
            player.setOnErrorListener { _,_,_ ->
                state.value=state.value.copy(error="Cannot play this audio.",ready=false,playing=false)
                prepared=false
                true
            }
            player.prepareAsync()
            scope.launch {
                while(isActive) {
                    if(state.value.ready && !seeking) {
                        state.value=state.value.copy(position=player.currentPosition.toLong(),playing=player.isPlaying)
                        if(++ticksSinceSave>=20) checkpoint() // Every five seconds during listening.
                    }
                    delay(250)
                }
            }
        } catch(e: Exception) { state.value=Playback(error=e.message ?: "Audio unavailable") }
    }
    private fun checkpoint() {
        if(!prepared || released) return
        val position=state.value.position
        if(lastSavedPosition!=position) { savePosition(position); lastSavedPosition=position }
        ticksSinceSave=0
    }
    fun setSpeed(speed: Float) {
        if(!state.value.ready || speed !in listOf(0.5f,0.75f,1f,1.25f,1.5f,2f)) return
        val wasPlaying=player.isPlaying
        try {
            // Setting nonzero playback parameters can start a paused MediaPlayer.
            player.playbackParams=android.media.PlaybackParams().setPitch(1f).setSpeed(speed)
            if(!wasPlaying) player.pause()
            state.value=state.value.copy(speed=speed,playing=wasPlaying,error=null)
        } catch(e: Exception) {
            if(!wasPlaying && player.isPlaying) player.pause()
            state.value=state.value.copy(playing=player.isPlaying,error="Cannot change playback speed for this audio.")
        }
    }
    fun play() {
        if (!state.value.ready || released) return
        try {
            if (state.value.position >= state.value.duration && state.value.duration > 0) seek(0)
            player.start()
            state.value = state.value.copy(playing=true, error=null)
        } catch (e: Exception) { state.value=state.value.copy(playing=false, error="Cannot play this audio.") }
    }
    fun pause() {
        if (!prepared || released) return
        if (player.isPlaying) player.pause()
        state.value=state.value.copy(playing=false,position=if(seeking) state.value.position else player.currentPosition.toLong())
        checkpoint()
    }
    fun toggle() {
        if(state.value.ready) {
            if(player.isPlaying) player.pause() else player.start()
            state.value=state.value.copy(playing=player.isPlaying,position=if(seeking) state.value.position else player.currentPosition.toLong())
            checkpoint()
        }
    }
    fun seek(ms: Long,play: Boolean=false) {
        if(state.value.ready) {
            val target=ms.coerceIn(0,state.value.duration)
            seeking=true
            player.seekTo(target.toInt())
            if(play) player.start()
            state.value=state.value.copy(position=target,playing=player.isPlaying)
            checkpoint()
        }
    }
    fun release() {
        if(released) return
        if(prepared && !seeking) state.value=state.value.copy(position=player.currentPosition.toLong())
        checkpoint()
        released=true;scope.cancel();player.release()
    }
}
