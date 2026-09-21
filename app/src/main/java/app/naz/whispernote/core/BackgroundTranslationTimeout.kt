package app.naz.whispernote.core

/** Main-thread timeout with a monotonic deadline, including time the device spends asleep. */
class BackgroundTranslationTimeout(
    private val now: () -> Long,
    private val schedule: (Long, () -> Unit) -> (() -> Unit),
    private val deactivate: () -> Unit
) {
    private var deadline: Long? = null
    private var cancel: (() -> Unit)? = null

    fun background() {
        if (deadline != null) return
        deadline = now() + TIMEOUT_MS
        cancel = schedule(TIMEOUT_MS) { expire() }
    }

    fun foreground() {
        // A sleeping/frozen process may not receive its delayed callback until it resumes.
        if (deadline?.let { now() >= it } == true) deactivate()
        clear()
    }

    private fun expire() {
        val remaining = (deadline ?: return) - now()
        if (remaining > 0) {
            cancel = schedule(remaining) { expire() }
        } else {
            clear()
            deactivate()
        }
    }

    fun clear() {
        cancel?.invoke()
        cancel = null
        deadline = null
    }

    companion object { const val TIMEOUT_MS = 5 * 60 * 1000L }
}
