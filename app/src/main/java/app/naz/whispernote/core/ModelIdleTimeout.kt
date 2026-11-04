package app.naz.whispernote.core

/** Monotonic deadlines; busy work defers expiry without extending the background deadline. */
class ModelIdleTimeout(private val now: () -> Long) {
    var foreground = false
        private set
    private var backgroundAt = now()
    private var activityAt = now()
    fun activity() { activityAt=now() }
    fun foreground() { foreground=true }
    fun background() { if(foreground) {foreground=false; backgroundAt=now()} }
    fun expired(busy: Boolean): Boolean = !busy &&
        if(foreground) now()-activityAt >= 600_000L else now()-backgroundAt >= 300_000L
}
