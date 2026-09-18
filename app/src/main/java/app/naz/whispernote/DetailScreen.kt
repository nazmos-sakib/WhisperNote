package app.naz.whispernote

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Label
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.naz.whispernote.core.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.DateFormat
import java.util.Date
import kotlin.math.abs

/** Owns playback and persistence; the content below is also usable with deterministic playback in tests. */
@Composable
fun Detail(n: Note, query: String, vm: NotesViewModel, translationVm: TranslationViewModel = androidx.lifecycle.viewmodel.compose.viewModel(), back: () -> Unit) {
    val context = LocalContext.current
    val translationSession = translationVm.session
    val translations by translationSession.state.collectAsStateWithLifecycle()
    var translatorSheet by rememberSaveable(n.id) { mutableStateOf(false) }
    var translationModels by rememberSaveable(n.id) { mutableStateOf(false) }
    var pendingTranslationId by rememberSaveable(n.id) { mutableStateOf<String?>(null) }
    DisposableEffect(n.id, translationSession) {
        translationSession.enter(n.id)
        onDispose {
            // The activity ViewModel retains temporary results through rotation, never through navigation.
            var owner = context
            while (owner is android.content.ContextWrapper && owner !is android.app.Activity) owner = owner.baseContext
            if ((owner as? android.app.Activity)?.isChangingConfigurations != true) translationSession.leave()
        }
    }
    LaunchedEffect(n.segments) { translationSession.reconcile(n.segments) }
    if (translatorSheet && !translationModels) ActiveTranslatorSheet(translationVm,
        onManage = { translationModels = true },
        onDismiss = { translatorSheet = false; pendingTranslationId = null },
        onActivated = {
            translatorSheet = false
            n.segments.firstOrNull { it.id == pendingTranslationId }?.let { translationSession.request(it.id, it.text) }
            pendingTranslationId = null
        })
    if (translationModels) TranslationModelsSheet(translationVm) { translationModels = false }
    val positions = remember(context) { PlaybackPositions(context) }
    val player = remember(n.id, n.audio) {
        n.audio.takeIf { it.isNotEmpty() }?.let { audio ->
            AudioPlayer(context,audio,positions.get(n.id)) { position -> positions.save(n.id,position) }
        }
    }
    DisposableEffect(player) { onDispose { player?.release() } }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(player, lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP && player?.state?.value?.playing == true) player.toggle()
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    val emptyPlayback = remember { kotlinx.coroutines.flow.MutableStateFlow(Playback()) }
    val playback by (player?.state ?: emptyPlayback).collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var deleting by remember { mutableStateOf(false) }
    var deleteAudio by remember { mutableStateOf(true) }
    val drafts by vm.retryDrafts.collectAsStateWithLifecycle()
    val draft=drafts[n.id]
    var retrySegmentId by rememberSaveable(n.id) { mutableStateOf<String?>(null) }
    val labels by vm.labels.collectAsStateWithLifecycle()
    var choosingLabel by remember { mutableStateOf(false) }
    val archiveSave=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(NoteArchive.MIME)) { uri -> uri?.let {vm.exportArchive(n,it)} }
    var export by remember { mutableStateOf(false) }
    var exportFormat by rememberSaveable { mutableStateOf("txt") }
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        uri?.let { scope.launch(Dispatchers.IO) {
            try {
                requireNotNull(context.contentResolver.openOutputStream(it)).bufferedWriter().use { out -> out.write(Exporter.render(n, exportFormat)) }
            } catch (e: Exception) { vm.error.value = e.message }
        } }
    }

    TranscriptDetailContent(
        n = n, query = query, playback = playback,
        onTitleChange = { vm.title(n.id, it) },
        onSegmentChange = { id, text -> translationSession.invalidate(id); vm.segment(n.id, id, text) },
        onClearSegment = { translationSession.invalidate(it); vm.clearSegment(n.id, it) },
        onTogglePlayback = { player?.toggle() },
        onSeek = { position, play -> player?.seek(position, play) },
        onRetry = { vm.start(n.id) }, onExport = { export = true },
        onDeleteNote = { deleting = true }, onBack = back, onLabel = { choosingLabel=true }, onSpeed = { player?.setSpeed(it) },
        onRetranscribe={retrySegmentId=it}, retryStatus=draft?.status,
        onReviewRetry={retrySegmentId=draft?.original?.id},
        translations = translations,
        onTranslator = { pendingTranslationId = null; translatorSheet = true },
        onTranslate = { segment ->
            val result = translations.results[segment.id]
            if (result?.text != null && result.source == segment.text) translationSession.toggle(segment.id)
            else if (translations.active == null) { pendingTranslationId = segment.id; translatorSheet = true }
            else translationSession.request(segment.id, segment.text)
        }
    )
    if(retrySegmentId!=null) {
        val target=n.segments.firstOrNull {it.id==retrySegmentId}
        if(draft!=null || target!=null) SegmentRetryDialog(n,target,draft,vm,{retrySegmentId=null})
    }
    if(choosingLabel) LabelPicker(labels,n.label,{vm.assignLabel(n.id,it)},vm::createLabel,{choosingLabel=false})
    if (deleting) AlertDialog(
        onDismissRequest = { deleting = false }, title = { Text("Delete this note?") },
        text = { Column {
            Text("The transcript will be permanently deleted.")
            if (n.owned) Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(deleteAudio, { deleteAudio = it }); Text("Also delete the imported audio copy")
            }
        } },
        confirmButton = { TextButton(onClick = { vm.delete(n, deleteAudio); deleting = false; back() }) { Text("Delete") } },
        dismissButton = { TextButton(onClick = { deleting = false }) { Text("Cancel") } }
    )
    if (export) AlertDialog(
        onDismissRequest = { export = false }, title = { Text("Export your transcript") },
        text = { Column(Modifier.verticalScroll(rememberScrollState())) {
            Text("Complete note",style=MaterialTheme.typography.titleMedium)
            Text("Includes original audio, text, timestamps and label. The recipient can import it into WhisperNote.",style=MaterialTheme.typography.bodySmall)
            Row {
                TextButton(onClick={archiveSave.launch("${safeName(n.title)}.whispernote");export=false},enabled=n.audio.isNotBlank()) {Text("Save complete note")}
                TextButton(onClick={
                    export=false
                    vm.exportArchive(n,null) { file ->
                        val uri=FileProvider.getUriForFile(context,"${context.packageName}.files",file)
                        context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType(NoteArchive.MIME).putExtra(Intent.EXTRA_STREAM,uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),"Share complete note"))
                    }
                },enabled=n.audio.isNotBlank()) {Text("Share")}
            }
            HorizontalDivider()
            listOf("txt", "md", "srt").forEach { format -> Row(verticalAlignment = Alignment.CenterVertically) {
                Text(format.uppercase(), Modifier.weight(1f))
                TextButton(onClick = { exportFormat = format; save.launch("${safeName(n.title)}.$format"); export = false }) { Text("Save") }
                TextButton(onClick = {
                    scope.launch(Dispatchers.IO) {
                        try {
                            val file = File(context.cacheDir, "exports").apply { mkdirs() }.resolve("${safeName(n.title)}.$format")
                            file.writeText(Exporter.render(n, format))
                            val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
                            val intent = Intent(Intent.ACTION_SEND).setType(if (format == "srt") "application/x-subrip" else "text/plain")
                                .putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            withContext(Dispatchers.Main) { context.startActivity(Intent.createChooser(intent, "Share transcript")) }
                        } catch (e: Exception) { vm.error.value = e.message }
                    }
                    export = false
                }) { Text("Share") }
            } }
        } }, confirmButton = { TextButton(onClick = { export = false }) { Text("Close") } }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TranscriptDetailContent(
    n: Note,
    query: String,
    playback: Playback,
    onTitleChange: (String) -> Unit,
    onSegmentChange: (String, String) -> Unit,
    onClearSegment: (String) -> Unit,
    onTogglePlayback: () -> Unit,
    onSeek: (Long, Boolean) -> Unit,
    onRetry: () -> Unit,
    onExport: () -> Unit,
    onDeleteNote: () -> Unit,
    onBack: () -> Unit,
    list: LazyListState = rememberLazyListState(),
    onLabel: () -> Unit = {},
    onSpeed: (Float) -> Unit = {},
    onRetranscribe: (String) -> Unit = {},
    retryStatus: String? = null,
    onReviewRetry: () -> Unit = {},
    translations: TranslationSessionState = TranslationSessionState(),
    onTranslator: () -> Unit = {},
    onTranslate: (Segment) -> Unit = {}
) {
    var title by rememberSaveable(n.id) { mutableStateOf(n.title) }
    var editingId by rememberSaveable(n.id) { mutableStateOf<String?>(null) }
    var deletingId by rememberSaveable(n.id) { mutableStateOf<String?>(null) }
    var follow by rememberSaveable(n.id) { mutableStateOf(false) }
    // Keep centering space after a manual interruption to avoid a sudden layout jump under the finger.
    var reserveCenterSpace by rememberSaveable(n.id) { mutableStateOf(false) }
    var measuredViewportHeight by remember { mutableIntStateOf(0) }
    var viewportHeight by remember { mutableIntStateOf(0) }
    // Let the header animation settle before changing centering padding or restarting following.
    LaunchedEffect(list) {
        snapshotFlow { measuredViewportHeight }.collectLatest { height ->
            if(viewportHeight!=0) delay(150)
            viewportHeight=height
        }
    }
    var scrollCollapsed by remember { mutableStateOf(false) }
    LaunchedEffect(list) {
        snapshotFlow { if(list.canScrollBackward) 1 else if(!list.isScrollInProgress) 0 else -1 }
            .collect { state -> if(state>=0) scrollCollapsed=state==1 }
    }
    val compact = follow || editingId != null || scrollCollapsed
    val activeIndex = segmentAtPosition(n.segments,playback.position)
    val activeId = n.segments.getOrNull(activeIndex)?.id
    val manualInteraction = rememberUpdatedState { follow = false }
    val manualScroll = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (source == NestedScrollSource.UserInput && available.y != 0f) manualInteraction.value()
                return Offset.Zero
            }
        }
    }
    fun setFollowing(enabled: Boolean) {
        follow = enabled
        if (enabled) { reserveCenterSpace = true; editingId = null }
    }
    // Never use isScrollInProgress to distinguish user input: our own animation also sets that flag.
    // Viewport changes restart alignment once the expanding/collapsing player has settled.
    LaunchedEffect(follow, activeId, viewportHeight, n.segments.size) {
        if (follow && activeId != null && viewportHeight > 0) list.centerSegment(activeIndex + 2, activeId)
    }
    LaunchedEffect(follow,list) {
        snapshotFlow { list.firstVisibleItemIndex == 0 && list.firstVisibleItemScrollOffset == 0 }
            .collect { atTop -> if(!follow && atTop) reserveCenterSpace=false }
    }
    LaunchedEffect(n.id, query) {
        if (query.isNotBlank()) {
            val match = n.segments.indexOfFirst { it.text.contains(query, ignoreCase = true) }
            if (match >= 0) list.scrollToItem(match + 2)
        }
    }
    val metadata=remember(n.created,n.model,n.language) {
        "${DateFormat.getDateInstance().format(Date(n.created))} · ${ModelCatalog.models.firstOrNull { it.id == n.model }?.displayName ?: n.model} · ${n.language.ifBlank { "Auto language" }}"
    }
    val centerSpace = with(LocalDensity.current) { (viewportHeight / 2).toDp() }
    Scaffold(topBar = {
        TopAppBar(
            title = { Text("Audio note") },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back") } },
            actions = {
                IconButton(onClick=onTranslator, modifier=Modifier.testTag("translation-toolbar")) {
                    Icon(Icons.Outlined.Translate, translations.active?.let { "Translator active: ${it.label}" } ?: "Choose translator",
                        tint=if(translations.active!=null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick=onLabel) {Icon(Icons.AutoMirrored.Outlined.Label,"Change label")}
                IconButton(onClick = onExport, enabled = n.segments.isNotEmpty() || n.audio.isNotBlank()) { Icon(Icons.Outlined.IosShare, "Export") }
                IconButton(onClick = onDeleteNote, enabled = !n.busy) { Icon(Icons.Outlined.DeleteOutline, "Delete note") }
            }
        )
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).imePadding()) {
            AnimatedVisibility(
                visible = !compact,
                enter = expandVertically(tween(220)) + fadeIn(tween(180)),
                exit = shrinkVertically(tween(220)) + fadeOut(tween(140))
            ) {
                Column(Modifier.padding(horizontal = 20.dp).padding(top = 12.dp, bottom = 16.dp)) {
                    BasicTextField(title, { title = it; onTitleChange(it) }, Modifier.fillMaxWidth(),
                        textStyle = MaterialTheme.typography.headlineMedium.copy(color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Bold),
                        decorationBox = { inner -> if (title.isEmpty()) Text("Untitled note", style = MaterialTheme.typography.headlineMedium); inner() })
                    TextButton(onClick=onLabel) {Text(n.label ?: "Add label")}
                    Spacer(Modifier.height(8.dp))
                    Text(metadata,
                        style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            // Outside the scroll container: play/pause and seeking never scroll off screen.
            PinnedAudioPlayer(
                playback = playback, duration = n.duration, compact = compact, following = follow,
                canFollow = n.segments.isNotEmpty(), onFollow = { setFollowing(it) },
                onToggle = onTogglePlayback, onSeek = onSeek, onSpeed = onSpeed, onScrub = { follow = false },
                modifier = Modifier.padding(horizontal = 20.dp).padding(bottom = 8.dp)
            )
            LazyColumn(
                state = list,
                modifier = Modifier.weight(1f).fillMaxWidth().testTag("transcript-list")
                    .onSizeChanged { measuredViewportHeight = it.height }
                    .nestedScroll(manualScroll)
                    .onPreviewKeyEvent { manualInteraction.value(); false }
                    .pointerInput(n.id) {
                        awaitEachGesture {
                            awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                            manualInteraction.value()
                            waitForUpOrCancellation(pass = PointerEventPass.Initial)
                        }
                    },
                contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 12.dp, bottom = if (reserveCenterSpace) centerSpace else 20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                item(key = "transcript-header") {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        if(retryStatus!=null) TextButton(onClick={editingId=null;onReviewRetry()}) {Text("Retranscription · $retryStatus · View")}
                        if (n.busy) {
                            Text("${n.status} ${if (n.progress > 0) "${n.progress}%" else "…"}")
                            LinearProgressIndicator(Modifier.fillMaxWidth())
                            Text(if (n.checkpointMs > 0) "Text saved through ${timestamp(n.checkpointMs)}. New segments appear below as they finish." else "You can leave this screen. Text appears below as segments finish.", style = MaterialTheme.typography.bodySmall)
                        }
                        if (n.resumable) {
                            Text("Transcription incomplete", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.error)
                            Text(if (n.checkpointMs > 0) "Saved through ${timestamp(n.checkpointMs)} of ${timestamp(n.duration)}. Resume keeps this text and continues from that timestamp." else "Your audio is saved. Retry to transcribe it.")
                            n.error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                            Button(onClick = onRetry) { Text(if (n.checkpointMs > 0) "Resume from ${timestamp(n.checkpointMs)}" else "Retry transcription") }
                        }
                        if (n.status == "Completed" && n.segments.isEmpty()) Text("No transcript segments. Your audio is still available.")
                        if (n.segments.isNotEmpty()) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text("TRANSCRIPT", Modifier.weight(1f), style = MaterialTheme.typography.labelMedium)
                            if (!follow) FilterChip(false, { setFollowing(true) }, modifier = Modifier.testTag("follow-playback-chip"), label = { Text("Follow playback") })
                        }
                    }
                }
                item(key = "centering-space") { Spacer(Modifier.height(if (reserveCenterSpace) centerSpace else 0.dp)) }
                itemsIndexed(n.segments, key = { _, segment -> segment.id }) { _, segment ->
                    TranscriptSegmentRow(
                        segment=segment, active=segment.id==activeId,
                        playing=segment.id==activeId && playback.playing, ready=playback.ready,
                        editing=editingId==segment.id,
                        onTimestamp={follow=false;onSeek(segment.start,true)},
                        onPlayback={
                            follow=false
                            if(segment.id==activeId) onTogglePlayback() else onSeek(segment.start,true)
                        },
                        onEdit={follow=false;editingId=if(editingId==segment.id) null else segment.id},
                        onDelete={follow=false;deletingId=segment.id},
                        onText={follow=false;onSegmentChange(segment.id,it)},
                        onRetranscribe={follow=false;editingId=null;onRetranscribe(segment.id)}, canRetranscribe=!n.busy && n.audio.isNotBlank(),
                        translation=translations.results[segment.id]?.takeIf { it.source==segment.text },
                        onTranslate={follow=false;onTranslate(segment)}
                    )
                }
            }
        }
    }
    remember(n.segments,deletingId) { if(deletingId==null) null else n.segments.firstOrNull { it.id == deletingId } }?.let { segment ->
        AlertDialog(
            onDismissRequest = { deletingId = null }, title = { Text("Clear this text?") },
            text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Clear the text at ${timestamp(segment.start)}? Its timestamps and audio stay available. You can add text or retranscribe this section later.")
                Text(segment.text, maxLines = 5, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } },
            confirmButton = { TextButton(onClick = {
                if (editingId == segment.id) editingId = null
                onClearSegment(segment.id); deletingId = null
            }) { Text("Clear text") } },
            dismissButton = { TextButton(onClick = { deletingId = null }) { Text("Cancel") } }
        )
    }
}

