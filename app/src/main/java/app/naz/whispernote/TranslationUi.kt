package app.naz.whispernote

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.naz.whispernote.core.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TranslationModelsSheet(vm: TranslationViewModel, onDismiss: () -> Unit) {
    val models by vm.models.collectAsStateWithLifecycle()
    val session by vm.session.state.collectAsStateWithLifecycle()
    var wifiOnly by rememberSaveable { mutableStateOf(true) }
    var removing by rememberSaveable { mutableStateOf<String?>(null) }
    var about by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { vm.refresh() }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(24.dp).navigationBarsPadding(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Translation models", style = MaterialTheme.typography.headlineSmall)
            Text("Download language packs once, then translate offline. Each pack is about 30 MB. English is built in; pairs share their language packs.")
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(wifiOnly, { wifiOnly = it }, enabled = models.busy == null)
                Text("Download over Wi-Fi only")
            }
            if (models.checking) LinearProgressIndicator(Modifier.fillMaxWidth())
            TranslationCatalog.pairs.forEach { pair ->
                val ready = models.downloaded.containsAll(pair.packs)
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(pair.label, style = MaterialTheme.typography.titleSmall)
                        Text(if (ready) "Available offline" else "Needs: ${(pair.packs - models.downloaded).joinToString { TranslationCatalog.name(it) }}", style = MaterialTheme.typography.bodySmall)
                    }
                    if (models.busy == pair.id) CircularProgressIndicator(Modifier.padding(12.dp).size(24.dp), strokeWidth = 2.dp)
                    else TextButton(onClick = { vm.download(pair, wifiOnly) }, enabled = !ready && models.busy == null && !models.checking) { Text(if (ready) "Ready" else "Download") }
                }
            }
            if (models.busy != null) Text(if (models.busy!!.startsWith("delete-")) "Removing pack…" else "Downloading… This can wait for Wi-Fi. You can close this sheet.", style = MaterialTheme.typography.bodySmall)
            HorizontalDivider()
            Text("Downloaded packs", style = MaterialTheme.typography.titleMedium)
            if (models.downloaded.isEmpty() && !models.checking) Text("No language packs downloaded yet.")
            models.downloaded.sortedBy { TranslationCatalog.name(it) }.forEach { code ->
                val active = code in (session.active?.packs ?: emptySet())
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(TranslationCatalog.name(code))
                        if (active) Text("In use · deactivate before removing", style = MaterialTheme.typography.bodySmall)
                    }
                    IconButton(onClick = { removing = code }, enabled = !active && models.busy == null && !models.checking) {
                        Icon(Icons.Outlined.DeleteOutline, "Remove ${TranslationCatalog.name(code)} pack")
                    }
                }
            }
            models.error?.let { Text(it, color = MaterialTheme.colorScheme.error); TextButton(onClick = { vm.clearError(); vm.refresh() }) { Text("Check again") } }
            TextButton(onClick = { about = true }) { Text("About Google Translate") }
        }
    }
    removing?.let { code ->
        AlertDialog(onDismissRequest = { removing = null }, title = { Text("Remove ${TranslationCatalog.name(code)} pack?") },
            text = { Text("These options will need another download: ${TranslationCatalog.pairs.filter { code in it.packs }.joinToString { it.label }}. Other packs and your original transcripts remain available.") },
            confirmButton = { TextButton(onClick = { vm.deletePack(code); removing = null }) { Text("Remove") } },
            dismissButton = { TextButton(onClick = { removing = null }) { Text("Cancel") } })
    }
    if (about) TranslationAboutDialog { about = false }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ActiveTranslatorSheet(vm: TranslationViewModel, onManage: () -> Unit, onDismiss: () -> Unit, onActivated: () -> Unit) {
    val models by vm.models.collectAsStateWithLifecycle()
    val session by vm.session.state.collectAsStateWithLifecycle()
    var selected by rememberSaveable { mutableStateOf(session.active?.id ?: "de-en") }
    LaunchedEffect(Unit) { vm.refresh() }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(24.dp).navigationBarsPadding(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Translate segments", style = MaterialTheme.typography.headlineSmall)
            Text(session.active?.let { "Active: ${it.label}" } ?: "Choose a downloaded language pair.")
            Text("Only the segment you tap is translated. Results stay here until you leave this note. Changing the target language clears them.", style = MaterialTheme.typography.bodyMedium)
            if (models.checking) LinearProgressIndicator(Modifier.fillMaxWidth())
            TranslationCatalog.pairs.filter { models.downloaded.containsAll(it.packs) }.forEach { pair ->
                FilterChip(selected == pair.id, { selected = pair.id }, label = { Text(pair.label) }, modifier = Modifier.fillMaxWidth())
            }
            if (!models.checking && TranslationCatalog.pairs.none { models.downloaded.containsAll(it.packs) }) Text("Download a language pair to get started.")
            val pair = TranslationCatalog.pairs.first { it.id == selected }
            Button(onClick = { if (vm.activate(pair)) onActivated() }, enabled = !models.checking && models.busy == null && models.downloaded.containsAll(pair.packs), modifier = Modifier.fillMaxWidth()) { Text("Activate ${pair.label}") }
            if (session.active != null) OutlinedButton(onClick = { vm.session.deactivate(); onDismiss() }, modifier = Modifier.fillMaxWidth()) { Text("Deactivate translator") }
            Text("Deactivation releases the translator after any current request finishes. Displayed translations remain readable.", style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = onManage) { Text("Manage translation models") }
            models.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }
}

@Composable
internal fun SegmentTranslateButton(id: String, pending: Boolean, enabled: Boolean, expanded: Boolean, onClick: () -> Unit) {
    Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
        IconButton(onClick = onClick, enabled = enabled && !pending, modifier = Modifier.testTag("segment-translate-$id")) {
            Icon(Icons.Outlined.Translate, if (pending) "Translation queued or running" else if (expanded) "Hide translation" else "Translate with Google")
        }
        if (pending) CircularProgressIndicator(Modifier.size(40.dp).testTag("translation-progress-$id"), strokeWidth = 2.dp)
    }
}

@Composable
internal fun TranslationAttribution() {
    Image(painterResource(R.drawable.powered_by_google_translate), "Powered by Google Translate", modifier = Modifier.height(5.dp))
}

@Composable
private fun TranslationAboutDialog(onDismiss: () -> Unit) {
    val uri = LocalUriHandler.current
    AlertDialog(onDismissRequest = onDismiss, title = { Text("About translations") },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Google Translate powers on-device translation through ML Kit. Transcript text is processed on your phone. German to Bengali and Hindi uses English as an intermediate language, which can affect accuracy.")
            Text("This service may contain translations powered by Google. Google disclaims all warranties related to the translations, express or implied, including any warranties of accuracy, reliability, and any implied warranties of merchantability, fitness for a particular purpose and noninfringement.", style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = { uri.openUri("https://cloud.google.com/translate") }) { Text("Google Translate information") }
        } }, confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } })
}
