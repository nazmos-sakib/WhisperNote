package app.naz.whispernote.core

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class Segment(val start: Long, val end: Long, val text: String, val id: String = UUID.randomUUID().toString())
data class Note(
    val id: String = UUID.randomUUID().toString(), val title: String,
    val audio: String = "", val owned: Boolean = false, val source: String? = null,
    val duration: Long = 0, val created: Long = System.currentTimeMillis(),
    val modified: Long = created, val language: String = "", val model: String = "tiny",
    val status: String = "Queued", val progress: Int = 0, val error: String? = null,
    val segments: List<Segment> = emptyList(),
    // Committed with finalized segments, never advanced from an estimated progress percentage.
    val checkpointMs: Long = 0,
    val label: String? = null
) {
    val busy get() = status !in listOf("Completed", "Failed", "Interrupted")
    val resumable get() = status == "Failed" || status == "Interrupted"
    fun appendFinalized(segment: Segment): Note {
        require(segment.start >= checkpointMs && segment.end > segment.start)
        require(duration > 0 && segment.end <= duration)
        return copy(segments = segments + segment, checkpointMs = segment.end)
    }
    fun editSegment(segmentId: String, text: String) = copy(
        segments = segments.map { if (it.id == segmentId) it.copy(text = text) else it }
    )
    // The checkpoint records processed audio, not the last remaining visible segment.
    fun deleteSegment(segmentId: String) = copy(segments = segments.filterNot { it.id == segmentId })
    fun interrupted(reason: String) = copy(status = "Interrupted", error = reason)
}

