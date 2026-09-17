package app.naz.whispernote

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.naz.whispernote.core.*
import android.net.Uri
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class LocalTranscriptionTest {
    @Test fun decodeAndTranscribeOnDevice()=runBlocking {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val context=instrumentation.targetContext
        val wav=File(context.cacheDir,"test-jfk.wav")
        val pcm=File(context.cacheDir,"test-jfk.pcm")
        try {
            instrumentation.context.assets.open("jfk.wav").use { input -> wav.outputStream().use { input.copyTo(it) } }
            val duration=AudioDecoder(context).decode(Uri.fromFile(wav),pcm)
            assertTrue(duration in 10000..12000)
            val manager=ModelManager(context)
            manager.download("tiny") {}
            val segments=mutableListOf<Segment>()
            val language=WhisperEngine().transcribe(manager.file("tiny").path,pcm.path,0,"",object: WhisperEngine.Listener {
                override fun onProgress(progress: Int) { assertTrue(progress in 0..100) }
                override fun onSegment(start: Long,end: Long,text: String) {segments.add(Segment(start,end,text))}
                override fun isCancelled()=false
            })
            assertEquals("en",language)
            assertTrue(segments.isNotEmpty())
            assertTrue(segments.all { it.start>=0 && it.end>=it.start })
            assertTrue(segments.joinToString(" ") {it.text}.contains("country",true))
        } finally {wav.delete(); pcm.delete()}
    }
}
