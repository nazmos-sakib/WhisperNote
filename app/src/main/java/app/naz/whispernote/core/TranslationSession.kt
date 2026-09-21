package app.naz.whispernote.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class TranslationPair(val source: String, val target: String) {
    val id get() = "$source-$target"
    val label get() = "${TranslationCatalog.name(source)} → ${TranslationCatalog.name(target)}"
    val packs get() = setOf(source, target) - "en"
}

object TranslationCatalog {
    val pairs = listOf("de", "fr", "es", "it", "pt", "nl", "tr", "uk").map { TranslationPair(it, "en") } +
        listOf(TranslationPair("de", "bn"), TranslationPair("de", "hi"))
    private val names = mapOf("de" to "German", "en" to "English", "fr" to "French", "es" to "Spanish",
        "it" to "Italian", "pt" to "Portuguese", "nl" to "Dutch", "tr" to "Turkish", "uk" to "Ukrainian",
        "bn" to "Bengali / Bangla", "hi" to "Hindi")
    fun name(code: String) = names[code] ?: code
}

/** Implementations deliver callbacks on the same (UI) thread as the caller. */
interface SegmentTranslator : AutoCloseable {
    fun translate(text: String, complete: (Result<String>) -> Unit)
}

data class SegmentTranslation(
    val source: String, val target: String, val pending: Boolean = true,
    val text: String? = null, val error: String? = null, val expanded: Boolean = true
)
data class TranslationSessionState(
    val active: TranslationPair? = null,
    val results: Map<String, SegmentTranslation> = emptyMap()
)

/** In-memory, screen-scoped results. One actual inference at a time, including across language switches. */
class TranslationSession(private val factory: (TranslationPair) -> SegmentTranslator) {
    private val mutable = MutableStateFlow(TranslationSessionState())
    val state = mutable.asStateFlow()
    private var noteId: String? = null
    private var translator: SegmentTranslator? = null
    private var generation = 0L
    private data class Request(val id: String, val entry: SegmentTranslation, val generation: Long)
    private val queue = ArrayDeque<Request>()
    private var running: SegmentTranslator? = null
    // In-use packs remain protected until an outstanding native inference has returned.
    private var runningPair: TranslationPair? = null
    val protectedPacks get() = (state.value.active?.packs ?: emptySet()) + (runningPair?.packs ?: emptySet())

    companion object { const val LOOKUP_ID = "selection-lookup" }
    fun lookup(text: String) {
        invalidate(LOOKUP_ID)
        request(LOOKUP_ID, text)
    }
    fun dismissLookup() = invalidate(LOOKUP_ID)

    fun enter(id: String) {
        if (noteId != id) { leave(); noteId = id }
    }
    fun activate(pair: TranslationPair) {
        if (state.value.active == pair) return
        val next = factory(pair) // Failure leaves the previous translator usable.
        val previousTarget = state.value.active?.target ?: state.value.results.values.firstOrNull()?.target
        deactivate()
        translator = next
        mutable.value = state.value.copy(active = pair,
            results = if (previousTarget != null && previousTarget != pair.target) emptyMap() else state.value.results)
    }
    fun deactivate() {
        generation++
        queue.clear()
        translator?.let { if (it !== running) it.close() }
        translator = null
        mutable.value = state.value.copy(active = null, results = state.value.results.filterValues { !it.pending })
    }
    fun leave() {
        deactivate()
        noteId = null
        mutable.value = TranslationSessionState()
    }
    fun invalidate(id: String) {
        queue.removeAll { it.id == id }
        mutable.value = state.value.copy(results = state.value.results - id)
    }
    fun reconcile(segments: List<Segment>) {
        val sources = segments.associate { it.id to it.text }
        state.value.results.filter { (id, entry) -> id != LOOKUP_ID && sources[id] != entry.source }.keys.forEach(::invalidate)
    }
    fun toggle(id: String) {
        val entry = state.value.results[id] ?: return
        mutable.value = state.value.copy(results = state.value.results + (id to entry.copy(expanded = !entry.expanded)))
    }
    fun request(id: String, text: String) {
        val pair = state.value.active ?: return
        if (text.isBlank()) return
        val existing = state.value.results[id]
        if (existing?.source == text && existing.target == pair.target) {
            if (existing.pending) return
            if (existing.text != null) { toggle(id); return }
        }
        val entry = SegmentTranslation(text, pair.target)
        mutable.value = state.value.copy(results = state.value.results + (id to entry))
        queue.addLast(Request(id, entry, generation))
        pump()
    }
    private fun pump() {
        if (running != null) return
        val engine = translator ?: return
        val request = queue.removeFirstOrNull() ?: return
        if (request.generation != generation || state.value.results[request.id] !== request.entry) { pump(); return }
        running = engine
        runningPair = state.value.active
        val complete: (Result<String>) -> Unit = { result ->
            running = null
            runningPair = null
            if (engine !== translator) engine.close()
            if (request.generation == generation && state.value.results[request.id] === request.entry) {
                val translated = result.getOrNull()?.takeIf { it.isNotBlank() }
                mutable.value = state.value.copy(results = state.value.results + (request.id to request.entry.copy(
                    pending = false, text = translated,
                    error = if (translated != null) null else result.exceptionOrNull()?.localizedMessage ?: "No translation returned. Try again."
                )))
            }
            pump()
        }
        try { engine.translate(request.entry.source, complete) }
        catch (e: Exception) { complete(Result.failure(e)) }
    }
}