/** A transactional SQLite document repository; audio bytes never enter the database. */
class NoteRepository(context: Context, databaseName: String = "notes.db") : SQLiteOpenHelper(context, databaseName, null, 1) {
    private val labelState = MutableStateFlow<List<String>>(emptyList())
    val labels = labelState.asStateFlow()
    private val state = MutableStateFlow<List<Note>>(emptyList())
    val notes = state.asStateFlow()
    override fun onCreate(db: SQLiteDatabase) { db.execSQL("CREATE TABLE notes (id TEXT PRIMARY KEY, document TEXT NOT NULL)") }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
    @Synchronized fun load() {
        writableDatabase.execSQL("CREATE TABLE IF NOT EXISTS labels (name TEXT PRIMARY KEY COLLATE NOCASE)")
        labelState.value = readableDatabase.rawQuery("SELECT name FROM labels ORDER BY name COLLATE NOCASE", null).use { c -> buildList { while(c.moveToNext()) add(c.getString(0)) } }
        state.value = readableDatabase.rawQuery("SELECT document FROM notes", null).use { c ->
            buildList { while (c.moveToNext()) add(decode(JSONObject(c.getString(0)))) }.sortedByDescending { it.created }
        }
    }
    @Synchronized fun recoverInterrupted() {
        state.value.filter { it.busy }.forEach { note ->
            update(note.id) { it.interrupted("Processing stopped before completion. Saved text and audio are safe.") }
        }
    }
    @Synchronized fun createLabel(raw: String): String {
        val name = raw.trim()
        require(name.isNotEmpty() && name.length <= 60) { "Use a label name between 1 and 60 characters." }
        val existing = labelState.value.firstOrNull { it.equals(name, true) }
        if (existing != null) return existing
        writableDatabase.execSQL("INSERT INTO labels(name) VALUES (?)", arrayOf(name)); load()
        return name
    }
    @Synchronized fun renameLabel(old: String, raw: String) {
        val name = raw.trim()
        require(name.isNotEmpty() && name.length <= 60) { "Use a label name between 1 and 60 characters." }
        require(labelState.value.none { it != old && it.equals(name, true) }) { "That label already exists." }
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.execSQL("UPDATE labels SET name=? WHERE name=?", arrayOf(name, old))
            state.value.filter { it.label == old }.forEach { n -> db.execSQL("UPDATE notes SET document=? WHERE id=?", arrayOf(encode(n.copy(label=name)).toString(),n.id)) }
            db.setTransactionSuccessful()
        } finally { db.endTransaction(); load() }
    }
    @Synchronized fun deleteLabel(name: String) {
        val db=writableDatabase
        db.beginTransaction()
        try {
            db.delete("labels","name=?",arrayOf(name))
            state.value.filter { it.label==name }.forEach { n -> db.execSQL("UPDATE notes SET document=? WHERE id=?",arrayOf(encode(n.copy(label=null)).toString(),n.id)) }
            db.setTransactionSuccessful()
        } finally { db.endTransaction(); load() }
    }
    @Synchronized fun assignLabel(id: String, label: String?) {
        require(label == null || label in labelState.value) { "This label no longer exists." }
        update(id) { it.copy(label=label) }
    }
    @Synchronized fun get(id: String) = state.value.firstOrNull { it.id == id }
    @Synchronized fun put(note: Note) {
        writableDatabase.execSQL("INSERT OR REPLACE INTO notes VALUES (?, ?)", arrayOf(note.id, encode(note).toString()))
        load()
    }
    @Synchronized fun update(id: String, change: (Note) -> Note) { get(id)?.let { put(change(it).copy(modified = System.currentTimeMillis())) } }
    @Synchronized fun delete(id: String) { writableDatabase.delete("notes", "id=?", arrayOf(id)); load() }
    private fun encode(n: Note) = JSONObject().apply {
        put("label",n.label); put("id",n.id); put("title",n.title); put("audio",n.audio); put("owned",n.owned); put("source",n.source)
        put("duration",n.duration); put("created",n.created); put("modified",n.modified); put("language",n.language)
        put("model",n.model); put("status",n.status); put("progress",n.progress); put("error",n.error); put("checkpointMs",n.checkpointMs)
        put("segments", JSONArray().apply { n.segments.forEach { put(JSONObject().put("start",it.start).put("end",it.end).put("text",it.text).put("id",it.id)) } })
    }
    private fun decode(j: JSONObject) = Note(j.getString("id"),j.getString("title"),j.getString("audio"),j.getBoolean("owned"),j.optString("source").takeIf { it.isNotEmpty() && it != "null" },j.getLong("duration"),j.getLong("created"),j.getLong("modified"),j.getString("language"),j.getString("model"),j.getString("status"),j.getInt("progress"),j.optString("error").takeIf { it.isNotEmpty() && it != "null" },j.getJSONArray("segments").let { a -> List(a.length()) { i -> a.getJSONObject(i).let { Segment(it.getLong("start"),it.getLong("end"),it.getString("text"),it.optString("id").ifBlank { "legacy-$i" }) } } },j.optLong("checkpointMs",0),if(j.isNull("label")) null else j.getString("label").takeIf { it.isNotBlank() })
}

fun timestamp(ms: Long, srt: Boolean = false): String {
    val t = ms.coerceAtLeast(0); val seconds = t / 1000
    return if (srt) String.format(java.util.Locale.ROOT,"%02d:%02d:%02d,%03d",seconds/3600,seconds/60%60,seconds%60,t%1000)
    else if (seconds >= 3600) String.format(java.util.Locale.ROOT,"%02d:%02d:%02d",seconds/3600,seconds/60%60,seconds%60)
    else String.format(java.util.Locale.ROOT,"%02d:%02d",seconds/60,seconds%60)
}
object Exporter {
    fun render(n: Note, format: String): String = when(format) {
        "srt" -> n.segments.mapIndexed { i,s -> "${i+1}\n${timestamp(s.start,true)} --> ${timestamp(s.end,true)}\n${s.text.trim()}\n" }.joinToString("\n")
        "md" -> "# ${n.title}\n\n" + n.segments.joinToString("\n\n") { "**${timestamp(it.start)}**\n\n${it.text}" }
        else -> n.title + "\n\n" + n.segments.joinToString("\n\n") { "${timestamp(it.start)}\n${it.text}" }
    }
}
