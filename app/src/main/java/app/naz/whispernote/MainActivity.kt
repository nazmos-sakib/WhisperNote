package app.naz.whispernote

import android.content.Intent
import android.os.Bundle
import android.net.Uri
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.staggeredgrid.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.automirrored.outlined.*
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
import app.naz.whispernote.core.*
import app.naz.whispernote.ui.theme.WhisperNoteTheme
import java.text.SimpleDateFormat
import java.util.Locale
import androidx.compose.ui.text.style.TextAlign
import java.util.Date
import kotlinx.coroutines.launch

class MainActivity: ComponentActivity() {
    private val opened= kotlinx.coroutines.flow.MutableStateFlow<String?>(null)
    private val openGeneration= kotlinx.coroutines.flow.MutableStateFlow(0L)
    private val incomingMime=kotlinx.coroutines.flow.MutableStateFlow<String?>(null)
    private val incoming= kotlinx.coroutines.flow.MutableStateFlow<Uri?>(null)
    @Suppress("DEPRECATION")
    private fun receive(intent: Intent) {
        incomingMime.value=intent.type
        opened.value=intent.getStringExtra("noteId")
        if (opened.value != null) openGeneration.value++
        incoming.value=when(intent.action) {
            Intent.ACTION_VIEW -> intent.data
            Intent.ACTION_SEND -> intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
            else -> null
        }?.takeIf { it.scheme=="content" }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState); receive(intent);
        applyKeepAwake()
        enableEdgeToEdge()
        setContent { WhisperNoteTheme {
            val id by opened.collectAsState(); val uri by incoming.collectAsState()
            val generation by openGeneration.collectAsState()
            val mime by incomingMime.collectAsState()
            WhisperNote(id,openRequest=generation,incoming=uri,incomingMime=mime,consumeIncoming={incoming.value=null;setIntent(Intent(this,MainActivity::class.java))})
        } }
    }
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); setIntent(intent); receive(intent) }
    override fun onStart() {
        super.onStart()
        androidx.lifecycle.ViewModelProvider(this)[TranslationViewModel::class.java].onForeground()
    }
    override fun onResume() {
        super.onResume()
        applyKeepAwake()
    }
    override fun onStop() {
        super.onStop()
        if (!isChangingConfigurations) androidx.lifecycle.ViewModelProvider(this)[TranslationViewModel::class.java].onBackground()
    }
    fun applyKeepAwake() {
        if(getSharedPreferences("display",MODE_PRIVATE).getBoolean("keep-awake",false)) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }
    override fun onPause() {
        super.onPause()
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }
}
@OptIn(ExperimentalMaterial3Api::class)
@Composable fun WhisperNote(opened: String?,vm: NotesViewModel=viewModel(), incoming: Uri? = null, consumeIncoming: () -> Unit = {}, openRequest: Long = 0, incomingMime: String? = null) {
    val translationVm: TranslationViewModel = viewModel()
    var translationModels by remember { mutableStateOf(false) }
    var informationPage by rememberSaveable { mutableStateOf<String?>(null) }
    val notes by vm.notes.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    val labels by vm.labels.collectAsStateWithLifecycle()
    val transfer by vm.transfer.collectAsStateWithLifecycle()
    val imported by vm.importedNote.collectAsStateWithLifecycle()
    var labelFilter by rememberSaveable { mutableStateOf<String?>(null) }
    var createLabel by remember { mutableStateOf(false) }
    var renameLabel by remember { mutableStateOf<String?>(null) }
    var deleteLabel by remember { mutableStateOf<String?>(null) }
    val drawer=rememberDrawerState(DrawerValue.Closed)
    val scope=rememberCoroutineScope()
    LaunchedEffect(labels) { if(labelFilter!=null && labelFilter!="" && labelFilter !in labels) labelFilter=null }
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    var query by rememberSaveable { mutableStateOf("") }
    var importing by remember { mutableStateOf(false) }; var modelSheet by remember { mutableStateOf(false) }
    var model by rememberSaveable { mutableStateOf(vm.models.preferredModel) }
    var url by rememberSaveable { mutableStateOf("") }
    val context=LocalContext.current
    val playbackController = (context.applicationContext as WhisperApp).playback
    val listening by playbackController.state.collectAsStateWithLifecycle()
    val picker=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let { vm.import(it,model,labelFilter?.takeIf { it.isNotEmpty() }) }; importing=false }
    val backupSave=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) {it?.let {vm.libraryBackup(it,false)}}
    val backupOpen=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) {it?.let {vm.libraryBackup(it,true)}}
    val recording by RecordingService.state.collectAsStateWithLifecycle()
    val recordingError by RecordingService.error.collectAsStateWithLifecycle()
    val micPermission=rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if(granted) runCatching {RecordingService.start(context,model,labelFilter?.takeIf {it.isNotEmpty()})}.onFailure {vm.error.value=it.message}
        else vm.error.value="Microphone permission is needed to record audio."
    }
    val archivePicker=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let(vm::importArchive) }
    val permission=rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    LaunchedEffect(opened,openRequest) { if(opened!=null) selected=opened }
    LaunchedEffect(imported) { imported?.let { selected=it; vm.importedNote.value=null } }
    val note=notes.firstOrNull { it.id==selected }
    androidx.activity.compose.BackHandler(selected!=null) { selected=null }
    if(informationPage != null) AppInformationScreen(privacy = informationPage == "privacy", onBack = { informationPage = null })
    else if(note!=null) { Detail(note,query,vm,translationVm) { selected=null } }
    else ModalNavigationDrawer(drawerState=drawer,drawerContent={
        ModalDrawerSheet {
            Column(Modifier.fillMaxHeight()) {
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(12.dp)) {
                Text("WhisperNote",style=MaterialTheme.typography.headlineSmall,modifier=Modifier.padding(16.dp))
                NavigationDrawerItem(label={Text("All notes · ${notes.size}")},selected=labelFilter==null,onClick={labelFilter=null;scope.launch {drawer.close()}},icon={Icon(Icons.AutoMirrored.Outlined.Notes,null)})
                NavigationDrawerItem(label={Text("Unlabelled · ${notes.count { it.labels.isEmpty() }}")},selected=labelFilter=="",onClick={labelFilter="";scope.launch {drawer.close()}},icon={Icon(Icons.AutoMirrored.Outlined.LabelOff,null)})
                HorizontalDivider(Modifier.padding(vertical=12.dp))
                Text("LABELS",modifier=Modifier.padding(16.dp),style=MaterialTheme.typography.labelMedium)
                labels.forEach { label ->
                    NavigationDrawerItem(label={Text("$label · ${notes.count {label in it.labels}}")},selected=labelFilter==label,onClick={labelFilter=label;scope.launch {drawer.close()}},icon={Icon(Icons.AutoMirrored.Outlined.Label,null)},badge={
                        var menu by remember { mutableStateOf(false) }
                        Box { IconButton(onClick={menu=true}) {Icon(Icons.Outlined.MoreVert,"Manage $label")}
                            DropdownMenu(menu,{menu=false}) {
                                DropdownMenuItem(text={Text("Rename")},onClick={menu=false;renameLabel=label})
                                DropdownMenuItem(text={Text("Delete label")},onClick={menu=false;deleteLabel=label})
                            }
                        }
                    })
                }
                NavigationDrawerItem(label={Text("Create label")},selected=false,onClick={createLabel=true},icon={Icon(Icons.Outlined.Add,null)})
                HorizontalDivider(Modifier.padding(vertical=12.dp))
                var keepAwake by remember {mutableStateOf(context.getSharedPreferences("display",android.content.Context.MODE_PRIVATE).getBoolean("keep-awake",false))}
                Row(verticalAlignment=Alignment.CenterVertically) {
                    Switch(keepAwake,{keepAwake=it;context.getSharedPreferences("display",android.content.Context.MODE_PRIVATE).edit().putBoolean("keep-awake",it).apply();(context as? MainActivity)?.applyKeepAwake()})
                    Text("Keep screen awake")
                }
                NavigationDrawerItem(label={Text("Translation models")},selected=false,onClick={scope.launch {drawer.close();translationModels=true}},icon={Icon(Icons.Outlined.Translate,null)})
                NavigationDrawerItem(label={Text("Models")},selected=false,onClick={scope.launch {drawer.close();modelSheet=true}},icon={Icon(Icons.Outlined.Memory,null)})
                NavigationDrawerItem(label={Text("Back up library")},selected=false,onClick={if(transfer==null) backupSave.launch("WhisperNote-library.zip")})
                NavigationDrawerItem(label={Text("Restore library")},selected=false,onClick={if(transfer==null) backupOpen.launch(arrayOf("application/zip","application/octet-stream"))})
                NavigationDrawerItem(label={Text("Import complete note")},selected=false,onClick={if(transfer==null) {scope.launch {drawer.close()};archivePicker.launch(arrayOf("*/*"))}},icon={Icon(Icons.Outlined.FileOpen,null)})
            }
            HorizontalDivider()
            Column(Modifier.padding(horizontal=12.dp, vertical=8.dp)) {
                NavigationDrawerItem(label={Text("Privacy")}, selected=false,
                    onClick={scope.launch {drawer.close();informationPage="privacy"}},
                    icon={Icon(Icons.Outlined.PrivacyTip,null)})
                NavigationDrawerItem(label={Text("Information")}, selected=false,
                    onClick={scope.launch {drawer.close();informationPage="information"}},
                    icon={Icon(Icons.Outlined.Info,null)})
            }
            }
        }
    }) { Scaffold(
        topBar={ TopAppBar(title={ Text(labelFilter?.ifEmpty { "Unlabelled" } ?: "WhisperNote",fontWeight=FontWeight.Bold) },navigationIcon={IconButton(onClick={scope.launch {drawer.open()}}) {Icon(Icons.Outlined.Menu,"Open navigation drawer")}}) },
        bottomBar={ listening?.let { audio ->
            ListeningMiniPlayer(audio, onOpen={selected=audio.noteId}, onToggle={playbackController.toggle()}, onStop={playbackController.stop()})
        } },
        floatingActionButton={ ExtendedFloatingActionButton(onClick={importing=true},icon={Icon(Icons.Outlined.Add,null)},text={Text("New transcription")}) }
    ) { padding -> Column(Modifier.fillMaxSize().padding(padding).padding(horizontal=20.dp)) {
        recording?.let { status -> Row(verticalAlignment=Alignment.CenterVertically) {
            Text(status,Modifier.weight(1f))
            TextButton(onClick={RecordingService.command(context,if(status=="Paused") "resume" else "pause")}) {Text(if(status=="Paused") "Resume" else "Pause")}
            TextButton(onClick={RecordingService.command(context,"stop")}) {Text("Stop & save")}
        } }
        Text("A little space for every spoken thought.",style=MaterialTheme.typography.bodyLarge,color=MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(20.dp))
        OutlinedTextField(query,{query=it},Modifier.fillMaxWidth(),placeholder={Text("Search your notes")},leadingIcon={Icon(Icons.Outlined.Search,null)},singleLine=true,shape=RoundedCornerShape(28.dp))
        Spacer(Modifier.height(20.dp))
        val filtered=notes.filter { (labelFilter==null || if(labelFilter=="") it.labels.isEmpty() else labelFilter in it.labels) }.filter { query.isBlank() || it.title.contains(query,true) || it.segments.any { s -> s.text.contains(query,true) } }
        Text(if(query.isBlank()) "YOUR LIBRARY · ${filtered.size}" else "${filtered.size} RESULTS",style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(12.dp))
        if(filtered.isEmpty()) Box(Modifier.fillMaxSize().padding(bottom=100.dp),contentAlignment=Alignment.Center) { Column(horizontalAlignment=Alignment.CenterHorizontally) {
            Icon(Icons.Outlined.GraphicEq,null,Modifier.size(56.dp),tint=MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(16.dp)); Text(if(query.isBlank()) "Your words, beautifully kept." else "No matching notes",style=MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(8.dp)); Text(if(query.isBlank()) "Import audio to create your first note.\nTranscribed on your device. Private by design." else "Try a different title or phrase.",color=MaterialTheme.colorScheme.onSurfaceVariant)
        } } else LazyVerticalStaggeredGrid(StaggeredGridCells.Adaptive(170.dp),horizontalArrangement=Arrangement.spacedBy(12.dp),verticalItemSpacing=12.dp,contentPadding=PaddingValues(bottom=100.dp)) {
            items(filtered,key={it.id}) { n -> NoteCard(n) {selected=n.id} }
        }
    } }
    }
    if(importing) ModalBottomSheet(onDismissRequest={importing=false}) { Column(Modifier.verticalScroll(rememberScrollState()).padding(24.dp).imePadding().navigationBarsPadding(),verticalArrangement=Arrangement.spacedBy(16.dp)) {
        Text("Turn audio into a note",style=MaterialTheme.typography.headlineSmall)
        Text("Your audio stays on this device. Download a multilingual model once, then transcribe offline.",color=MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedButton(onClick={importing=false;micPermission.launch(android.Manifest.permission.RECORD_AUDIO)},enabled=recording==null,modifier=Modifier.fillMaxWidth()) {Text("Record audio")}
        ModelPicker(model) { model=it; vm.models.preferredModel=it }
        if(!vm.models.ready(model)) Text("Download ${ModelCatalog.get(model).displayName} in Models before transcription.",color=MaterialTheme.colorScheme.primary)
        Button(onClick={if(android.os.Build.VERSION.SDK_INT>=33) permission.launch(android.Manifest.permission.POST_NOTIFICATIONS); picker.launch(arrayOf("audio/*","video/mp4","application/ogg"))},Modifier.fillMaxWidth()) { Icon(Icons.Outlined.AudioFile,null); Spacer(Modifier.width(8.dp)); Text("Choose audio file") }
        OutlinedButton(onClick={importing=false;archivePicker.launch(arrayOf("*/*"))},enabled=transfer==null,modifier=Modifier.fillMaxWidth()) {Text("Import complete note (.whispernote)")}
        HorizontalDivider()
        OutlinedTextField(url,{url=it},Modifier.fillMaxWidth(),label={Text("Direct HTTPS audio URL")},singleLine=true)
        OutlinedButton(onClick={vm.importUrl(url.trim(),model,labelFilter?.takeIf {it.isNotEmpty()}); importing=false; url=""},enabled=url.isNotBlank(),modifier=Modifier.fillMaxWidth()) { Text("Download audio") }
    } }
    if(incoming!=null) {
        val incomingType=remember(incoming,incomingMime) {incomingMime ?: runCatching {context.contentResolver.getType(incoming)}.getOrNull().orEmpty()}
        val isAudio=incomingType.startsWith("audio/") || incomingType=="video/mp4" || incomingType=="application/ogg"
        AlertDialog(onDismissRequest=consumeIncoming,title={Text(if(isAudio) "Transcribe shared audio?" else "Import complete note?")},
            text={Column {Text(if(isAudio) "Save a local audio note and add it to the transcription queue." else "Add a copy of this complete note to your library."); if(isAudio) ModelPicker(model) {model=it;vm.models.preferredModel=it}}},
            confirmButton={TextButton(onClick={if(isAudio) vm.import(incoming,model,labelFilter?.takeIf {it.isNotEmpty()}) else vm.importArchive(incoming);consumeIncoming()},enabled=transfer==null) {Text("Import")}},dismissButton={TextButton(onClick=consumeIncoming) {Text("Cancel")}})
    }
    recordingError?.let {message -> AlertDialog(onDismissRequest={RecordingService.error.value=null},title={Text("Recording")},text={Text(message)},confirmButton={TextButton(onClick={RecordingService.error.value=null}) {Text("OK")}})}

    if(createLabel) LabelNameDialog(null,{vm.createLabel(it);createLabel=false},{createLabel=false})
    renameLabel?.let { old -> LabelNameDialog(old,{vm.renameLabel(old,it); if(labelFilter==old) labelFilter=it;renameLabel=null},{renameLabel=null}) }
    deleteLabel?.let { label -> AlertDialog(onDismissRequest={deleteLabel=null},title={Text("Delete label?")},text={Text("Remove “$label” from all notes? Their other labels, text, and audio will be kept.")},confirmButton={TextButton(onClick={vm.deleteLabel(label);deleteLabel=null}) {Text("Delete label")}},dismissButton={TextButton(onClick={deleteLabel=null}) {Text("Cancel")}}) }
    if(transfer!=null) AlertDialog(onDismissRequest={},title={Text(transfer!!)},text={Column {LinearProgressIndicator(Modifier.fillMaxWidth());Text("Large audio files can take a little while.")}},confirmButton={})
    if(translationModels) TranslationModelsSheet(translationVm) { translationModels=false }
    if(modelSheet) ModalBottomSheet(onDismissRequest={modelSheet=false}) { Models(vm) }
    if(error!=null) AlertDialog(onDismissRequest={vm.error.value=null},title={Text("Something went wrong")},text={Text(error!!)},confirmButton={TextButton(onClick={vm.error.value=null}) {Text("OK")}})
}
@Composable fun NoteCard(n: Note,onClick:()->Unit) {
    val progressChange by PlaybackPositions.changes.collectAsStateWithLifecycle()
    val context=LocalContext.current
    val progress=remember(n.id,progressChange) {PlaybackPositions(context).progress(n.id)}
    val colors=listOf(MaterialTheme.colorScheme.secondaryContainer,MaterialTheme.colorScheme.tertiaryContainer,MaterialTheme.colorScheme.surfaceContainerHigh)
    Card(onClick=onClick,shape=RoundedCornerShape(20.dp),colors=CardDefaults.cardColors(containerColor=colors[(n.id.hashCode() and Int.MAX_VALUE)%colors.size]),modifier=Modifier.fillMaxWidth().heightIn(min=175.dp,max=360.dp)) {
        Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                Icon(Icons.Outlined.GraphicEq,null,tint=MaterialTheme.colorScheme.primary,modifier=Modifier.size(22.dp))
                Box(Modifier.weight(1f).padding(start=8.dp),contentAlignment=Alignment.CenterEnd) {
                    n.labels.takeIf {it.isNotEmpty()}?.joinToString(" · ")?.let { label ->
                        Text(label,maxLines=2,
                            overflow=TextOverflow.Ellipsis,textAlign=TextAlign.End,style=MaterialTheme.typography.labelSmall)
                    }
                }
            }
            if(progress.completed) Row(verticalAlignment=Alignment.CenterVertically) {
                Icon(Icons.Outlined.CheckCircle,"Listening completed",Modifier.size(18.dp))
                Text(" Completed",style=MaterialTheme.typography.labelSmall)
            } else if(progress.position>0 && n.duration>0) LinearProgressIndicator(
                progress={ (progress.position.toFloat()/n.duration).coerceIn(0f,1f) }, modifier=Modifier.fillMaxWidth())
            Text(n.title.ifBlank { "Untitled note" },fontWeight=FontWeight.SemiBold,maxLines=2,overflow=TextOverflow.Ellipsis)
            if(n.busy) { Text(n.status+if(n.progress>0) " · ${n.progress}%" else "…",style=MaterialTheme.typography.bodySmall); LinearProgressIndicator(modifier=Modifier.fillMaxWidth()) }
            if(n.resumable) Text(if(n.checkpointMs>0) "Incomplete · saved through ${timestamp(n.checkpointMs)}" else "Incomplete · tap to retry",style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.error)
            if(n.segments.isNotEmpty() || !n.busy) Text(n.segments.joinToString(" ") {it.text}.ifBlank { if(n.resumable) n.error ?: "Tap to retry" else "No speech detected" },maxLines=4,overflow=TextOverflow.Ellipsis,style=MaterialTheme.typography.bodyMedium)
            Text("${timestamp(n.duration)}  ·  ${n.language.uppercase().ifBlank {if(n.source==null) "FILE" else "URL"}}",style=MaterialTheme.typography.labelSmall)
            Text(remember(n.created) { SimpleDateFormat("d MMM yyyy",Locale.ENGLISH).format(Date(n.created)) },style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
/** Two independent choices avoid overflowing a phone screen with six model chips. */
@Composable fun ModelPicker(selected: String, onSelect: (String) -> Unit) {
    val model=ModelCatalog.get(selected)
    Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement=Arrangement.spacedBy(4.dp)) {
            listOf("Fast" to "tiny-q5_1","Balanced" to "base-q5_1","Accurate" to "small-q5_1").forEach { (label,id) ->
                FilterChip(selected==id,{onSelect(id)},label={Text(label)})
            }
        }
        Text("Larger models can take much longer on long recordings. Fast trades accuracy for speed.",style=MaterialTheme.typography.bodySmall)
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
        HorizontalDivider()
        Text("Skip silence (optional)",style=MaterialTheme.typography.titleMedium)
        Text("Speech detection can speed up recordings with long pauses. Quiet speech may be missed; turn this off if needed.",style=MaterialTheme.typography.bodySmall)
        val vadReady=remember(progress) {vm.models.vadReady}
        var skipSilence by remember {mutableStateOf(vm.models.skipSilence)}
        Row(verticalAlignment=Alignment.CenterVertically) {
            Switch(skipSilence,{skipSilence=it;vm.models.skipSilence=it},enabled=vadReady)
            Text("Enable for transcription")
        }
        if(!vadReady) TextButton(onClick={ProcessingService.start(context,"vad",true)},enabled=progress==null) {Text(if(progress?.first=="vad") "Downloading speech detector…" else "Download speech detector · 0.9 MB")}
        error?.let { Text(it,color=MaterialTheme.colorScheme.error) }
    }
}

