package app.naz.whispernote

import android.app.Instrumentation
import android.content.Intent
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.platform.app.InstrumentationRegistry
import app.naz.whispernote.ui.theme.WhisperNoteTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class SelectedTextActionsTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun shareSendsOnlyTheSelectedWord() {
        wakeUiTestDisplay()
        compose.activityRule.scenario.onActivity { it.showUiTestWindow() }
        compose.setContent { WhisperNoteTheme { SelectableTranscriptText("Hallo Welt", Modifier.padding(top = 120.dp, start = 40.dp)) } }
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        var sent: Intent? = null
        val monitor = object : Instrumentation.ActivityMonitor() {
            override fun onStartActivity(intent: Intent): Instrumentation.ActivityResult? {
                if (intent.action != Intent.ACTION_CHOOSER) return null
                @Suppress("DEPRECATION")
                sent = intent.getParcelableExtra(Intent.EXTRA_INTENT)
                return Instrumentation.ActivityResult(0, null)
            }
        }
        instrumentation.addMonitor(monitor)
        try {
            onView(withText("Hallo Welt")).check { view, error ->
                if (error != null) throw error
                val text = view as android.widget.TextView
                android.text.Selection.setSelection(text.text as android.text.Spannable, 6, 10)
                val menu = android.widget.PopupMenu(text.context, text).menu
                val mode = object : android.view.ActionMode() {
                    override fun setTitle(title: CharSequence?) = Unit
                    override fun setTitle(resId: Int) = Unit
                    override fun setSubtitle(subtitle: CharSequence?) = Unit
                    override fun setSubtitle(resId: Int) = Unit
                    override fun setCustomView(view: android.view.View?) = Unit
                    override fun invalidate() = Unit
                    override fun finish() = Unit
                    override fun getMenu() = menu
                    override fun getTitle(): CharSequence = ""
                    override fun getSubtitle(): CharSequence = ""
                    override fun getCustomView(): android.view.View? = null
                    override fun getMenuInflater() = android.view.MenuInflater(text.context)
                }
                val callback = text.customSelectionActionModeCallback!!
                callback.onCreateActionMode(mode, menu)
                assertTrue((0 until menu.size()).any { menu.getItem(it).title == "Translate with…" })
                val share = (0 until menu.size()).map { menu.getItem(it) }.first { it.title == "Share…" }
                callback.onActionItemClicked(mode, share)
            }
            assertEquals(Intent.ACTION_SEND, sent?.action)
            assertEquals("text/plain", sent?.type)
            val selected = sent?.getStringExtra(Intent.EXTRA_TEXT)
            assertTrue("Expected one selected word, got $selected", selected == "Welt")
        } finally { instrumentation.removeMonitor(monitor) }
    }

    @Test fun reversedAndInvalidSelectionRanges() {
        assertEquals("Welt", selectedSubstring("Hallo Welt", 10, 6))
        assertEquals("", selectedSubstring("Hallo Welt", -1, 6))
        assertEquals("", selectedSubstring("Hallo Welt", 0, 11))
    }
}
