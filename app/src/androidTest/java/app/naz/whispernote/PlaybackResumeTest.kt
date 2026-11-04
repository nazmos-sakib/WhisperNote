package app.naz.whispernote

import android.net.Uri
import androidx.test.platform.app.InstrumentationRegistry
import app.naz.whispernote.core.AudioPlayer
import app.naz.whispernote.core.PlaybackPositions
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID

class PlaybackResumeTest {
    private val instrumentation=InstrumentationRegistry.getInstrumentation()
    private val context=instrumentation.targetContext
    private fun awaitReady(player: AudioPlayer) {
        val deadline=System.currentTimeMillis()+10000
        while(!player.state.value.ready && System.currentTimeMillis()<deadline) Thread.sleep(50)
        assertTrue("Player did not prepare: ${player.state.value}",player.state.value.ready)
    }
    @Test fun leavingAndReopeningRestoresPositionPausedAndKeepsNotesIndependent() {
        val id=UUID.randomUUID().toString();val other=UUID.randomUUID().toString()
        val audio=File(context.cacheDir,"resume-$id.wav")
        instrumentation.context.assets.open("jfk.wav").use { input -> audio.outputStream().use {input.copyTo(it)} }
        val positions=PlaybackPositions(context)
        var player: AudioPlayer?=null
        try {
            positions.save(other,17000)
            instrumentation.runOnMainSync { player=AudioPlayer(context,Uri.fromFile(audio).toString(),positions.get(id)) {positions.save(id,it)} }
            awaitReady(player!!)
            instrumentation.runOnMainSync {player!!.seek(7000)}
            // Disposal must save the seek target even before the asynchronous seek finishes.
            instrumentation.runOnMainSync {player!!.release();player=null}
            assertEquals(7000.0,PlaybackPositions(context).get(id).toDouble(),350.0)
            instrumentation.runOnMainSync { player=AudioPlayer(context,Uri.fromFile(audio).toString(),PlaybackPositions(context).get(id)) {positions.save(id,it)} }
            awaitReady(player!!)
            assertEquals(7000.0,player!!.state.value.position.toDouble(),350.0)
            assertFalse(player!!.state.value.playing)
            instrumentation.runOnMainSync {player!!.setSpeed(1.5f)}
            assertEquals(1.5f,player!!.state.value.speed)
            assertFalse("Changing speed must not start paused audio",player!!.state.value.playing)
            assertNull(player!!.state.value.error)
            instrumentation.runOnMainSync {player!!.setSpeed(0.75f)}
            assertEquals(0.75f,player!!.state.value.speed)
            assertFalse(player!!.state.value.playing)
            assertEquals(17000L,positions.get(other))
        } finally {
            instrumentation.runOnMainSync {player?.release()}
            positions.remove(id);positions.remove(other);audio.delete()
        }
    }
    @Test fun naturalEndMarksCompletedAndReplayStartsFromBeginning() {
        val id=UUID.randomUUID().toString()
        val audio=File(context.cacheDir,"completion-$id.wav")
        val positions=PlaybackPositions(context)
        var player: AudioPlayer?=null
        try {
            instrumentation.context.assets.open("jfk.wav").use {input -> audio.outputStream().use {input.copyTo(it)}}
            instrumentation.runOnMainSync {
                player=AudioPlayer(context,Uri.fromFile(audio).toString(),onCompleted={positions.markCompleted(id,true)}) {positions.save(id,it)}
            }
            awaitReady(player!!)
            instrumentation.runOnMainSync {player!!.seek(player!!.state.value.duration-600)}
            Thread.sleep(300)
            assertFalse(positions.progress(id).completed)
            instrumentation.runOnMainSync {player!!.play()}
            val deadline=System.currentTimeMillis()+5000
            while(!positions.progress(id).completed && System.currentTimeMillis()<deadline) Thread.sleep(50)
            assertTrue(positions.progress(id).completed)
            instrumentation.runOnMainSync {player!!.toggle()}
            Thread.sleep(300)
            assertTrue(player!!.state.value.position<2000)
        } finally {
            instrumentation.runOnMainSync {player?.release()}
            positions.remove(id);audio.delete()
        }
    }
    @Test fun listeningCompletionIsIndependentAndDurable() {
        val id=UUID.randomUUID().toString()
        val positions=PlaybackPositions(context)
        try {
            assertEquals(app.naz.whispernote.core.ListeningState.Unplayed,positions.progress(id).state)
            positions.save(id,5000)
            assertEquals(app.naz.whispernote.core.ListeningState.InProgress,positions.progress(id).state)
            positions.markCompleted(id,true)
            positions.save(id,6000)
            assertTrue(PlaybackPositions(context).progress(id).completed)
            positions.markCompleted(id,false)
            assertFalse(PlaybackPositions(context).progress(id).completed)
            assertEquals(6000L,positions.get(id))
        } finally { positions.remove(id) }
    }
    @Test fun releasingBeforePreparationDoesNotEraseSavedPosition() {
        val id=UUID.randomUUID().toString();val positions=PlaybackPositions(context)
        positions.save(id,420000)
        try {
            instrumentation.runOnMainSync {
                val player=AudioPlayer(context,"file:///missing-audio.wav",positions.get(id)) {positions.save(id,it)}
                player.release()
            }
            assertEquals(420000L,positions.get(id))
        } finally {positions.remove(id)}
    }
}
