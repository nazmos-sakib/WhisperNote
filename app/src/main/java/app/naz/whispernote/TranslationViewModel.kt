package app.naz.whispernote

import androidx.lifecycle.ViewModel
import app.naz.whispernote.core.*
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.translate.TranslateRemoteModel
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class MlKitSegmentTranslator(pair: TranslationPair) : SegmentTranslator {
    private val client = Translation.getClient(TranslatorOptions.Builder()
        .setSourceLanguage(pair.source).setTargetLanguage(pair.target).build())
    override fun translate(text: String, complete: (Result<String>) -> Unit) {
        client.translate(text).addOnSuccessListener { complete(Result.success(it)) }
            .addOnFailureListener { complete(Result.failure(it)) }
    }
    override fun close() = client.close()
}

data class TranslationModelsState(
    val downloaded: Set<String> = emptySet(), val busy: String? = null,
    val checking: Boolean = true, val error: String? = null
)

class TranslationViewModel : ViewModel() {
    val session = TranslationSession(::MlKitSegmentTranslator)
    private val timeoutHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val backgroundTimeout = BackgroundTranslationTimeout(
        now = android.os.SystemClock::elapsedRealtime,
        schedule = { delay, action ->
            val runnable = Runnable { action() }
            timeoutHandler.postDelayed(runnable, delay)
            val cancel: () -> Unit = { timeoutHandler.removeCallbacks(runnable) }
            cancel
        },
        deactivate = session::deactivate
    )
    fun onForeground() = backgroundTimeout.foreground()
    fun onBackground() = backgroundTimeout.background()

    private val manager = RemoteModelManager.getInstance()
    private val mutable = MutableStateFlow(TranslationModelsState())
    val models = mutable.asStateFlow()
    private var disposed = false
    init { refresh() }
    fun refresh() {
        if (mutable.value.busy != null) return
        mutable.value = mutable.value.copy(checking = true)
        manager.getDownloadedModels(TranslateRemoteModel::class.java)
            .addOnSuccessListener { if (!disposed) mutable.value = mutable.value.copy(downloaded = it.map { m -> m.language }.toSet(), checking = false) }
            .addOnFailureListener { if (!disposed) mutable.value = mutable.value.copy(checking = false, error = it.localizedMessage ?: "Could not check downloaded models.") }
    }
    fun clearError() { mutable.value = mutable.value.copy(error = null) }
    fun activate(pair: TranslationPair): Boolean {
        if (mutable.value.busy != null || mutable.value.checking || !mutable.value.downloaded.containsAll(pair.packs)) return false
        return try { session.activate(pair); true } catch (e: Exception) {
            mutable.value = mutable.value.copy(error = e.localizedMessage ?: "Could not activate translator."); false
        }
    }
    fun download(pair: TranslationPair, wifiOnly: Boolean) {
        if (mutable.value.busy != null || mutable.value.checking) return
        val missing = (pair.packs - mutable.value.downloaded).toList()
        if (missing.isEmpty()) return
        mutable.value = mutable.value.copy(busy = pair.id, error = null)
        val conditions = DownloadConditions.Builder().apply { if (wifiOnly) requireWifi() }.build()
        fun next(index: Int) {
            if (disposed) return
            if (index == missing.size) { mutable.value = mutable.value.copy(busy = null); refresh(); return }
            val code = missing[index]
            manager.download(TranslateRemoteModel.Builder(code).build(), conditions)
                .addOnSuccessListener {
                    if (!disposed) { mutable.value = mutable.value.copy(downloaded = mutable.value.downloaded + code); next(index + 1) }
                }
                .addOnFailureListener {
                    if (!disposed) { mutable.value = mutable.value.copy(busy = null, error = it.localizedMessage ?: "Download failed. Try again."); refresh() }
                }
        }
        next(0)
    }
    fun deletePack(code: String) {
        if (mutable.value.busy != null || mutable.value.checking) return
        if (code in session.protectedPacks) {
            mutable.value = mutable.value.copy(error = "Deactivate this translator and wait for its current translation to finish before removing this pack.")
            return
        }
        mutable.value = mutable.value.copy(busy = "delete-$code", error = null)
        manager.deleteDownloadedModel(TranslateRemoteModel.Builder(code).build())
            .addOnSuccessListener { if (!disposed) { mutable.value = mutable.value.copy(busy = null, downloaded = mutable.value.downloaded - code); refresh() } }
            .addOnFailureListener { if (!disposed) mutable.value = mutable.value.copy(busy = null, error = it.localizedMessage ?: "Could not remove model.") }
    }
    override fun onCleared() { backgroundTimeout.clear(); disposed = true; session.leave() }
}
