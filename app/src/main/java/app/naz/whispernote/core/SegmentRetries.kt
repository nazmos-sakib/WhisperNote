package app.naz.whispernote.core

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class SegmentRetry(
    val noteId: String, val original: Segment, val model: String, val language: String,
    val id: String=UUID.randomUUID().toString(), val status: String="Queued",
    val progress: Int=0, val result: List<Segment> = emptyList(), val error: String?=null
) { val busy get()=status !in listOf("Ready","Failed") }

/** Durable draft suggestions; never replace the source transcript until explicitly accepted. */
class SegmentRetries(context: Context, name: String="segment_retries") {
    private val prefs=context.getSharedPreferences(name,Context.MODE_PRIVATE)
    private val state=MutableStateFlow<Map<String,SegmentRetry>>(emptyMap())
    val drafts=state.asStateFlow()
    private fun segment(s: Segment)=JSONObject().put("id",s.id).put("start",s.start).put("end",s.end).put("text",s.text)
    private fun segment(j: JSONObject)=Segment(j.getLong("start"),j.getLong("end"),j.getString("text"),j.getString("id"))
    @Synchronized fun load() {
        state.value=prefs.all.mapNotNull { (key,value) -> runCatching {
            val j=JSONObject(value as String)
            key to SegmentRetry(key,segment(j.getJSONObject("original")),j.getString("model"),j.getString("language"),j.getString("id"),j.getString("status"),j.getInt("progress"),
                j.getJSONArray("result").let { a -> List(a.length()) { segment(a.getJSONObject(it)) } },if(j.isNull("error")) null else j.getString("error"))
        }.getOrNull() }.toMap()
        state.value.values.filter {it.busy}.forEach {put(it.copy(status="Failed",error="Retranscription was interrupted. Your original text is unchanged. Try again."))}
    }
    @Synchronized fun get(noteId: String)=state.value[noteId]
    @Synchronized fun put(draft: SegmentRetry) {
        val j=JSONObject().put("original",segment(draft.original)).put("model",draft.model).put("language",draft.language).put("id",draft.id)
            .put("status",draft.status).put("progress",draft.progress).put("error",draft.error)
            .put("result",JSONArray().apply { draft.result.forEach {put(segment(it))} })
        check(prefs.edit().putString(draft.noteId,j.toString()).commit()) {"Cannot save retranscription draft."}
        state.value=state.value+(draft.noteId to draft)
    }
    @Synchronized fun update(noteId: String, id: String, change: (SegmentRetry)->SegmentRetry) {
        get(noteId)?.takeIf {it.id==id}?.let {put(change(it))}
    }
    @Synchronized fun remove(noteId: String) {check(prefs.edit().remove(noteId).commit());state.value=state.value-noteId}
}

/** Verify the source has not been edited while inference ran. Keep all unprocessed ranges visible. */
fun Note.acceptRetry(draft: SegmentRetry, useNewSegments: Boolean): Note {
    require(id==draft.noteId && draft.status=="Ready") {"This suggestion is not ready."}
    val index=segments.indexOfFirst {it.id==draft.original.id}
    require(index>=0 && segments[index]==draft.original) {"This segment changed. Keep your edits or generate a new suggestion."}
    val source=segments[index]
    require(draft.result.all {it.start>=source.start && it.end<=source.end && it.end>it.start}) {"Invalid suggestion timestamps."}
    require(draft.result.zipWithNext().all {(a,b)->a.end<=b.start}) {"Overlapping suggestion timestamps."}
    val replacements=if(!useNewSegments || draft.result.isEmpty()) listOf(source.copy(text=draft.result.joinToString(" ") {it.text}.trim())) else buildList {
        var cursor=source.start
        draft.result.forEach { s ->
            if(s.start>cursor) add(Segment(cursor,s.start,""))
            add(s);cursor=s.end
        }
        if(cursor<source.end) add(Segment(cursor,source.end,""))
    }
    return copy(segments=segments.take(index)+replacements+segments.drop(index+1))
}
