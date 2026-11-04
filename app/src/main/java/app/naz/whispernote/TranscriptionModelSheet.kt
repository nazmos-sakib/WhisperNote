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
internal fun TranscriptionModelSheet(vm: NotesViewModel, dismiss: () -> Unit) {
    val state by vm.transcription.state.collectAsState()
    var selected by rememberSaveable {mutableStateOf(state.model ?: "base")}
    val downloads by ModelDownloads.progress.collectAsState()
    ModalBottomSheet(onDismissRequest=dismiss) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(24.dp).navigationBarsPadding(),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            Text("Transcription model",style=MaterialTheme.typography.titleLarge)
            if(state.operation!=null) {
                CircularProgressIndicator()
                Text(state.operation!!)
            } else if(state.model!=null) {
                ModelInformation(state.model!!)
                Button(onClick=vm::unloadModel) {Text("Unload Model")}
            } else {
                ModelPicker(selected) {selected=it}
                val ready=remember(selected,downloads) {vm.models.ready(selected)}
                if(!ready) Text("Download this model from Models first.")
                Button(onClick={vm.loadModel(selected)},enabled=ready) {Text("Load Model")}
            }
            TextButton(onClick=dismiss) {Text("Close")}
        }
    }
}

@Composable
internal fun ModelInformation(id: String) {
    val model=ModelCatalog.get(id)
    Text("${model.displayName} · ${model.size}",style=MaterialTheme.typography.titleMedium)
    Text(if(model.quantized) "Compact (Q5). Smaller download and memory use." else "Standard precision. Uses more storage and memory.")
}
