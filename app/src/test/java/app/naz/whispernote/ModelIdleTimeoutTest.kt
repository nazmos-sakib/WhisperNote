package app.naz.whispernote

import app.naz.whispernote.core.ModelIdleTimeout
import org.junit.Assert.*
import org.junit.Test

class ModelIdleTimeoutTest {
    @Test fun foregroundActivityResetsIdleAndNeverExpiresBusyWork() {
        var now=0L
        val timeout=ModelIdleTimeout {now}
        timeout.foreground()
        now=599999; assertFalse(timeout.expired(false))
        timeout.activity(); now+=599999; assertFalse(timeout.expired(false))
        now++; assertTrue(timeout.expired(false)); assertFalse(timeout.expired(true))
        timeout.activity(); assertFalse(timeout.expired(false))
    }
    @Test fun backgroundDeadlineSurvivesActivityAndDefersUntilWorkFinishes() {
        var now=0L
        val timeout=ModelIdleTimeout {now}
        timeout.foreground(); timeout.background()
        now=300000; assertFalse(timeout.expired(true))
        timeout.activity(); assertTrue(timeout.expired(false))
    }
    @Test fun shortBackgroundVisitCancelsDeadlineAndNextVisitGetsFiveMinutes() {
        var now=0L
        val timeout=ModelIdleTimeout {now}
        timeout.foreground(); timeout.background()
        now=299999; assertFalse(timeout.expired(false))
        timeout.foreground(); assertFalse(timeout.expired(false))
        timeout.background(); now+=299999; assertFalse(timeout.expired(false))
        now++; assertTrue(timeout.expired(false))
    }
}
