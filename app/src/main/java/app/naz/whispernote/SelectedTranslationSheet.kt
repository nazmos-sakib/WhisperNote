package app.naz.whispernote

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.naz.whispernote.core.SegmentTranslation

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SelectedTranslationSheet(
    source: String, pairLabel: String?, result: SegmentTranslation?,
    onRetry: () -> Unit, onDismiss: () -> Unit
) {
    val context = LocalContext.current
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp).navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("Translate selection", style = MaterialTheme.typography.titleLarge)
            pairLabel?.let { Text(it, style = MaterialTheme.typography.labelLarge) }
            SelectableTranscriptText(source)
            HorizontalDivider()
            when {
                result?.pending == true -> Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                    Text("Translating…")
                }
                result?.text != null -> {
                    SelectableTranscriptText(result.text)
                    TranslationAttribution()
                    TextButton(onClick = {
                        (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                            .setPrimaryClip(ClipData.newPlainText("Translation", result.text))
                    }) { Text("Copy translation") }
                }
                else -> {
                    Text(result?.error ?: "Choose a translation model to translate this selection.")
                    TextButton(onClick = onRetry) { Text(if (result?.error != null) "Retry" else "Choose model") }
                }
            }
            OutlinedButton(onClick = { openSelectedText(context, source) }) { Text("Translate with another app…") }
            TextButton(onClick = onDismiss) { Text("Done") }
        }
    }
}