/** Align against the measured viewport, not estimated row heights or a hard-coded pixel offset. */
private suspend fun LazyListState.centerSegment(index: Int, segmentId: String) {
    if (layoutInfo.visibleItemsInfo.none { it.key == segmentId }) scrollToItem(index)
    val item = snapshotFlow { layoutInfo.visibleItemsInfo.firstOrNull { it.key == segmentId } }.first { it != null }!!
    val viewportCenter = (layoutInfo.viewportStartOffset + layoutInfo.viewportEndOffset) / 2f
    val distance = item.offset + item.size / 2f - viewportCenter
    if (abs(distance) > 1f) animateScrollBy(distance, tween(320))
}

@Composable
private fun PinnedAudioPlayer(
    playback: Playback, duration: Long, compact: Boolean, following: Boolean, canFollow: Boolean,
    onFollow: (Boolean) -> Unit, onToggle: () -> Unit, onSeek: (Long, Boolean) -> Unit,
    onSpeed: (Float) -> Unit, onScrub: () -> Unit, modifier: Modifier = Modifier
) {
    val total = playback.duration.takeIf { it > 0 } ?: duration
    var scrubPosition by remember { mutableStateOf<Float?>(null) }
    Card(modifier.fillMaxWidth().testTag("audio-player").animateContentSize(tween(220)), shape = RoundedCornerShape(if (compact) 16.dp else 24.dp)) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = if (compact) 8.dp else 16.dp)) {
            if (compact) {
                Row(Modifier.fillMaxWidth().testTag("compact-player"), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { onSeek(playback.position - 10000, false) }, enabled = playback.ready) { Icon(Icons.Outlined.Replay10, "Back 10 seconds") }
                    FilledIconButton(onClick = onToggle, enabled = playback.ready, modifier = Modifier.size(48.dp)) {
                        Icon(if (playback.playing) Icons.Outlined.Pause else Icons.Outlined.PlayArrow, if (playback.playing) "Pause" else "Play")
                    }
                    IconButton(onClick = { onSeek(playback.position + 10000, false) }, enabled = playback.ready) { Icon(Icons.Outlined.Forward10, "Forward 10 seconds") }
                    Column(Modifier.weight(1f).padding(start = 8.dp)) {
                        Text(timestamp(playback.position), style = MaterialTheme.typography.labelMedium)
                        Text("/ ${timestamp(total)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    IconToggleButton(checked = following, onCheckedChange = onFollow, enabled = canFollow, modifier = Modifier.testTag("follow-playback-toggle")) {
                        Icon(Icons.Outlined.MyLocation, if (following) "Stop following playback" else "Follow playback", tint = if (following) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            } else Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                Text("Listen & read",Modifier.weight(1f),fontWeight=FontWeight.SemiBold)
                PlaybackSpeedMenu(playback.speed,playback.ready,onSpeed)
            }
            playback.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
            Slider(
                value = (scrubPosition ?: playback.position.toFloat()).coerceIn(0f, total.coerceAtLeast(1).toFloat()),
                onValueChange = { onScrub(); scrubPosition = it },
                onValueChangeFinished = { scrubPosition?.let { onSeek(it.toLong(), false) }; scrubPosition = null },
                valueRange = 0f..total.coerceAtLeast(1).toFloat(), enabled = playback.ready, modifier = Modifier.weight(1f)
            )
            if(compact) PlaybackSpeedMenu(playback.speed,playback.ready,onSpeed)
            }
            if (!compact) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text(timestamp(playback.position)); Text(timestamp(total)) }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { onSeek(playback.position - 10000, false) }, enabled = playback.ready) { Icon(Icons.Outlined.Replay10, "Back 10 seconds") }
                    FilledIconButton(onClick = onToggle, enabled = playback.ready, modifier = Modifier.size(56.dp)) {
                        Icon(if (playback.playing) Icons.Outlined.Pause else Icons.Outlined.PlayArrow, if (playback.playing) "Pause" else "Play")
                    }
                    IconButton(onClick = { onSeek(playback.position + 10000, false) }, enabled = playback.ready) { Icon(Icons.Outlined.Forward10, "Forward 10 seconds") }
                }
            }
        }
    }
}

