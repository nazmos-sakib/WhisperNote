package app.naz.whispernote

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AppInformationScreen(privacy: Boolean, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val context = LocalContext.current
    val uri = LocalUriHandler.current
    var license by remember(privacy) { mutableStateOf<String?>(null) }
    val version = remember {
        @Suppress("DEPRECATION")
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: ""
    }
    Scaffold(topBar = {
        TopAppBar(title = { Text(if (privacy) "Privacy" else "Information") }, navigationIcon = {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back") }
        })
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            if (privacy) {
                InfoSection("Processing on your device", "WhisperNote transcribes audio on your device using whisper.cpp. Built-in translation uses Google ML Kit on-device processing. ML Kit does not send the translation input text or translated output to Google servers.")
                InfoSection("What is stored", "Notes, transcripts, labels, settings and listening positions are stored in the app’s private storage. Imported audio may be accessed through your selected file provider or copied into private storage. Downloaded models are stored on the device. Translations are temporary: leaving a note or losing the app process clears them; dismissing a word lookup discards its result.")
                InfoSection("Network connections", "Whisper models are downloaded from Hugging Face. Translation packs come from Google. Downloading an audio URL contacts the host you choose. These providers receive network requests, including your IP address. A file provider you select may also use its own cloud service.")
                InfoSection("Google ML Kit diagnostics", "ML Kit sends usage and performance metrics to Google, including device and app information, identifiers, configured languages, errors and model events. It may contact Google for updates and compatibility information. These diagnostics are separate from your translation text and results.")
                TextButton(onClick = { uri.openUri("https://developers.google.com/ml-kit/terms") }) { Text("ML Kit terms and privacy") }
                TextButton(onClick = { uri.openUri("https://policies.google.com/privacy") }) { Text("Google Privacy Policy") }
                InfoSection("Sharing is your choice", "Share and Translate with another app send the selected text to the app you choose. Exported transcripts contain note text; complete-note archives include audio and transcript. The receiving app or chosen storage provider handles that data under its own policies. Copy actions place text on the system clipboard.")
                InfoSection("Deletion and backups", "Delete a note to remove its saved transcript. You can also delete audio owned by WhisperNote; external source files are not deleted. Exported or shared copies remain wherever you saved or sent them. Remove downloaded packs from the model screens. Automatic backup and device migration of private app data are disabled. Uninstalling or clearing app storage removes private notes and files. Save complete-note exports somewhere separate to keep a backup.")
                InfoSection("Background activity", "Audio can continue with notification and lock-screen controls until you pause or stop it. Translation models remain active for up to five continuous minutes in the background before deactivation; Android may reclaim the process sooner. Transcription jobs can continue in the foreground service.")
            } else {
                Text("WhisperNote", style = MaterialTheme.typography.headlineMedium)
                Text("Version $version", style = MaterialTheme.typography.labelLarge)
                Text("An offline-first audio notebook for transcription, translation and listening.")
                InfoSection("Transcribe and listen", "Download a Whisper model, import audio and read timestamped segments. Edit or retranscribe individual segments, organize notes with labels, and export text or complete notes. Playback supports speed controls, remembered positions and background listening.")
                InfoSection("Translate", "Download a language pair and activate it from the note toolbar. Use a segment’s translation button for inline results, or select a word or phrase and choose Translate for a quick lookup. You can copy the result or try another translation app.")
                InfoSection("Accuracy", "Speech recognition and machine translation can make mistakes, omit words or produce incorrect meanings. Check important passages against the original audio. Word lookups are translations, not complete dictionary definitions.")
                InfoSection("Code and licenses", "Original WhisperNote code is licensed under MIT. whisper.cpp and other third-party components retain their own licenses and terms. Google ML Kit and Google Translate branding are governed by Google’s terms.")
                TextButton(onClick = { license = "LICENSE.md" }) { Text("WhisperNote MIT license") }
                TextButton(onClick = { license = "whisper-LICENSE.txt" }) { Text("whisper.cpp license") }
                InfoSection("Translation attribution", "Google Translate powers built-in translation through ML Kit.")
                TranslationAttribution()
                Text("This service may contain translations powered by Google. Google disclaims all warranties related to the translations, express or implied, including any warranties of accuracy, reliability, and any implied warranties of merchantability, fitness for a particular purpose and noninfringement.", style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = { uri.openUri("https://developers.google.com/ml-kit/language/translation") }) { Text("About Google ML Kit translation") }
            }
        }
    }
    license?.let { file ->
        val text = remember(file) { context.assets.open("legal/$file").bufferedReader().use { it.readText() } }
        AlertDialog(onDismissRequest = { license = null }, title = { Text("License") },
            text = { Text(text, modifier = Modifier.verticalScroll(rememberScrollState()), style = MaterialTheme.typography.bodySmall) },
            confirmButton = { TextButton(onClick = { license = null }) { Text("Close") } })
    }
}

@Composable
private fun InfoSection(title: String, body: String) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(body, style = MaterialTheme.typography.bodyMedium)
    }
}
