package app.naz.whispernote.core

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.os.SystemClock
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class TranscriptionState(val model: String? = null, val operation: String? = null)

/** Application-owned native model. Drafts are deliberately owned by SegmentRetries instead. */
class TranscriptionSession(private val app: Application, private val scope: CoroutineScope) : Application.ActivityLifecycleCallbacks {
    private val mutable = MutableStateFlow(TranscriptionState())
    val state = mutable.asStateFlow()
    @Volatile var foreground = false
        private set
    private var activities = 0
    private val timeout = ModelIdleTimeout(SystemClock::elapsedRealtime)
    init {
        app.registerActivityLifecycleCallbacks(this)
        scope.launch { while (isActive) { delay(1000); expire() } }
    }
    @Synchronized private fun expire() {
        if (mutable.value.model != null && timeout.expired(mutable.value.operation != null)) unload()
    }
    @Synchronized fun tryBegin(label: String): Boolean {
        if (mutable.value.operation != null) return false
        timeout.activity()
        mutable.value = mutable.value.copy(operation=label)
        return true
    }
    fun begin(label: String) {
        check(tryBegin(label)) { "A model operation is already running. Open the model button to view its status." }
    }
    @Synchronized fun status(label: String) { mutable.value = mutable.value.copy(operation=label) }
    fun ensureLoaded(model: String) {
        if (mutable.value.model == model) return
        status("Loading model…")
        try {
            WhisperEngine().load(ModelManager(app).file(model).path)
            mutable.value = mutable.value.copy(model=model)
        } catch (e: Exception) { mutable.value = mutable.value.copy(model=null); throw e }
    }
    @Synchronized fun finish() {
        timeout.activity()
        mutable.value = mutable.value.copy(operation=null)
        expire()
    }
    @Synchronized fun unload() {
        check(mutable.value.operation == null) { "Wait for the current model operation to finish." }
        if (mutable.value.model == null) return
        begin("Unloading model…")
        scope.launch {
            try { WhisperEngine().unload(); mutable.value = TranscriptionState(operation="Unloading model…") }
            finally { finish() }
        }
    }
    @Synchronized override fun onActivityStarted(activity: Activity) {
        if (activities++ == 0) { expire(); foreground = true; timeout.foreground() }
    }
    @Synchronized override fun onActivityStopped(activity: Activity) {
        if (--activities == 0 && !activity.isChangingConfigurations) { foreground=false; timeout.background() }
    }
    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
    override fun onActivityResumed(activity: Activity) {}
    override fun onActivityPaused(activity: Activity) {}
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
    override fun onActivityDestroyed(activity: Activity) {}
}
