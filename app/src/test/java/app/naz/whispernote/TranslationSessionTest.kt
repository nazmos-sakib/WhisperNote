package app.naz.whispernote

import app.naz.whispernote.core.*
import org.junit.Assert.*
import org.junit.Test

class TranslationSessionTest {
    private class Fake : SegmentTranslator {
        val inputs = mutableListOf<String>()
        var callback: ((Result<String>) -> Unit)? = null
        var closed = false
        override fun translate(text: String, complete: (Result<String>) -> Unit) { inputs += text; callback = complete }
        fun finish(text: String = "Translated") { val cb = callback!!; callback = null; cb(Result.success(text)) }
        fun fail() { val cb = callback!!; callback = null; cb(Result.failure(IllegalStateException("Offline model missing"))) }
        override fun close() { check(!closed); closed = true }
    }
    @Test fun exactSegmentOnlySerialQueueAndDuplicateSuppression() {
        val engine = Fake()
        val session = TranslationSession { engine }
        session.enter("note"); session.activate(TranslationPair("de", "en"))
        session.request("a", "Guten Tag"); session.request("a", "Guten Tag"); session.request("b", "Danke")
        assertEquals(listOf("Guten Tag"), engine.inputs)
        assertTrue(session.state.value.results.getValue("b").pending)
        engine.finish("Good day")
        assertEquals(listOf("Guten Tag", "Danke"), engine.inputs)
        engine.finish("Thank you")
        session.request("a", "Guten Tag")
        assertFalse(session.state.value.results.getValue("a").expanded)
        assertEquals(2, engine.inputs.size)
        session.deactivate()
        assertTrue(engine.closed)
        assertEquals(2, session.state.value.results.size)
        session.enter("note") // same screen after configuration change
        assertEquals(2, session.state.value.results.size)
        session.leave()
        assertTrue(session.state.value.results.isEmpty())
    }
    @Test fun switchingLanguageWaitsForOldInferenceAndRejectsItsResult() {
        val engines = mutableListOf<Fake>()
        val session = TranslationSession { Fake().also(engines::add) }
        session.enter("note"); session.activate(TranslationPair("de", "en"))
        session.request("a", "Hallo"); session.request("b", "Alt")
        session.activate(TranslationPair("de", "bn")); session.request("a", "Hallo")
        assertTrue(engines[1].inputs.isEmpty())
        assertFalse(engines[0].closed)
        engines[0].finish("Hello")
        assertTrue(engines[0].closed)
        assertEquals(listOf("Hallo"), engines[1].inputs)
        assertFalse(session.state.value.results.containsKey("b"))
        engines[1].finish("হ্যালো")
        assertEquals("হ্যালো", session.state.value.results.getValue("a").text)
    }
    @Test fun editsAndNavigationDiscardLateResultsAndReleaseEngine() {
        val engine = Fake(); val session = TranslationSession { engine }
        session.enter("note"); session.activate(TranslationPair("de", "en"))
        session.request("a", "Alt"); session.request("b", "Text")
        session.reconcile(listOf(Segment(0, 100, "Neu", "a")))
        engine.finish()
        assertTrue(session.state.value.results.isEmpty())
        assertEquals(listOf("Alt"), engine.inputs)
        session.request("a", "Neu"); session.enter("other")
        assertFalse(engine.closed)
        engine.finish()
        assertTrue(engine.closed)
        assertNull(session.state.value.active)
        assertTrue(session.state.value.results.isEmpty())
    }
    @Test fun failureCanRetryAndTargetChangeClearsCompletedResults() {
        val engines = mutableListOf<Fake>(); val session = TranslationSession { Fake().also(engines::add) }
        session.activate(TranslationPair("de", "en")); session.request("a", "Hallo")
        engines[0].fail()
        assertFalse(session.state.value.results.getValue("a").pending)
        assertNotNull(session.state.value.results.getValue("a").error)
        session.request("a", "Hallo"); engines[0].finish()
        session.deactivate(); session.activate(TranslationPair("de", "hi"))
        assertTrue(session.state.value.results.isEmpty())
    }
    @Test fun catalogUsesSharedPacksAndBuiltInEnglish() {
        assertEquals(10, TranslationCatalog.pairs.size)
        assertEquals(setOf("de"), TranslationPair("de", "en").packs)
        assertEquals(setOf("de", "bn"), TranslationPair("de", "bn").packs)
        assertEquals(setOf("de", "hi"), TranslationPair("de", "hi").packs)
    }
}
