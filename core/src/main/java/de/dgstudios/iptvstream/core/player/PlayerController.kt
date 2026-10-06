package de.dgstudios.iptvstream.core.player

import android.content.Context
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.decoder.ffmpeg.FfmpegAudioRenderer
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlaybackException
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioCapabilities
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory
import de.dgstudios.iptvstream.core.data.PlayItem
import de.dgstudios.iptvstream.core.data.PlayKind
import de.dgstudios.iptvstream.core.data.remote.Net
import de.dgstudios.iptvstream.core.settings.AppSettings
import de.dgstudios.iptvstream.core.util.Urls
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.util.Locale

data class TrackOption(
    val id: String,
    val label: String,
    val selected: Boolean,
    val supported: Boolean = true,
)

data class PlayerState(
    val loading: Boolean = true,
    val playing: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val bufferedMs: Long = 0,
    val isLive: Boolean = false,
    val error: String? = null,
    val notice: String? = null,
    val audioTracks: List<TrackOption> = emptyList(),
    val textTracks: List<TrackOption> = emptyList(),
    val textEnabled: Boolean = false,
    val videoWidth: Int = 0,
    val videoHeight: Int = 0,
    val resize: String = "fit",
    val ended: Boolean = false,
)

/**
 * Hardware-Decoder zuerst, FFmpeg nur als Fallback (AC3, E-AC3, MP2, DTS).
 * Passthrough ist standardmäßig aus: [DefaultAudioSink.Builder] mit Context ignoriert
 * [DefaultAudioSink.Builder.setAudioCapabilities] und würde AC3 sonst durchreichen.
 */
class AppRenderersFactory(context: Context, private val passthrough: Boolean) : DefaultRenderersFactory(context) {
    init {
        setEnableDecoderFallback(true)
        setExtensionRendererMode(EXTENSION_RENDERER_MODE_ON)
        // Harte Referenz: DefaultRenderersFactory lädt den Renderer nur per Reflection.
        check(FfmpegAudioRenderer::class.java.constructors.isNotEmpty())
    }

    @Suppress("DEPRECATION")
    override fun buildAudioSink(
        context: Context,
        enableFloatOutput: Boolean,
        enableAudioTrackPlaybackParams: Boolean,
    ): AudioSink {
        val b = if (passthrough) {
            DefaultAudioSink.Builder(context)
        } else {
            DefaultAudioSink.Builder()
                .setAudioCapabilities(AudioCapabilities.DEFAULT_AUDIO_CAPABILITIES)
        }
        return b
            .setEnableFloatOutput(enableFloatOutput)
            .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
            .build()
    }
}

/**
 * Kapselt ExoPlayer: schnelles Umschalten (ein Player, nur Medium wechseln), Audio-Fallback ohne
 * Endlosschleife, automatische Wiederverbindung, Fortschritt und Spurauswahl.
 */
