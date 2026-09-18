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

@Composable
internal fun SegmentRetryDialog(note: Note, target: Segment?, draft: SegmentRetry?, vm: NotesViewModel, dismiss: ()->Unit) {
    val segment=draft?.original ?: target ?: return
    var model by rememberSaveable(segment.id) { mutableStateOf(ModelCatalog.models.firstOrNull {it.id==note.model}?.id ?: "base") }
    var language by rememberSaveable(segment.id) {mutableStateOf(note.language)}
    val downloads by ModelDownloads.progress.collectAsState()
    val modelReady=remember(model,downloads) {vm.models.ready(model)}
    AlertDialog(onDismissRequest=dismiss,title={Text("Retranscribe ${timestamp(segment.start)}–${timestamp(segment.end)}")},text={
        Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            if(draft==null) {
                Text("Only this audio range is sent to the local model. Your current text stays unchanged until you accept a suggestion.")
                ModelPicker(model) {model=it}
                OutlinedTextField(language,{language=it.lowercase().trim()},label={Text("Language code (de, en…) ")},supportingText={Text("Leave blank for automatic detection.")},singleLine=true)
                if(!modelReady) Text("Download this model from Models in the drawer first.",color=MaterialTheme.colorScheme.error)
                Text("Retrying with the same settings may produce the same words. Audio preparation may also need to decode earlier parts of the recording.",style=MaterialTheme.typography.bodySmall)
            } else if(draft.busy) {
                Text("${draft.status} · ${draft.progress}%")
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text("You can close this dialog or leave the note. The suggestion will be saved for review.")
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
    },confirmButton={
        if(draft==null) TextButton(onClick={vm.retrySegment(note.id,segment.id,model,language)},enabled=modelReady) {Text("Generate suggestion")}
        else TextButton(onClick=dismiss) {Text("Close")}
    },dismissButton={if(draft==null) TextButton(onClick=dismiss) {Text("Cancel")}})
}
