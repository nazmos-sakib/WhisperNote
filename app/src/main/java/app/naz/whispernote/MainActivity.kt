package app.naz.whispernote

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.staggeredgrid.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.core.content.FileProvider
import app.naz.whispernote.core.*
import app.naz.whispernote.ui.theme.WhisperNoteTheme
import java.io.File
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.launch

class MainActivity: ComponentActivity() {
    private val opened= kotlinx.coroutines.flow.MutableStateFlow<String?>(null)
    override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState); opened.value=intent.getStringExtra("noteId"); enableEdgeToEdge(); setContent { WhisperNoteTheme { val id by opened.collectAsState(); WhisperNote(id) } } }
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); opened.value=intent.getStringExtra("noteId") }
}
@OptIn(ExperimentalMaterial3Api::class)
@Composable fun WhisperNote(opened: String?,vm: NotesViewModel=viewModel()) {
    val notes by vm.notes.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    var query by rememberSaveable { mutableStateOf("") }
    var importing by remember { mutableStateOf(false) }; var modelSheet by remember { mutableStateOf(false) }
    var model by rememberSaveable { mutableStateOf(vm.models.preferredModel) }
    var url by rememberSaveable { mutableStateOf("") }
    val context=LocalContext.current
    val picker=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let { vm.import(it,model) }; importing=false }
    val permission=rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    LaunchedEffect(opened) { if(opened!=null) selected=opened }
    val note=notes.firstOrNull { it.id==selected }
    androidx.activity.compose.BackHandler(selected!=null) { selected=null }
    if(note!=null) { Detail(note,query,vm) { selected=null } }
    else Scaffold(
        topBar={ TopAppBar(title={ Text("WhisperNote",fontWeight=FontWeight.Bold) },actions={ TextButton(onClick={modelSheet=true}) { Icon(Icons.Outlined.Memory,null); Spacer(Modifier.width(6.dp)); Text("Models") } }) },
        floatingActionButton={ ExtendedFloatingActionButton(onClick={importing=true},icon={Icon(Icons.Outlined.Add,null)},text={Text("New transcription")}) }
    ) { padding -> Column(Modifier.fillMaxSize().padding(padding).padding(horizontal=20.dp)) {
        Text("A little space for every spoken thought.",style=MaterialTheme.typography.bodyLarge,color=MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(20.dp))
        OutlinedTextField(query,{query=it},Modifier.fillMaxWidth(),placeholder={Text("Search your notes")},leadingIcon={Icon(Icons.Outlined.Search,null)},singleLine=true,shape=RoundedCornerShape(28.dp))
        Spacer(Modifier.height(20.dp))
        val filtered=notes.filter { query.isBlank() || it.title.contains(query,true) || it.segments.any { s -> s.text.contains(query,true) } }
        Text(if(query.isBlank()) "YOUR LIBRARY · ${notes.size}" else "${filtered.size} RESULTS",style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(12.dp))
        if(filtered.isEmpty()) Box(Modifier.fillMaxSize().padding(bottom=100.dp),contentAlignment=Alignment.Center) { Column(horizontalAlignment=Alignment.CenterHorizontally) {
            Icon(Icons.Outlined.GraphicEq,null,Modifier.size(56.dp),tint=MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(16.dp)); Text(if(query.isBlank()) "Your words, beautifully kept." else "No matching notes",style=MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(8.dp)); Text(if(query.isBlank()) "Import audio to create your first note.\nTranscribed on your device. Private by design." else "Try a different title or phrase.",color=MaterialTheme.colorScheme.onSurfaceVariant)
        } } else LazyVerticalStaggeredGrid(StaggeredGridCells.Adaptive(170.dp),horizontalArrangement=Arrangement.spacedBy(12.dp),verticalItemSpacing=12.dp,contentPadding=PaddingValues(bottom=100.dp)) {
            items(filtered,key={it.id}) { n -> NoteCard(n) {selected=n.id} }
        }
    } }
    if(importing) ModalBottomSheet(onDismissRequest={importing=false}) { Column(Modifier.verticalScroll(rememberScrollState()).padding(24.dp).imePadding().navigationBarsPadding(),verticalArrangement=Arrangement.spacedBy(16.dp)) {
        Text("Turn audio into a note",style=MaterialTheme.typography.headlineSmall)
        Text("Your audio stays on this device. Download a multilingual model once, then transcribe offline.",color=MaterialTheme.colorScheme.onSurfaceVariant)
        ModelPicker(model) { model=it; vm.models.preferredModel=it }
        if(!vm.models.ready(model)) Text("Download ${ModelCatalog.get(model).displayName} in Models before transcription.",color=MaterialTheme.colorScheme.primary)
        Button(onClick={if(android.os.Build.VERSION.SDK_INT>=33) permission.launch(android.Manifest.permission.POST_NOTIFICATIONS); picker.launch(arrayOf("audio/*","video/mp4","application/ogg"))},Modifier.fillMaxWidth()) { Icon(Icons.Outlined.AudioFile,null); Spacer(Modifier.width(8.dp)); Text("Choose audio file") }
        HorizontalDivider()
        OutlinedTextField(url,{url=it},Modifier.fillMaxWidth(),label={Text("Direct HTTPS audio URL")},singleLine=true)
        OutlinedButton(onClick={vm.importUrl(url.trim(),model); importing=false; url=""},enabled=url.isNotBlank(),modifier=Modifier.fillMaxWidth()) { Text("Download audio") }
    } }
    if(modelSheet) ModalBottomSheet(onDismissRequest={modelSheet=false}) { Models(vm) }
    if(error!=null) AlertDialog(onDismissRequest={vm.error.value=null},title={Text("Something went wrong")},text={Text(error!!)},confirmButton={TextButton(onClick={vm.error.value=null}) {Text("OK")}})
}
@Composable fun NoteCard(n: Note,onClick:()->Unit) {
    val colors=listOf(MaterialTheme.colorScheme.secondaryContainer,MaterialTheme.colorScheme.tertiaryContainer,MaterialTheme.colorScheme.surfaceContainerHigh)
    Card(onClick=onClick,shape=RoundedCornerShape(20.dp),colors=CardDefaults.cardColors(containerColor=colors[(n.id.hashCode() and Int.MAX_VALUE)%colors.size]),modifier=Modifier.fillMaxWidth().heightIn(min=175.dp,max=320.dp)) {
        Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            Icon(Icons.Outlined.GraphicEq,null,tint=MaterialTheme.colorScheme.primary,modifier=Modifier.size(22.dp))
            Text(n.title.ifBlank { "Untitled note" },fontWeight=FontWeight.SemiBold,maxLines=2,overflow=TextOverflow.Ellipsis)
            if(n.busy) { Text(n.status+if(n.progress>0) " · ${n.progress}%" else "…",style=MaterialTheme.typography.bodySmall); LinearProgressIndicator(modifier=Modifier.fillMaxWidth()) }
            if(n.resumable) Text(if(n.checkpointMs>0) "Incomplete · saved through ${timestamp(n.checkpointMs)}" else "Incomplete · tap to retry",style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.error)
            if(n.segments.isNotEmpty() || !n.busy) Text(n.segments.joinToString(" ") {it.text}.ifBlank { if(n.resumable) n.error ?: "Tap to retry" else "No speech detected" },maxLines=4,overflow=TextOverflow.Ellipsis,style=MaterialTheme.typography.bodyMedium)
            Text("${timestamp(n.duration)}  ·  ${n.language.uppercase().ifBlank {if(n.source==null) "FILE" else "URL"}}",style=MaterialTheme.typography.labelSmall)
            Text(DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(n.created)),style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
/** Two independent choices avoid overflowing a phone screen with six model chips. */
@Composable fun ModelPicker(selected: String, onSelect: (String) -> Unit) {
    val model=ModelCatalog.get(selected)
    Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
        Text("Model",style=MaterialTheme.typography.labelLarge)
        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            ModelCatalog.families.forEach { family ->
                FilterChip(model.family==family,{onSelect(ModelCatalog.variant(family,model.quantized).id)},label={Text(family.replaceFirstChar {it.uppercase()})})
            }
        }
        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            FilterChip(!model.quantized,{onSelect(ModelCatalog.variant(model.family,false).id)},label={Text("Standard")})
            FilterChip(model.quantized,{onSelect(ModelCatalog.variant(model.family,true).id)},label={Text("Compact (Q5)")})
        }
        Text("${model.displayName} · ${model.size}",style=MaterialTheme.typography.bodyMedium)
        Text(if(model.quantized) "Smaller download and memory use. Recognition may differ slightly from Standard." else "Original model precision. Uses more storage and memory.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
@Composable fun Models(vm: NotesViewModel) {
    val progress by ModelDownloads.progress.collectAsStateWithLifecycle(); val error by ModelDownloads.error.collectAsStateWithLifecycle()
    val context=LocalContext.current
    Column(Modifier.verticalScroll(rememberScrollState()).padding(24.dp).imePadding().navigationBarsPadding(),verticalArrangement=Arrangement.spacedBy(16.dp)) {
        Text("An offline voice for your notes",style=MaterialTheme.typography.headlineSmall)
        Text("All models support German, English, and more. Larger models can improve accuracy but need more time and memory. Compact Q5 models use less storage and memory; speed and accuracy vary by device and audio.")
        vm.models.models.forEach { m ->
            val ready=remember(progress) { vm.models.ready(m.id) }
            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) { Text(m.displayName,fontWeight=FontWeight.Bold); Text("${m.size} · ${if(ready) "Available offline" else "One-time download"}",style=MaterialTheme.typography.bodySmall) }
                if(progress?.first==m.id) { Text(if(progress!!.second<0) "Downloading…" else if(progress!!.second==100) "Verifying…" else "${progress!!.second}%") }
                else TextButton(onClick={ModelDownloads.error.value=null; ProcessingService.start(context,m.id,true)},enabled=!ready && progress==null) {Text(if(ready) "Ready" else "Download")}
            }
        }
        error?.let { Text(it,color=MaterialTheme.colorScheme.error) }
    }
}
