package app.naz.whispernote

import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import app.naz.whispernote.core.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Opt-in short-sample benchmark. Downloads are outside timed sections; no library notes are modified. */
class PerformanceBenchmarkTest {
    @Test fun benchmark() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val args = InstrumentationRegistry.getArguments()
        org.junit.Assume.assumeTrue("Run explicitly with -e models", args.containsKey("models"))
        val context = instrumentation.targetContext
        val models = (args.getString("models") ?: "tiny").split(",")
        val repetitions = (args.getString("repetitions") ?: "2").toInt().coerceIn(1, 5)
        val label = args.getString("label") ?: "manual"
        val manager = ModelManager(context)
        val wav = File(context.cacheDir, "benchmark-jfk.wav")
        val pcm = File(context.cacheDir, "benchmark-jfk.pcm")
        val power = context.getSystemService(PowerManager::class.java)
        val wake = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "WhisperNote:benchmark")
        wake.acquire(15 * 60 * 1000L)
        try {
            instrumentation.context.assets.open("jfk.wav").use { input -> wav.outputStream().use { input.copyTo(it) } }
            val decodeStart = SystemClock.elapsedRealtime()
            val duration = AudioDecoder(context).decode(Uri.fromFile(wav), pcm)
            val decodeMs = SystemClock.elapsedRealtime() - decodeStart
            for (model in models) {
                manager.download(model) {}
                repeat(repetitions) { run ->
                    val segments = mutableListOf<Segment>()
                    val started = SystemClock.elapsedRealtime()
                    var loadedAt = 0L
                    var firstSegmentAt = 0L
                    val language = WhisperEngine().transcribe(manager.file(model).path, pcm.path, 0, "en", object : WhisperEngine.Listener {
                        override fun onProgress(progress: Int) { if (loadedAt == 0L) loadedAt = SystemClock.elapsedRealtime() }
                        override fun onSegment(start: Long, end: Long, text: String) {
                            if (firstSegmentAt == 0L) firstSegmentAt = SystemClock.elapsedRealtime()
                            segments.add(Segment(start, end, text))
                        }
                        override fun isCancelled() = false
                    })
                    val finished = SystemClock.elapsedRealtime()
                    assertEquals("en", language)
                    assertTrue(segments.joinToString(" ") { it.text }.contains("country", ignoreCase = true))
                    assertTrue(segments.all { it.start >= 0 && it.end <= duration && it.end > it.start })
                    val result = "$label model=$model run=${run+1} audioMs=$duration decodeMs=$decodeMs loadMs=${loadedAt-started} inferenceMs=${finished-loadedAt} totalMs=${finished-started} firstSegmentMs=${firstSegmentAt-started} segments=${segments.size} thermal=${if(android.os.Build.VERSION.SDK_INT>=29) power.currentThermalStatus else -1}"
                    instrumentation.sendStatus(0, Bundle().apply { putString("stream", "\nBENCHMARK $result\n") })
                }
            }
        } finally {
            if (wake.isHeld) wake.release()
            wav.delete(); pcm.delete()
        }
    }
}