@Composable
private fun ListeningMiniPlayer(audio: Listening, onOpen: () -> Unit, onToggle: () -> Unit, onStop: () -> Unit) {
    Surface(tonalElevation=3.dp, modifier=Modifier.fillMaxWidth()) {
        Row(Modifier.navigationBarsPadding().padding(horizontal=12.dp, vertical=4.dp), verticalAlignment=Alignment.CenterVertically) {
            Column(Modifier.weight(1f).clickable(onClick=onOpen).padding(8.dp)) {
                Text(audio.title, maxLines=1, overflow=TextOverflow.Ellipsis, style=MaterialTheme.typography.titleSmall)
                Text(audio.playback.error ?: "${timestamp(audio.playback.position)} · ${if(audio.playback.playing) "Playing" else if(!audio.playback.ready) "Preparing…" else "Paused"}", maxLines=1, overflow=TextOverflow.Ellipsis, style=MaterialTheme.typography.bodySmall)
            }
            IconButton(onClick=onToggle) { Icon(if(audio.playback.playing) Icons.Outlined.Pause else Icons.Outlined.PlayArrow, if(audio.playback.playing) "Pause audio" else "Resume audio") }
            IconButton(onClick=onStop) { Icon(Icons.Outlined.Close,"Stop audio") }
        }
    }
}