class PlayerController(
    context: Context,
    private val settings: AppSettings,
    okHttp: OkHttpClient,
    private val scope: CoroutineScope,
    private val onProgress: (PlayItem, Long, Long) -> Unit,
) {
    private val _state = MutableStateFlow(PlayerState(resize = settings.resize))
    val state: StateFlow<PlayerState> = _state

    private val trackSelector = DefaultTrackSelector(context)
    val player: ExoPlayer

    private var current: PlayItem? = null
    private var lastTracks: Tracks = Tracks.EMPTY
    private val fallback = AudioFallbackState(maxTrackSwitches = 3)
    private var maxChannels = if (settings.surround) 6 else 2
    private var initialAudioDone = false
    private var initialTextDone = false
    private var silentChecked = false
    private var audioDisabledByUs = false
    private var selectedAudioId: String? = null

    private var reconnectAttempts = 0
    private var reconnectJob: Job? = null
    private var noticeJob: Job? = null
    private var pollJob: Job? = null
    private var playingSince = 0L
    private var lastSaveAt = 0L
    private var released = false

    init {
        val dataSource = DefaultDataSource.Factory(
            context,
            OkHttpDataSource.Factory(okHttp).setUserAgent(Net.USER_AGENT),
        )
        val extractors = DefaultExtractorsFactory().setTsExtractorFlags(
            DefaultTsPayloadReaderFactory.FLAG_DETECT_ACCESS_UNITS or
                DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES,
        )
        val mediaSourceFactory = DefaultMediaSourceFactory(dataSource, extractors)
            .setLoadErrorHandlingPolicy(DefaultLoadErrorHandlingPolicy(6))

        val (minMs, maxMs, bytes) = when (settings.buffer) {
            "small" -> Triple(5_000, 15_000, 40 * 1024 * 1024)
            "large" -> Triple(30_000, 90_000, 96 * 1024 * 1024)
            else -> Triple(15_000, 40_000, 64 * 1024 * 1024)
        }
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(minMs, maxMs, 1_000, 2_500)
            .setTargetBufferBytes(bytes)
            .setPrioritizeTimeOverSizeThresholds(false)
            .build()

        var params = trackSelector.buildUponParameters().setMaxAudioChannelCount(maxChannels)
        if (settings.audioLang != "auto") params = params.setPreferredAudioLanguage(settings.audioLang)
        trackSelector.setParameters(params)

        player = ExoPlayer.Builder(context, AppRenderersFactory(context, settings.passthrough))
            .setTrackSelector(trackSelector)
            .setLoadControl(loadControl)
            .setMediaSourceFactory(mediaSourceFactory)
            .setHandleAudioBecomingNoisy(true)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                    .build(),
                true,
            )
            .build()

        // Untertitel: standardmäßig aus, sonst bevorzugte Sprache.
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, settings.subLang == "off")
            .apply { if (settings.subLang != "off") setPreferredTextLanguage(settings.subLang) }
            .build()

    }

    // ------------------------------------------------------------------ Steuerung

    fun load(item: PlayItem, startMs: Long = 0L) {
        if (released) return
        current?.let { saveProgress(it, force = true) }
        reconnectJob?.cancel()
        fallback.reset()
        maxChannels = if (settings.surround) 6 else 2
        initialAudioDone = false
        initialTextDone = false
        silentChecked = false
        selectedAudioId = null
        reconnectAttempts = 0
        playingSince = 0L
        lastTracks = Tracks.EMPTY
        if (audioDisabledByUs) {
            audioDisabledByUs = false
            player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, false)
                .setMaxAudioChannelCount(maxChannels)
                .clearOverridesOfType(C.TRACK_TYPE_AUDIO)
                .build()
        } else {
            player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                .setMaxAudioChannelCount(maxChannels)
                .clearOverridesOfType(C.TRACK_TYPE_AUDIO)
                .clearOverridesOfType(C.TRACK_TYPE_TEXT)
                .build()
        }
        current = item
        val mediaItem = MediaItem.Builder()
            .setUri(item.url)
            .setMediaId(item.id)
            .apply { if (Urls.isHls(item.url)) setMimeType(MimeTypes.APPLICATION_M3U8) }
            .build()
        _state.update {
            it.copy(
                loading = true, playing = false, error = null, notice = null, ended = false,
                audioTracks = emptyList(), textTracks = emptyList(), positionMs = 0, durationMs = 0,
                isLive = item.kind == PlayKind.LIVE,
            )
        }
        player.setMediaItem(mediaItem, if (startMs > 0) startMs else C.TIME_UNSET)
        player.prepare()
        player.playWhenReady = true
    }

    fun retry() {
        if (released) return
        reconnectAttempts = 0
        _state.update { it.copy(error = null, loading = true) }
        if (current?.kind == PlayKind.LIVE) player.seekToDefaultPosition()
        player.prepare()
        player.playWhenReady = true
    }

    private var stoppedForBackground = false

    /** App geht in den Hintergrund: Fortschritt sichern, Live-Streams ganz beenden (keine offenen Verbindungen). */
    fun onBackground() {
        if (released) return
        current?.let { saveProgress(it, force = true) }
        player.pause()
        if (current?.kind == PlayKind.LIVE) {
            player.stop()
            stoppedForBackground = true
        }
    }

    fun onForeground() {
        if (released) return
        if (stoppedForBackground) {
            stoppedForBackground = false
            retry()
        }
    }

    fun togglePlay() {
        if (player.isPlaying) player.pause() else {
            if (player.playbackState == Player.STATE_IDLE) player.prepare()
            player.play()
        }
    }

    fun pause() = player.pause()

    fun seekBy(deltaMs: Long) {
        if (_state.value.isLive || player.duration == C.TIME_UNSET) return
        val target = (player.currentPosition + deltaMs).coerceIn(0, player.duration)
        player.seekTo(target)
        _state.update { it.copy(positionMs = target) }
    }

    fun seekTo(positionMs: Long) {
        if (_state.value.isLive || player.duration == C.TIME_UNSET) return
        player.seekTo(positionMs.coerceIn(0, player.duration))
    }

    fun setResize(mode: String) {
        _state.update { it.copy(resize = mode) }
    }

    fun selectAudio(id: String) {
        val cand = audioCandidates().firstOrNull { it.id == id } ?: return
        if (audioDisabledByUs) {
            audioDisabledByUs = false
            player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, false).build()
        }
        fallback.failed.remove(id)
        applyAudio(cand)
        silentChecked = false
        playingSince = System.currentTimeMillis()
    }

    fun selectText(id: String?) {
        val params = player.trackSelectionParameters.buildUpon()
        if (id == null) {
            params.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
        } else {
            val (gi, ti) = parseId(id) ?: return
            val group = lastTracks.groups.getOrNull(gi) ?: return
            params.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, ti))
        }
        player.trackSelectionParameters = params.build()
    }

    fun release() {
        if (released) return
        current?.let { saveProgress(it, force = true) }
        released = true
        reconnectJob?.cancel()
        noticeJob?.cancel()
        pollJob?.cancel()
        player.removeListener(listener)
        player.release()
    }

    // ------------------------------------------------------------------ Audio

    private fun audioCandidates(): List<AudioCandidate> {
        val list = ArrayList<AudioCandidate>()
        lastTracks.groups.forEachIndexed { gi, g ->
            if (g.type == C.TRACK_TYPE_AUDIO) {
                for (ti in 0 until g.length) {
                    val f = g.getTrackFormat(ti)
                    list.add(
                        AudioCandidate(
                            id = "a$gi:$ti",
                            groupIndex = gi,
                            trackIndex = ti,
                            language = f.language,
                            label = f.label,
                            channels = f.channelCount.let { if (it == Format.NO_VALUE) 0 else it },
                            mime = f.sampleMimeType,
                            supported = g.isTrackSupported(ti),
                            isDefault = (f.selectionFlags and C.SELECTION_FLAG_DEFAULT) != 0,
                        ),
                    )
                }
            }
        }
        return list
    }

    private fun currentAudioId(): String? {
        lastTracks.groups.forEachIndexed { gi, g ->
            if (g.type == C.TRACK_TYPE_AUDIO) {
                for (ti in 0 until g.length) if (g.isTrackSelected(ti)) return "a$gi:$ti"
            }
        }
        return selectedAudioId
    }

    private fun applyAudio(c: AudioCandidate) {
        val group = lastTracks.groups.getOrNull(c.groupIndex) ?: return
        selectedAudioId = c.id
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, c.trackIndex))
            .build()
    }

    private fun audioFallback(reason: String) {
        if (released) return
        val candidates = audioCandidates()
        when (val step = fallback.onFailure(currentAudioId(), candidates, settings.audioLang, maxChannels)) {
            is AudioFallbackState.Step.SwitchTo -> {
                applyAudio(step.track)
                notice("Ton: wechsle zur Spur „${describe(step.track)}“")
            }
            AudioFallbackState.Step.ForceStereo -> {
                maxChannels = 2
                player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                    .setMaxAudioChannelCount(2)
                    .clearOverridesOfType(C.TRACK_TYPE_AUDIO)
                    .build()
                initialAudioDone = false
                notice("Ton: Stereo-Modus aktiviert")
            }
            AudioFallbackState.Step.GiveUp -> {
                audioDisabledByUs = true
                player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                    .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true)
                    .build()
                notice("Ton konnte nicht wiedergegeben werden ($reason)", 8_000)
            }
        }
        silentChecked = false
        playingSince = System.currentTimeMillis()
        if (player.playbackState == Player.STATE_IDLE) player.prepare()
        player.playWhenReady = true
    }

    private fun describe(c: AudioCandidate): String =
        buildTrackLabel(c.language, c.label, c.mime, c.channels, c.groupIndex)

    // ------------------------------------------------------------------ Listener

    private val listener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            when (playbackState) {
                Player.STATE_READY -> {
                    _state.update { it.copy(loading = false, error = null) }
                }
                Player.STATE_BUFFERING -> _state.update { it.copy(loading = true) }
                Player.STATE_ENDED -> {
                    current?.let { saveProgress(it, force = true) }
                    _state.update { it.copy(loading = false, playing = false, ended = true) }
                }
                else -> {}
            }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (isPlaying) {
                if (playingSince == 0L) playingSince = System.currentTimeMillis()
            }
            _state.update { it.copy(playing = isPlaying) }
        }

        override fun onVideoSizeChanged(videoSize: VideoSize) {
            _state.update { it.copy(videoWidth = videoSize.width, videoHeight = videoSize.height) }
        }

        override fun onTracksChanged(tracks: Tracks) {
            lastTracks = tracks
            val candidates = audioCandidates()

            if (!initialAudioDone && candidates.isNotEmpty()) {
                initialAudioDone = true
                val pick = AudioTrackPicker.pick(candidates, settings.audioLang, maxChannels, fallback.failed)
                if (pick != null) {
                    val alreadySelected = tracks.groups.getOrNull(pick.groupIndex)?.isTrackSelected(pick.trackIndex) == true
                    if (!alreadySelected) applyAudio(pick) else selectedAudioId = pick.id
                }
            }

            val audioOptions = ArrayList<TrackOption>()
            val textOptions = ArrayList<TrackOption>()
            var textSelected = false
            tracks.groups.forEachIndexed { gi, g ->
                for (ti in 0 until g.length) {
                    val f = g.getTrackFormat(ti)
                    when (g.type) {
                        C.TRACK_TYPE_AUDIO -> audioOptions.add(
                            TrackOption(
                                "a$gi:$ti",
                                buildTrackLabel(f.language, f.label, f.sampleMimeType, f.channelCount, gi),
                                g.isTrackSelected(ti),
                                g.isTrackSupported(ti),
                            ),
                        )
                        C.TRACK_TYPE_TEXT -> {
                            val sel = g.isTrackSelected(ti)
                            if (sel) textSelected = true
                            textOptions.add(
                                TrackOption("t$gi:$ti", buildTrackLabel(f.language, f.label, null, 0, gi), sel, true),
                            )
                        }
                    }
                }
            }

            if (!initialTextDone && textOptions.isNotEmpty() && settings.subLang != "off") {
                initialTextDone = true
            }
            _state.update { it.copy(audioTracks = audioOptions, textTracks = textOptions, textEnabled = textSelected) }
        }

        override fun onPlayerError(error: PlaybackException) {
            handleError(error)
        }
    }

    init {
        player.addListener(listener)
        startPolling()
    }

    private fun handleError(error: PlaybackException) {
        if (released) return
        val code = error.errorCode
        val isLive = current?.kind == PlayKind.LIVE

        // Live-Fenster überholt: einfach zum Live-Rand springen.
        if (code == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
            player.seekToDefaultPosition()
            player.prepare()
            return
        }

        val rendererFormat = (error as? ExoPlaybackException)?.takeIf { it.type == ExoPlaybackException.TYPE_RENDERER }?.rendererFormat
        val audioRelated = code in 5001..5005 ||
            (code in 4001..4004 && MimeTypes.isAudio(rendererFormat?.sampleMimeType)) ||
            (rendererFormat != null && MimeTypes.isAudio(rendererFormat.sampleMimeType))
        if (audioRelated) {
            audioFallback("Fehler $code")
            return
        }

        val networkRelated = code in 2000..2004
        val parseRelated = isLive && code in 3001..3004
        if ((networkRelated || parseRelated) && tryReconnect()) return

        _state.update {
            it.copy(loading = false, playing = false, error = friendlyError(code))
        }
    }

    private fun tryReconnect(): Boolean {
        if (!settings.reconnect) return false
        if (reconnectAttempts >= MAX_RECONNECTS) return false
        reconnectAttempts++
        val wait = (1000L shl (reconnectAttempts - 1)).coerceAtMost(8000L)
        notice("Verbindung unterbrochen – neuer Versuch $reconnectAttempts/$MAX_RECONNECTS …", wait + 1500)
        _state.update { it.copy(loading = true) }
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            delay(wait)
            if (released) return@launch
            if (current?.kind == PlayKind.LIVE) player.seekToDefaultPosition()
            player.prepare()
            player.playWhenReady = true
        }
        return true
    }

    private fun friendlyError(code: Int): String = when (code) {
        2001 -> "Keine Verbindung zum Server. Bitte Netzwerk prüfen."
        2002 -> "Zeitüberschreitung. Der Server antwortet nicht."
        2004 -> "Der Server hat den Stream abgelehnt. Möglicherweise sind zu viele Verbindungen aktiv."
        2005 -> "Stream nicht gefunden."
        4001, 4002, 4003, 4004 -> "Dieses Format kann auf diesem Gerät nicht dekodiert werden."
        3001, 3002, 3003, 3004 -> "Der Stream ist beschädigt oder wird nicht unterstützt."
        else -> "Wiedergabefehler ($code)."
    }

    // ------------------------------------------------------------------ Hilfen

    private fun notice(text: String, durationMs: Long = 4_000) {
        _state.update { it.copy(notice = text) }
        noticeJob?.cancel()
        noticeJob = scope.launch {
            delay(durationMs)
            _state.update { if (it.notice == text) it.copy(notice = null) else it }
        }
    }

    private fun parseId(id: String): Pair<Int, Int>? {
        val body = id.drop(1)
        val gi = body.substringBefore(':').toIntOrNull() ?: return null
        val ti = body.substringAfter(':').toIntOrNull() ?: return null
        return gi to ti
    }

    private fun saveProgress(item: PlayItem, force: Boolean) {
        if (item.kind == PlayKind.LIVE) return
        val now = System.currentTimeMillis()
        if (!force && now - lastSaveAt < 10_000) return
        lastSaveAt = now
        val pos = player.currentPosition
        val dur = if (player.duration == C.TIME_UNSET) 0L else player.duration
        onProgress(item, pos, dur)
    }

    private fun startPolling() {
        pollJob = scope.launch {
            while (isActive && !released) {
                delay(500)
                if (released) break
                val item = current ?: continue
                val dur = player.duration
                val live = item.kind == PlayKind.LIVE
                _state.update {
                    it.copy(
                        positionMs = if (live) 0 else player.currentPosition,
                        durationMs = if (dur == C.TIME_UNSET || live) 0 else dur,
                        bufferedMs = if (live) 0 else player.bufferedPosition,
                    )
                }
                val now = System.currentTimeMillis()
                if (player.isPlaying) {
                    if (playingSince == 0L) playingSince = now
                    // Stabil gelaufen: Wiederverbindungszähler zurücksetzen.
                    if (now - playingSince > 10_000) reconnectAttempts = 0
                    // Bild ohne Ton erkennen: Audiospuren vorhanden, aber keine wird abgespielt.
                    if (!silentChecked && !audioDisabledByUs && now - playingSince > 4_000) {
                        silentChecked = true
                        val hasSupportedAudio = audioCandidates().any { it.supported }
                        if (hasSupportedAudio && player.audioFormat == null) {
                            audioFallback("kein Ton erkannt")
                        }
                    }
                    if (!live) saveProgress(item, force = false)
                } else {
                    playingSince = 0L
                }
            }
        }
    }

    private companion object {
        const val MAX_RECONNECTS = 5
    }
}

