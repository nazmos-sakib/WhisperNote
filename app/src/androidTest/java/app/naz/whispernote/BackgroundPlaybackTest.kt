package app.naz.whispernote

import android.app.Notification
import android.app.NotificationManager
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.session.MediaController
import android.media.session.MediaSession
import android.net.Uri
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import app.naz.whispernote.core.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

class BackgroundPlaybackTest {
    @get:Rule val compose = createEmptyComposeRule()
    @org.junit.Before fun wakeDisplay() { wakeUiTestDisplay() }
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app get() = instrumentation.targetContext.applicationContext as WhisperApp
    private fun waitFor(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 15000
        while (!condition() && System.currentTimeMillis() < deadline) Thread.sleep(50)
        assertTrue("Condition timed out; playback=${app.playback.state.value}", condition())
    }
    private fun fixture(): Pair<Note, File> {
        runBlocking { app.ready.await() }
        val file = File(app.cacheDir, "background-${UUID.randomUUID()}.wav")
        val bytes = 16000 * 2 * 90
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray()).putInt(bytes+36).put("WAVEfmt ".toByteArray()).putInt(16)
            .putShort(1).putShort(1).putInt(16000).putInt(32000).putShort(2).putShort(16).put("data".toByteArray()).putInt(bytes)
        file.outputStream().use { it.write(header.array()); it.write(ByteArray(bytes)) }
        val note = Note(title="Background playback fixture", audio=Uri.fromFile(file).toString(), status="Completed", duration=90000,
            segments=listOf(Segment(0,90000,"Background playback test.")))
        app.repository.put(note)
        return note to file
    }
    @Test fun playbackSurvivesNavigationAndBackgroundWithMediaControlsAndStop() {
        val (note, file) = fixture()
        try {
            ActivityScenario.launch<MainActivity>(Intent(app, MainActivity::class.java).putExtra("noteId",note.id)).use { scenario ->
                scenario.onActivity { it.showUiTestWindow() }
                compose.waitUntil(10000) { runCatching { compose.onAllNodesWithContentDescription("Play").fetchSemanticsNodes().isNotEmpty() }.getOrDefault(false) }
                compose.onNodeWithContentDescription("Play").performClick()
                waitFor { app.playback.state.value?.playback?.playing == true }
                compose.onNodeWithContentDescription("Back").performClick()
                compose.onNodeWithContentDescription("Pause audio").assertExists()
                val start = app.playback.state.value!!.playback.position
                scenario.moveToState(Lifecycle.State.CREATED)
                waitFor { app.playback.state.value!!.playback.position > start + 750 }
                assertTrue(app.playback.state.value!!.playback.playing)
                val notifications = app.getSystemService(NotificationManager::class.java).activeNotifications
                val notification = notifications.first { it.notification.category == Notification.CATEGORY_TRANSPORT }.notification
                @Suppress("DEPRECATION")
                val token = notification.extras.getParcelable<MediaSession.Token>(Notification.EXTRA_MEDIA_SESSION)!!
                val controls = MediaController(app, token).transportControls
                controls.pause()
                waitFor { app.playback.state.value?.playback?.playing == false }
                val paused = app.playback.state.value!!.playback.position
                controls.fastForward()
                waitFor { app.playback.state.value!!.playback.position > paused + 9000 }
                controls.rewind()
                waitFor { kotlin.math.abs(app.playback.state.value!!.playback.position-paused) < 500 }
                controls.play()
                waitFor { app.playback.state.value!!.playback.playing }
                scenario.moveToState(Lifecycle.State.RESUMED)
                scenario.onActivity { it.showUiTestWindow() }
                repeat(2) {
                    notification.contentIntent.send()
                    compose.waitUntil(10000) { runCatching { compose.onAllNodesWithTag("translation-toolbar").fetchSemanticsNodes().isNotEmpty() }.getOrDefault(false) }
                    compose.onNodeWithContentDescription("Back").performClick()
                    compose.onNodeWithContentDescription("Pause audio").assertExists()
                }
                compose.onNodeWithContentDescription("Stop audio").performClick()
                waitFor { app.playback.state.value == null }
                waitFor { app.getSystemService(NotificationManager::class.java).activeNotifications.none { it.notification.category == Notification.CATEGORY_TRANSPORT } }
                assertTrue(PlaybackPositions(app).get(note.id) >= paused)
            }
        } finally {
            instrumentation.runOnMainSync { app.playback.stop() }
            waitFor { app.playback.state.value == null }
            app.repository.delete(note.id); PlaybackPositions(app).remove(note.id); file.delete()
        }
    }
    @androidx.test.filters.SdkSuppress(minSdkVersion=26)
    @Test fun briefBackgroundRetainsTranslationAndAudioFocusInterruptionsPauseAndResume() {
        val (note,file) = fixture()
        val manager=app.getSystemService(AudioManager::class.java)
        val focus=AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build())
            .setOnAudioFocusChangeListener {}.build()
        try {
            ActivityScenario.launch<MainActivity>(Intent(app, MainActivity::class.java).putExtra("noteId",note.id)).use { scenario ->
                lateinit var translation: TranslationViewModel
                scenario.onActivity {
                    it.showUiTestWindow()
                    translation=ViewModelProvider(it)[TranslationViewModel::class.java]
                }
                compose.waitUntil(10000) { runCatching { compose.onAllNodesWithTag("translation-toolbar").fetchSemanticsNodes().isNotEmpty() }.getOrDefault(false) }
                scenario.onActivity {
                    translation.session.activate(TranslationPair("de","en"))
                    app.playback.toggle(note)
                }
                waitFor { app.playback.state.value?.playback?.playing == true }
                scenario.moveToState(Lifecycle.State.CREATED)
                assertNotNull(translation.session.state.value.active)
                assertTrue(app.playback.state.value!!.playback.playing)
                instrumentation.runOnMainSync { assertEquals(AudioManager.AUDIOFOCUS_REQUEST_GRANTED,manager.requestAudioFocus(focus)) }
                waitFor { !app.playback.state.value!!.playback.playing }
                instrumentation.runOnMainSync { manager.abandonAudioFocusRequest(focus) }
                waitFor { app.playback.state.value!!.playback.playing }
                scenario.moveToState(Lifecycle.State.RESUMED)
                scenario.onActivity { it.showUiTestWindow() }
                // Starting a different note replaces the current player; there is only one shared session.
                val other=note.copy(id=UUID.randomUUID().toString(),title="Second playback fixture")
                scenario.onActivity { app.playback.toggle(other) }
                waitFor { app.playback.state.value?.noteId == other.id && app.playback.state.value?.playback?.playing == true }
                scenario.onActivity { app.playback.stop() }
                waitFor { app.playback.state.value == null }
                PlaybackPositions(app).remove(other.id)
            }
        } finally {
            instrumentation.runOnMainSync { manager.abandonAudioFocusRequest(focus); app.playback.stop() }
            waitFor { app.playback.state.value == null }
            app.repository.delete(note.id); PlaybackPositions(app).remove(note.id); file.delete()
        }
    }
}
