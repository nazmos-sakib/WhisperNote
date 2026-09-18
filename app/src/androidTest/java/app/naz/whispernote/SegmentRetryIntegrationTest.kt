package app.naz.whispernote

import android.net.Uri
import androidx.test.platform.app.InstrumentationRegistry
import app.naz.whispernote.core.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID

class SegmentRetryIntegrationTest {
    private val instrumentation=InstrumentationRegistry.getInstrumentation()
    private val context=instrumentation.targetContext
    @Test fun rangedDecoderMatchesSameSamplesFromFullDecode()=runBlocking {
        val id=UUID.randomUUID().toString()
        val wav=File(context.cacheDir,"$id.wav");val full=File(context.cacheDir,"$id-full.pcm");val crop=File(context.cacheDir,"$id-crop.pcm")
        try {
            instrumentation.context.assets.open("jfk.wav").use {input->wav.outputStream().use {input.copyTo(it)}}
            AudioDecoder(context).decode(Uri.fromFile(wav),full)
            assertEquals(3000L,AudioDecoder(context).decode(Uri.fromFile(wav),crop,2000,5000))
            assertArrayEquals(full.readBytes().copyOfRange(2000*16*4,5000*16*4),crop.readBytes())
        } finally {wav.delete();full.delete();crop.delete()}
    }
    @Test fun draftsSurviveReopenAndInterruptedWorkBecomesRetryable() {
        val name="retry-test-${UUID.randomUUID()}"
        try {
            val store=SegmentRetries(context,name);store.load()
            val original=Segment(1000,6000,"Original")
            val ready=SegmentRetry("ready",original,"base","en",status="Ready",result=listOf(Segment(1000,6000,"Suggestion")))
            store.put(ready);store.put(SegmentRetry("unfinished",original,"base","en"))
            val reopened=SegmentRetries(context,name);reopened.load()
            assertEquals(ready,reopened.get("ready"))
            assertEquals("Failed",reopened.get("unfinished")!!.status)
        } finally {context.deleteSharedPreferences(name)}
    }
    @Test fun serviceProducesBoundedSuggestionWithoutChangingOriginal()=runBlocking {
        val app=context.applicationContext as WhisperApp;app.ready.await()
        val manager=ModelManager(context)
        val model=ModelCatalog.models.firstOrNull {manager.ready(it.id)} ?: error("A downloaded model is required for this device test.")
        val id=UUID.randomUUID().toString();val wav=File(context.cacheDir,"retry-$id.wav")
        val original=Segment(1000,7000,"Keep this until accepted")
        val note=Note(id=id,title="Segment retry test",audio=Uri.fromFile(wav).toString(),status="Completed",duration=11000,checkpointMs=11000,segments=listOf(original))
        val draft=SegmentRetry(id,original,model.id,"en")
        try {
            instrumentation.context.assets.open("jfk.wav").use {input->wav.outputStream().use {input.copyTo(it)}}
            app.repository.put(note);app.retries.put(draft)
            ProcessingService.start(context,id,retryId=draft.id)
            withTimeout(180000) {while(app.retries.get(id)?.busy==true) delay(200)}
            val result=app.retries.get(id)!!
            assertEquals(result.error,"Ready",result.status)
            assertTrue(result.result.isNotEmpty())
            assertTrue(result.result.all {it.start>=1000 && it.end<=7000})
            assertEquals(note,app.repository.get(id))
            app.repository.update(id) {it.acceptRetry(result,false)}
            assertEquals(original.id,app.repository.get(id)!!.segments.single().id)
            assertEquals(11000L,app.repository.get(id)!!.checkpointMs)
        } finally {app.retries.remove(id);app.repository.delete(id);wav.delete()}
    }
}
