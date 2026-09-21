package app.naz.whispernote

import android.content.ActivityNotFoundException
import android.content.Intent
import android.view.ActionMode
import android.view.Menu
import android.view.MenuItem
import android.widget.TextView
import android.widget.Toast
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.viewinterop.AndroidView

/** Native selection keeps accessibility and installed apps' text actions available. */
@Composable
internal fun SelectableTranscriptText(text: String, modifier: Modifier = Modifier, onTranslate: ((String) -> Unit)? = null) {
    val translateSelection by rememberUpdatedState(onTranslate)
    val color = MaterialTheme.colorScheme.onSurface.toArgb()
    val size = MaterialTheme.typography.bodyLarge.fontSize.value
    AndroidView(
        modifier = modifier,
        factory = { context ->
            TextView(context).apply {
                setTextIsSelectable(true)
                setPadding(0, 0, 0, 0)
                setLineSpacing(0f, 1.25f)
                customSelectionActionModeCallback = object : ActionMode.Callback {
                    override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean = onPrepareActionMode(mode, menu)
                    override fun onPrepareActionMode(mode: ActionMode, menu: Menu): Boolean {
                        if (translateSelection != null && menu.findItem(LOCAL_TRANSLATION) == null) {
                            menu.add(Menu.NONE, LOCAL_TRANSLATION, 99, "Translate")
                                .setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
                        }
                        // Replace the platform Share item to avoid duplicates on different Android versions.
                        menu.removeItem(android.R.id.shareText)
                        if (menu.findItem(SHARE_SELECTION) == null) {
                            menu.add(Menu.NONE, SHARE_SELECTION, 100, "Share…")
                                .setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
                        }
                        if (menu.findItem(TRANSLATE_SELECTION) == null) {
                            menu.add(Menu.NONE, TRANSLATE_SELECTION, 101, "Translate with…")
                                .setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
                        }
                        return true
                    }
                    override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
                        if (item.itemId != LOCAL_TRANSLATION && item.itemId != SHARE_SELECTION && item.itemId != TRANSLATE_SELECTION) return false
                        val selected = selectedSubstring(this@apply.text, selectionStart, selectionEnd)
                        if (selected.isEmpty()) return true
                        if (item.itemId == LOCAL_TRANSLATION) {
                            mode.finish()
                            translateSelection?.invoke(selected)
                        } else {
                            openSelectedText(context, selected, item.itemId == SHARE_SELECTION)
                            mode.finish()
                        }
                        return true
                    }
                    override fun onDestroyActionMode(mode: ActionMode) = Unit
                }
            }
        },
        update = { view ->
            if (view.text.toString() != text) view.text = text
            view.setTextColor(color)
            view.textSize = size
        }
    )
}

internal fun selectedSubstring(text: CharSequence, start: Int, end: Int): String {
    if (start < 0 || end < 0 || start > text.length || end > text.length) return ""
    return text.subSequence(minOf(start, end), maxOf(start, end)).toString()
}

private const val SHARE_SELECTION = 0x575001
private const val TRANSLATE_SELECTION = 0x575002

private const val LOCAL_TRANSLATION = 0x575003

internal fun openSelectedText(context: android.content.Context, selected: String, sharing: Boolean = false) {
    val intent = Intent(if (sharing) Intent.ACTION_SEND else Intent.ACTION_PROCESS_TEXT).apply {
        type = "text/plain"
        putExtra(if (sharing) Intent.EXTRA_TEXT else Intent.EXTRA_PROCESS_TEXT, selected)
        if (!sharing) putExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, true)
    }
    if (!sharing && context.packageManager.queryIntentActivities(intent, 0).isEmpty()) {
        openSelectedText(context, selected, sharing = true)
        return
    }
    try {
        context.startActivity(Intent.createChooser(intent, if (sharing) "Share selected text" else "Translate selected text with"))
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(context, "No compatible app is available.", Toast.LENGTH_LONG).show()
    }
}
