package app.naz.whispernote.core

import android.content.Context

/** Device-local listening progress, separate from transcription checkpoints and note edits. */
class PlaybackPositions(context: Context) {
    private val preferences=context.applicationContext.getSharedPreferences("playback_positions",Context.MODE_PRIVATE)
    fun get(noteId: String): Long = preferences.getLong(noteId,0).coerceAtLeast(0)
    fun save(noteId: String, position: Long) { preferences.edit().putLong(noteId,position.coerceAtLeast(0)).apply() }
    fun remove(noteId: String) { preferences.edit().remove(noteId).apply() }
}
