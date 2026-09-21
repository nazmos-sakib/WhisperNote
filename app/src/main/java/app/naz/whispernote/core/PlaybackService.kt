package app.naz.whispernote.core

import android.app.*
import android.content.*
import android.content.pm.ServiceInfo
import android.media.*
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.*
import androidx.core.content.ContextCompat
import app.naz.whispernote.MainActivity
import app.naz.whispernote.WhisperApp
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Shared UI snapshot; the service, never a screen, owns the actual player. */
data class Listening(val noteId: String, val title: String, val audio: String, val playback: Playback = Playback())
class PlaybackController(private val context: Context) {
    internal val mutable = MutableStateFlow<Listening?>(null)
    val state = mutable.asStateFlow()
    fun command(action: String, note: Note? = null, position: Long? = null, play: Boolean = false, speed: Float? = null) {
        if (note == null && state.value == null) return
        val intent = Intent(context, PlaybackService::class.java).setAction(action)
        note?.let { intent.putExtra("noteId", it.id).putExtra("audio", it.audio).putExtra("title", it.title) }
        position?.let { intent.putExtra("position", it) }
        speed?.let { intent.putExtra("speed", it) }
        intent.putExtra("play", play)
        try { ContextCompat.startForegroundService(context, intent) }
        catch (e: Exception) {
            val current = mutable.value ?: note?.let { Listening(it.id, it.title, it.audio) }
            mutable.value = current?.copy(playback = current.playback.copy(error = "Playback could not start: ${e.localizedMessage}"))
        }
    }
    fun toggle(note: Note? = null, speed: Float? = null) = command(PlaybackService.TOGGLE, note, speed = speed)
    fun seek(note: Note?, position: Long, play: Boolean = false) = command(PlaybackService.SEEK, note, position, play)
    fun speed(note: Note, speed: Float) = command(PlaybackService.SPEED, note, speed = speed)
    fun stop() = command(PlaybackService.STOP)
}

