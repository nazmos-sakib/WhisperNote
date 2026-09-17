package app.naz.whispernote.core
import android.content.Context
import android.media.MediaPlayer
import android.net.Uri
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow

data class Playback(val ready: Boolean=false,val playing: Boolean=false,val position: Long=0,val duration: Long=0,val error: String?=null)
class AudioPlayer(context: Context,uri: String) {
    val state=MutableStateFlow(Playback())
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
    private val player=MediaPlayer()
    init {
        try {
            player.setDataSource(context,Uri.parse(uri))
            player.setOnPreparedListener { state.value=Playback(ready=true,duration=it.duration.toLong()) }
            player.setOnCompletionListener { state.value=state.value.copy(playing=false,position=state.value.duration) }
            player.setOnErrorListener { _,_,_ -> state.value=state.value.copy(error="Cannot play this audio.",ready=false); true }
            player.prepareAsync()
            scope.launch { while(isActive) { if(state.value.ready) state.value=state.value.copy(position=player.currentPosition.toLong(),playing=player.isPlaying); delay(250) } }
        } catch(e: Exception) { state.value=Playback(error=e.message ?: "Audio unavailable") }
    }
    fun toggle() { if(state.value.ready) { if(player.isPlaying) player.pause() else player.start(); state.value=state.value.copy(playing=player.isPlaying) } }
    fun seek(ms: Long,play: Boolean=false) { if(state.value.ready) { val target=ms.coerceIn(0,state.value.duration); player.seekTo(target.toInt()); if(play) player.start(); state.value=state.value.copy(position=target,playing=player.isPlaying) } }
    fun release() { scope.cancel(); player.release() }
}
