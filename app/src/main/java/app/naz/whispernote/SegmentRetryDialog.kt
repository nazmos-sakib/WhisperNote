package app.naz.whispernote

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.naz.whispernote.core.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SegmentRetryDialog(note: Note, target: Segment?, draft: SegmentRetry?, vm: NotesViewModel, dismiss: ()->Unit) {
    val notificationPermission=androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) {}
    val session by vm.transcription.state.collectAsState()
    var changing by rememberSaveable {mutableStateOf(false)}
    var replacement by rememberSaveable {mutableStateOf<String?>(null)}
    LaunchedEffect(session) { if(replacement!=null && session.operation==null && session.model==replacement) {changing=false;replacement=null} }
    val segment=draft?.original ?: target ?: return
    var model by rememberSaveable(segment.id) { mutableStateOf(ModelCatalog.models.firstOrNull {it.id==note.model}?.id ?: "base") }
    var language by rememberSaveable(segment.id) {mutableStateOf(note.language)}
    val downloads by ModelDownloads.progress.collectAsState()
    val modelReady=remember(model,downloads) {vm.models.ready(model)}
    ModalBottomSheet(onDismissRequest=dismiss) {
        Column(Modifier.padding(24.dp).navigationBarsPadding()) {
        Text("Retranscribe ${timestamp(segment.start)}–${timestamp(segment.end)}",style=MaterialTheme.typography.titleLarge)
        Column(Modifier.weight(1f,fill=false).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            if(draft==null) {
                Text("Only this audio range is sent to the local model. Your current text stays unchanged until you accept a suggestion.")
                if(session.operation!=null) {
                    Text(session.operation!!)
                    CircularProgressIndicator()
                } else if(session.model!=null && !changing) {
                    ModelInformation(session.model!!)
                    TextButton(onClick={changing=true}) {Text("Change Model")}
                } else ModelPicker(model) {model=it}
                OutlinedTextField(language,{language=it.lowercase().trim()},label={Text("Language code (de, en…) ")},supportingText={Text("Leave blank for automatic detection.")},singleLine=true)
                if(!modelReady && (session.model==null || changing)) Text("Download this model from Models in the drawer first.",color=MaterialTheme.colorScheme.error)
                Text("Retrying with the same settings may produce the same words. Audio preparation may also need to decode earlier parts of the recording.",style=MaterialTheme.typography.bodySmall)
            } else if(draft.busy) {
                Text("${draft.status}…")
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text("You can close this sheet or leave the note. The suggestion will be saved for review.")
                TextButton(onClick={vm.discardRetry(note.id);dismiss()}) {Text("Cancel retranscription")}

            } else if(draft.status=="Failed") {
                Text(draft.error ?: "Retranscription failed.",color=MaterialTheme.colorScheme.error)
                Text("Your original text is unchanged.")
                TextButton(onClick={vm.discardRetry(note.id)}) {Text("Choose settings and try again")}
            } else {
                Text("Original",style=MaterialTheme.typography.titleSmall)
                Text(segment.text.ifBlank {"No transcript"})
                Text("Suggestion · ${ModelCatalog.models.firstOrNull {it.id==draft.model}?.displayName ?: draft.model}",style=MaterialTheme.typography.titleSmall)
                if(draft.result.isEmpty()) Text("No speech recognized. Keeping the original text is recommended.")
                draft.result.forEach {Text("${timestamp(it.start)}–${timestamp(it.end)}\n${it.text}")}
                Text("Replace text keeps the original timestamps. Use new segments keeps the model's boundaries and adds empty placeholders for any gaps.",style=MaterialTheme.typography.bodySmall)
                val unchanged=note.segments.any {it==draft.original}
                if(!unchanged) Text("This segment was edited after the suggestion started. Discard this suggestion to keep your edits and try again.",color=MaterialTheme.colorScheme.error)
                Button(onClick={vm.acceptRetry(note.id,false);dismiss()},enabled=unchanged) {Text("Replace text")}
                OutlinedButton(onClick={vm.acceptRetry(note.id,true);dismiss()},enabled=unchanged && draft.result.isNotEmpty()) {Text("Use new segments")}
                TextButton(onClick={vm.discardRetry(note.id);dismiss()}) {Text("Keep original")}
            }
        }
        if(draft==null) {
            Button(onClick={
                if(changing) {replacement=model;vm.loadModel(model)}
                else {
                    if(android.os.Build.VERSION.SDK_INT>=33) notificationPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                    vm.retrySegment(note.id,segment.id,session.model ?: model,language)
                }
            },enabled=session.operation==null && (if(changing || session.model==null) modelReady else true)) {
                Text(if(changing) "Load selected model" else if(session.model!=null) "Start" else "Load model and re-transcribe")
            }
            TextButton(onClick=dismiss) {Text("Cancel")}
        } else TextButton(onClick=dismiss) {Text("Close")}
        }
    }
}