/** Local, non-exported foreground playback with system media controls and audio-focus handling. */
class PlaybackService : Service() {
    companion object {
        const val TOGGLE = "app.naz.whispernote.playback.TOGGLE"
        const val SEEK = "app.naz.whispernote.playback.SEEK"
        const val SPEED = "app.naz.whispernote.playback.SPEED"
        const val STOP = "app.naz.whispernote.playback.STOP"
        const val BACK = "app.naz.whispernote.playback.BACK"
        const val FORWARD = "app.naz.whispernote.playback.FORWARD"
        private const val CHANNEL = "audio_playback"
        private const val NOTIFICATION = 702
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val controller get() = (application as WhisperApp).playback
    private lateinit var session: MediaSession
    private lateinit var audioManager: AudioManager
    private lateinit var positions: PlaybackPositions
    private var player: AudioPlayer? = null
    private var observation: Job? = null
    private var wantedPlay = false
    private var pendingSeek: Long? = null
    private var pendingSpeed = 1f
    private var resumeAfterFocus = false
    private var hasFocus = false
    private var foreground = false
    private var lastNotificationKey: List<Any?>? = null
    private var lastMetadataKey: List<Any?>? = null
    private var focusRequest: AudioFocusRequest? = null
    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        when (change) {
            AudioManager.AUDIOFOCUS_GAIN -> {
                hasFocus = true
                if (resumeAfterFocus) { resumeAfterFocus = false; play() }
            }
            AudioManager.AUDIOFOCUS_LOSS -> { hasFocus = false; pause() }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT, AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                resumeAfterFocus = wantedPlay || player?.state?.value?.playing == true
                wantedPlay = false
                hasFocus = false
                player?.pause()
            }
        }
    }
    private val noisy = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) { if (intent?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) pause() }
    }
    override fun onCreate() {
        super.onCreate()
        positions = PlaybackPositions(this)
        audioManager = getSystemService(AudioManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel(CHANNEL, "Audio playback", NotificationManager.IMPORTANCE_LOW))
            focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                .setWillPauseWhenDucked(true).setOnAudioFocusChangeListener(focusListener).build()
        }
        session = MediaSession(this, "WhisperNote playback")
        @Suppress("DEPRECATION")
        session.setFlags(MediaSession.FLAG_HANDLES_MEDIA_BUTTONS or MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS)
        session.setCallback(object : MediaSession.Callback() {
            override fun onCustomAction(action: String, extras: Bundle?) { if (action == STOP) stopPlayback() }
            override fun onPlay() = play()
            override fun onPause() = pause()
            override fun onStop() = stopPlayback()
            override fun onSeekTo(pos: Long) { player?.seek(pos) }
            override fun onRewind() = skip(-10000)
            override fun onFastForward() = skip(10000)
            override fun onSkipToPrevious() = skip(-10000)
            override fun onSkipToNext() = skip(10000)
        })
        session.isActive = true
        ContextCompat.registerReceiver(this, noisy, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY), ContextCompat.RECEIVER_EXPORTED)
        // This service receives only explicit commands from this app / immutable notification intents.
        promote()
    }
    override fun onBind(intent: Intent?) = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        promote() // Satisfy startForegroundService even for pause/stop commands.
        if (intent == null || intent.action == STOP) { stopPlayback(); return START_NOT_STICKY }
        val noteId = intent.getStringExtra("noteId")
        if (noteId != null && (controller.state.value?.noteId != noteId || player == null)) {
            select(noteId, intent.getStringExtra("title").orEmpty(), intent.getStringExtra("audio").orEmpty())
        }
        if (player == null) { stopPlayback(); return START_NOT_STICKY }
        if (intent.hasExtra("speed")) {
            pendingSpeed = intent.getFloatExtra("speed", 1f)
            player!!.setSpeed(pendingSpeed)
        }
        when (intent.action) {
            TOGGLE -> if (wantedPlay || player!!.state.value.playing) pause() else play()
            SEEK -> {
                val target = intent.getLongExtra("position", 0)
                if (player!!.state.value.ready) player!!.seek(target) else pendingSeek = target
                if (intent.getBooleanExtra("play", false)) play() else update(player!!.state.value)
            }
            SPEED -> {
                pendingSpeed = intent.getFloatExtra("speed", 1f)
                player!!.setSpeed(pendingSpeed)
                update(player!!.state.value)
            }
            BACK -> skip(-10000)
            FORWARD -> skip(10000)
        }
        return START_NOT_STICKY
    }
    private fun select(id: String, title: String, audio: String) {
        wantedPlay = false; resumeAfterFocus = false; pendingSeek = null; pendingSpeed = 1f
        observation?.cancel(); player?.release(); abandonFocus()
        controller.mutable.value = Listening(id, title.ifBlank { "Untitled note" }, audio)
        session.isActive = true
        session.setSessionActivity(openNote())
        val selected = AudioPlayer(this, audio, positions.get(id)) { positions.save(id, it) }
        player = selected
        observation = scope.launch {
            selected.state.collect { state ->
                if (state.ready) {
                    pendingSeek?.let { pendingSeek = null; selected.seek(it) }
                    if (state.speed != pendingSpeed) {
                        selected.setSpeed(pendingSpeed)
                        pendingSpeed = selected.state.value.speed
                    }
                    if (wantedPlay && !state.playing) {
                        wantedPlay = false
                        if (hasFocus) selected.play()
                    }
                }
                update(selected.state.value)
            }
        }
    }
    private fun play() {
        if (player == null) return
        promote()
        resumeAfterFocus = false
        @Suppress("DEPRECATION")
        val granted = if (Build.VERSION.SDK_INT >= 26) audioManager.requestAudioFocus(focusRequest!!) else audioManager.requestAudioFocus(focusListener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN)
        hasFocus = granted == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        if (!hasFocus) {
            wantedPlay = false
            update(player!!.state.value.copy(error = "Another app is using audio. Try Play again."))
            return
        }
        wantedPlay = !player!!.state.value.ready
        if (!wantedPlay) player!!.play()
        update(player!!.state.value)
    }
    private fun pause() {
        wantedPlay = false; resumeAfterFocus = false
        player?.pause(); abandonFocus()
        player?.state?.value?.let(::update)
    }
    private fun abandonFocus() {
        @Suppress("DEPRECATION")
        if (Build.VERSION.SDK_INT >= 26) focusRequest?.let { audioManager.abandonAudioFocusRequest(it) } else audioManager.abandonAudioFocus(focusListener)
        hasFocus = false
    }
    private fun skip(delta: Long) { player?.let { it.seek(it.state.value.position + delta); update(it.state.value) } }
    private fun update(state: Playback) {
        val current = controller.state.value ?: return
        controller.mutable.value = current.copy(playback = state)
        val status = when { state.error != null -> PlaybackState.STATE_ERROR; state.playing -> PlaybackState.STATE_PLAYING; !state.ready -> PlaybackState.STATE_BUFFERING; else -> PlaybackState.STATE_PAUSED }
        val builder = PlaybackState.Builder().setActions(PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_STOP or PlaybackState.ACTION_SEEK_TO or PlaybackState.ACTION_REWIND or PlaybackState.ACTION_FAST_FORWARD or PlaybackState.ACTION_SKIP_TO_PREVIOUS or PlaybackState.ACTION_SKIP_TO_NEXT)
            .setState(status, state.position, if (state.playing) state.speed else 0f)
            .addCustomAction(STOP, "Stop", android.R.drawable.ic_menu_close_clear_cancel)
        state.error?.let { builder.setErrorMessage(it) }
        session.setPlaybackState(builder.build())
        val metadataKey = listOf(current.noteId, current.title, state.duration)
        if (lastMetadataKey != metadataKey) {
            lastMetadataKey = metadataKey
            session.setMetadata(MediaMetadata.Builder().putString(MediaMetadata.METADATA_KEY_TITLE, current.title)
                .putString(MediaMetadata.METADATA_KEY_ARTIST, "WhisperNote").putLong(MediaMetadata.METADATA_KEY_DURATION, state.duration).build())
        }
        if (state.error != null) wantedPlay = false
        if (!state.playing && !wantedPlay && (state.ready || state.error != null) && !resumeAfterFocus) abandonFocus()
        val key = listOf(current.noteId, current.title, state.playing, state.ready, state.error)
        if (lastNotificationKey != key) {
            lastNotificationKey = key
            getSystemService(NotificationManager::class.java).notify(NOTIFICATION, notification())
        }
        if (!state.playing && !wantedPlay && (state.ready || state.error != null) && !resumeAfterFocus && foreground) {
            stopForeground(STOP_FOREGROUND_DETACH); foreground = false
        }
    }
    private fun openNote(): PendingIntent = PendingIntent.getActivity(this, 0,
        Intent(this, MainActivity::class.java).putExtra("noteId", controller.state.value?.noteId)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    private fun action(action: String): PendingIntent = PendingIntent.getService(this, action.hashCode(), Intent(this, PlaybackService::class.java).setAction(action), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    @Suppress("DEPRECATION")
    private fun notification(): Notification {
        val current = controller.state.value
        val playing = current?.playback?.playing == true
        val builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL) else Notification.Builder(this)
        return builder.setSmallIcon(android.R.drawable.ic_media_play).setContentTitle(current?.title ?: "WhisperNote")
            .setContentText(current?.playback?.error ?: if (playing) "Playing audio" else "Audio playback")
            .setContentIntent(openNote()).setDeleteIntent(action(STOP)).setOnlyAlertOnce(true).setOngoing(playing)
            .setVisibility(Notification.VISIBILITY_PUBLIC).setCategory(Notification.CATEGORY_TRANSPORT)
            .addAction(android.R.drawable.ic_media_rew, "Back 10 seconds", action(BACK))
            .addAction(if (playing) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play, if (playing) "Pause" else "Play", action(TOGGLE))
            .addAction(android.R.drawable.ic_media_ff, "Forward 10 seconds", action(FORWARD))
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", action(STOP))
            .setStyle(Notification.MediaStyle().setMediaSession(session.sessionToken).setShowActionsInCompactView(0, 1, 2)).build()
    }
    private fun promote() {
        if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIFICATION, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        else startForeground(NOTIFICATION, notification())
        foreground = true
    }
    private fun stopPlayback() {
        wantedPlay = false; resumeAfterFocus = false
        observation?.cancel(); observation = null
        player?.release(); player = null
        abandonFocus()
        controller.mutable.value = null
        session.isActive = false
        stopForeground(STOP_FOREGROUND_REMOVE); foreground = false
        stopSelf()
    }
    override fun onTaskRemoved(rootIntent: Intent?) { if (player?.state?.value?.playing != true && !wantedPlay) stopPlayback() }
    override fun onDestroy() {
        observation?.cancel(); player?.release(); player = null
        scope.cancel(); abandonFocus()
        unregisterReceiver(noisy); session.release()
        controller.mutable.value = null
        getSystemService(NotificationManager::class.java).cancel(NOTIFICATION)
        super.onDestroy()
    }
}