/** Gut lesbarer Spurname, z. B. "Deutsch · AC3 5.1". */
fun buildTrackLabel(language: String?, label: String?, mime: String?, channels: Int, index: Int): String {
    val lang = language?.takeIf { it.isNotBlank() && it != "und" }?.let {
        val name = Locale.forLanguageTag(it).getDisplayLanguage(Locale.GERMAN)
        name.takeIf { n -> n.isNotBlank() } ?: it
    }
    val base = lang ?: label?.takeIf { it.isNotBlank() } ?: "Spur ${index + 1}"
    val codec = when {
        mime == null -> null
        mime.contains("eac3") -> "E-AC3"
        mime.contains("ac3") -> "AC3"
        mime.contains("mp4a") || mime.contains("aac") -> "AAC"
        mime.contains("dts") -> "DTS"
        mime.contains("truehd") -> "TrueHD"
        mime.contains("mpeg") -> "MP2/MP3"
        mime.contains("opus") -> "Opus"
        else -> null
    }
    val layout = when (channels) {
        1 -> "Mono"
        2 -> "Stereo"
        6 -> "5.1"
        8 -> "7.1"
        0, Format.NO_VALUE -> null
        else -> "${channels}ch"
    }
    val tail = listOfNotNull(codec, layout).joinToString(" ")
    return if (tail.isEmpty()) base else "$base · $tail"
}
