package app.naz.whispernote.core

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class Bookmark(val position: Long, val comment: String, val id: String = UUID.randomUUID().toString())
data class SavedTranslation(val segmentId: String, val source: String, val target: String, val text: String)

internal fun extrasJson(note: Note) = JSONObject().apply {
    put("bookmarks", JSONArray().apply { note.bookmarks.forEach { put(JSONObject().put("id",it.id).put("position",it.position).put("comment",it.comment)) } })
    put("translations", JSONArray().apply { note.translations.forEach { put(JSONObject().put("segmentId",it.segmentId).put("source",it.source).put("target",it.target).put("text",it.text)) } })
}
internal fun readBookmarks(j: JSONObject, duration: Long): List<Bookmark> = j.optJSONArray("bookmarks")?.let { a -> List(a.length()) { i ->
    val b=a.getJSONObject(i); val position=b.getLong("position")
    require(position in 0..duration) { "Invalid bookmark timestamp." }
    Bookmark(position,b.getString("comment"),b.optString("id").ifBlank { UUID.randomUUID().toString() })
} } ?: emptyList()
internal fun readTranslations(j: JSONObject): List<SavedTranslation> = j.optJSONArray("translations")?.let { a -> List(a.length()) { i ->
    val t=a.getJSONObject(i); SavedTranslation(t.getString("segmentId"),t.getString("source"),t.getString("target"),t.getString("text"))
} } ?: emptyList()
