package app.naz.whispernote.core

import android.content.Context
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.security.MessageDigest
import java.util.Locale

/** Stable IDs are stored in notes; existing standard-model IDs and files remain unchanged. */
data class Model(
    val id: String,
    val family: String,
    val quantized: Boolean,
    val bytes: Long,
    val sha256: String
) {
    val familyName get() = family.replaceFirstChar { it.uppercase() }
    val formatName get() = if (quantized) "Compact (Q5)" else "Standard"
    val displayName get() = "$familyName · $formatName"
    val size get() = String.format(Locale.ROOT, "%.1f MB", bytes / 1_000_000.0)
}

object ModelCatalog {
    // Hugging Face LFS SHA-256s and exact byte counts, verified 2026-09-16.
    const val REVISION = "5359861c739e955e79d9a303bcbc70fb988958b1"
    val families = listOf("tiny", "base", "small")
    val models = listOf(
        Model("tiny", "tiny", false, 77_691_713, "be07e048e1e599ad46341c8d2a135645097a538221678b7acdd1b1919c6e1b21"),
        Model("tiny-q5_1", "tiny", true, 32_152_673, "818710568da3ca15689e31a743197b520007872ff9576237bda97bd1b469c3d7"),
        Model("base", "base", false, 147_951_465, "60ed5bc3dd14eea856493d334349b405782ddcaf0028d4b5df4088345fba2efe"),
        Model("base-q5_1", "base", true, 59_707_625, "422f1ae452ade6f30a004d7e5c6a43195e4433bc370bf23fac9cc591f01a8898"),
        Model("small", "small", false, 487_601_967, "1be3a9b2063867b937e64e2ec7483364a79917e157fa98c5d94b5c1fffea987b"),
        Model("small-q5_1", "small", true, 190_085_487, "ae85e4a935d7a567bd102fe55afc16bb595bdb618e11b2fc7591bc08120411bb")
    )
    fun get(id: String) = models.firstOrNull { it.id == id } ?: error("Unknown model: $id")
    fun variant(family: String, quantized: Boolean) = models.first { it.family == family && it.quantized == quantized }
}

/** Only a complete, verified file is published under the filename used by inference. */
internal object ModelFiles {
    suspend fun verifyAndPublish(staging: File, target: File, model: Model) {
        try {
            require(staging.length() == model.bytes) { "The model download is incomplete. Please retry." }
            val digest = MessageDigest.getInstance("SHA-256")
            staging.inputStream().buffered().use { input ->
                val buffer = ByteArray(65536)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val count = input.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                }
            }
            val hash = digest.digest().joinToString("") { "%02x".format(it) }
            require(hash == model.sha256) { "The model failed its integrity check. Please retry the download." }
            currentCoroutineContext().ensureActive()
            check(staging.renameTo(target)) { "Cannot save the downloaded model." }
        } finally { staging.delete() }
    }
}

class ModelManager(context: Context) {
    private val directory = File(context.filesDir, "models").apply { mkdirs() }
    private val preferences = context.getSharedPreferences("model-settings", Context.MODE_PRIVATE)
    val models get() = ModelCatalog.models
    var preferredModel: String
        get() = preferences.getString("preferred", "tiny").takeIf { id -> models.any { it.id == id } } ?: "tiny"
        set(value) { ModelCatalog.get(value); preferences.edit().putString("preferred", value).apply() }

    fun file(id: String): File { ModelCatalog.get(id); return File(directory, "ggml-$id.bin") }
    fun ready(id: String) = file(id).let { it.isFile && it.length() == ModelCatalog.get(id).bytes }

    suspend fun download(id: String, progress: (Int) -> Unit) = downloadMutex.withLock {
        val model = ModelCatalog.get(id)
        if (ready(id)) return@withLock
        val target = file(id)
        val staging = File(directory, "ggml-$id.download")
        try {
            Downloader.download("https://huggingface.co/ggerganov/whisper.cpp/resolve/${ModelCatalog.REVISION}/ggml-$id.bin", staging, progress)
            progress(100) // UI labels this phase Verifying; the model is not ready yet.
            ModelFiles.verifyAndPublish(staging, target, model)
        } finally { staging.delete() }
    }

    companion object { private val downloadMutex = Mutex() }
}