private fun safeName(title: String) = title.replace(Regex("[^\\p{L}\\p{N} ._-]"), "_").take(80).ifBlank { "Transcript" }

@Composable
private fun PlaybackSpeedMenu(speed: Float, enabled: Boolean, onSpeed: (Float) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    fun label(value: Float) = "${value.toString().removeSuffix(".0")}×"
    Box {
        TextButton(onClick={expanded=true},enabled=enabled,modifier=Modifier.testTag("playback-speed")) { Text(label(speed)) }
        DropdownMenu(expanded,onDismissRequest={expanded=false}) {
            listOf(0.5f,0.75f,1f,1.25f,1.5f,2f).forEach { value ->
                DropdownMenuItem(text={Text(label(value))},onClick={onSpeed(value);expanded=false},
                    trailingIcon={if(value==speed) Icon(Icons.Outlined.Check,"Selected")})
            }
        }
    }
}

/** A row only needs active/playing flags, not the continuously changing playback position. */
@Composable
private fun TranscriptSegmentRow(
    segment: Segment, active: Boolean, playing: Boolean, ready: Boolean, editing: Boolean,
    onTimestamp: () -> Unit, onPlayback: () -> Unit, onEdit: () -> Unit,
    onDelete: () -> Unit, onText: (String) -> Unit, onRetranscribe: () -> Unit, canRetranscribe: Boolean,
    translation: SegmentTranslation? = null, onTranslate: () -> Unit = {}
) {
                    Column(Modifier.fillMaxWidth().testTag("segment-${segment.id}")
                        .background(if (active) MaterialTheme.colorScheme.secondaryContainer.copy(alpha = .5f) else MaterialTheme.colorScheme.surface, RoundedCornerShape(12.dp))
                        .padding(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            TextButton(onClick = onTimestamp, contentPadding = PaddingValues(0.dp)) {
                                Text(timestamp(segment.start), style = MaterialTheme.typography.labelMedium)
                            }
                            Spacer(Modifier.width(8.dp))
                            IconButton(onClick=onPlayback,enabled=ready,modifier=Modifier.testTag("segment-play-${segment.id}")) {
                                Icon(if(playing) Icons.Outlined.Pause else Icons.Outlined.PlayArrow,
                                    if(playing) "Pause segment" else "Play segment")
                            }
                            Spacer(Modifier.weight(1f))
                            SegmentTranslateButton(segment.id, translation?.pending==true, segment.text.isNotBlank() && !editing,
                                translation?.text!=null && translation.expanded, onTranslate)
                            Box {
                                var menuOpen by remember(segment.id) { mutableStateOf(false) }
                                IconButton(onClick={menuOpen=true},modifier=Modifier.testTag("segment-menu-${segment.id}")) {
                                    Icon(Icons.Outlined.MoreVert,"Segment actions")
                                }
                                DropdownMenu(expanded=menuOpen,onDismissRequest={menuOpen=false}) {
                                    DropdownMenuItem(text={Text("Retranscribe")},leadingIcon={Icon(Icons.Outlined.Refresh,null)},
                                        enabled=canRetranscribe,onClick={menuOpen=false;onRetranscribe()})
                                    DropdownMenuItem(text={Text(if(editing) "Finish editing" else if(segment.text.isBlank()) "Add text" else "Edit text")},
                                        leadingIcon={Icon(Icons.Outlined.Edit,null)},onClick={menuOpen=false;onEdit()})
                                    DropdownMenuItem(text={Text("Clear text")},leadingIcon={Icon(Icons.Outlined.DeleteOutline,null)},
                                        enabled=segment.text.isNotBlank(),onClick={menuOpen=false;onDelete()})
                                }
                            }
                        }
                        if (editing) {
                            var text by rememberSaveable(segment.id) { mutableStateOf(segment.text) }
                            OutlinedTextField(text, { text = it; onText(it) }, Modifier.fillMaxWidth(),
                                supportingText = { Text("Saved automatically · timestamps preserved") })
                        } else if(segment.text.isBlank()) {
                            Text("No transcript",style=MaterialTheme.typography.bodyLarge,color=MaterialTheme.colorScheme.onSurfaceVariant)
                        } else SelectionContainer {
                            Text(segment.text, style = MaterialTheme.typography.bodyLarge, lineHeight = 27.sp)
                        }
                        if (translation?.error != null) {
                            Text(translation.error, color=MaterialTheme.colorScheme.error, style=MaterialTheme.typography.bodySmall)
                            TextButton(onClick=onTranslate) { Text("Retry translation") }
                        }
                        if (translation?.text != null && translation.expanded) {
                            Column(Modifier.fillMaxWidth().testTag("translation-${segment.id}").padding(top=12.dp), verticalArrangement=Arrangement.spacedBy(8.dp)) {
                                HorizontalDivider()
                                Row(Modifier.fillMaxWidth(), verticalAlignment=Alignment.CenterVertically, horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                                    Text(TranslationCatalog.name(translation.target), modifier=Modifier.weight(1f), style=MaterialTheme.typography.labelMedium, color=MaterialTheme.colorScheme.primary)
                                    TranslationAttribution()
                                }
                                SelectionContainer { Text(translation.text, style=MaterialTheme.typography.bodyLarge, lineHeight=27.sp) }
                            }
                        }

                    }
}
