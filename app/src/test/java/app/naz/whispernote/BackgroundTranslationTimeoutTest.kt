package app.naz.whispernote

import app.naz.whispernote.core.BackgroundTranslationTimeout
import org.junit.Assert.*
import org.junit.Test

class BackgroundTranslationTimeoutTest {
    private class Fixture {
        var time = 0L
        var unloads = 0
        var callback: (() -> Unit)? = null
        val timeout = BackgroundTranslationTimeout({ time }, { _, action ->
            callback = action
            val cancel: () -> Unit = { callback = null }
            cancel
        }, { unloads++ })
        fun advance(ms: Long) { time += ms }
    }
    @Test fun shortVisitCancelsAndNextVisitGetsFullFiveMinutes() {
        val f = Fixture()
        f.timeout.background(); f.advance(299999); f.timeout.foreground()
        assertEquals(0, f.unloads); assertNull(f.callback)
        f.timeout.background(); f.advance(299999); f.callback!!()
        assertEquals(0, f.unloads)
        f.advance(1); f.callback!!()
        assertEquals(1, f.unloads); assertNull(f.callback)
        f.timeout.foreground(); assertEquals(1, f.unloads)
    }
    @Test fun returningAfterSleepingPastDeadlineUnloadsBeforeReuse() {
        val f = Fixture()
        f.timeout.background(); f.advance(300001); f.timeout.foreground()
        assertEquals(1, f.unloads); assertNull(f.callback)
    }
    @Test fun duplicateBackgroundDoesNotExtendDeadlineAndClearCancels() {
        val f = Fixture()
        f.timeout.background(); f.advance(200000); f.timeout.background()
        f.advance(100000); f.callback!!()
        assertEquals(1, f.unloads)
        f.timeout.background(); f.timeout.clear()
        assertNull(f.callback)
        f.advance(300000); f.timeout.foreground()
        assertEquals(1, f.unloads)
    }
}
