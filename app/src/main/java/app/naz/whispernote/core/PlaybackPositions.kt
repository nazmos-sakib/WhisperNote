package app.naz.whispernote.core

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow

enum class ListeningState { Unplayed, InProgress, Completed }
data class ListeningProgress(val position: Long = 0, val completed: Boolean = false) {
    val state get() = if(completed) ListeningState.Completed else if(position>0) ListeningState.InProgress else ListeningState.Unplayed
}

/** Device-local progress uses the existing preferences, separate from transcription status. */
class PlaybackPositions(context: Context) {
    private val preferences=context.applicationContext.getSharedPreferences("playback_positions",Context.MODE_PRIVATE)
    companion object { val changes=MutableStateFlow(0L) }
    fun get(noteId: String): Long = preferences.getLong(noteId,0).coerceAtLeast(0)
    fun progress(noteId: String)=ListeningProgress(get(noteId),preferences.getBoolean("completed:$noteId",false))
    fun save(noteId: String, position: Long) { preferences.edit().putLong(noteId,position.coerceAtLeast(0)).apply(); changes.value++ }
    fun markCompleted(noteId: String, completed: Boolean) { preferences.edit().putBoolean("completed:$noteId",completed).apply(); changes.value++ }
    fun remove(noteId: String) { preferences.edit().remove(noteId).remove("completed:$noteId").apply(); changes.value++ }
}
