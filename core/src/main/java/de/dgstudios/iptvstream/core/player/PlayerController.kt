package de.dgstudios.iptvstream.core.player

import android.app.ActivityManager
import android.content.Context
import android.content.res.Configuration
import android.os.Handler
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
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.decoder.ffmpeg.FfmpegAudioRenderer
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlaybackException
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.mediacodec.DefaultMediaCodecAdapterFactory
import androidx.media3.exoplayer.mediacodec.MediaCodecDecoderException
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.video.VideoRendererEventListener
import androidx.media3.exoplayer.audio.AudioCapabilities
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory
import androidx.media3.extractor.ts.TsExtractor
import de.dgstudios.iptvstream.core.data.PlayItem
import de.dgstudios.iptvstream.core.data.PlayKind
import de.dgstudios.iptvstream.core.data.remote.Net
import de.dgstudios.iptvstream.core.settings.AppSettings
import de.dgstudios.iptvstream.core.update.installedVersionName
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
import java.util.ArrayList

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
    /** Ziel des Live-Puffers in Millisekunden. 0 außerhalb des Live-Rands. */
    val bufferMaxMs: Long = 0,
    val isLive: Boolean = false,
    val error: String? = null,
    val notice: String? = null,
    val audioTracks: List<TrackOption> = emptyList(),
    val textTracks: List<TrackOption> = emptyList(),
    val textEnabled: Boolean = false,
    val videoWidth: Int = 0,
    val videoHeight: Int = 0,
    /** Auflösung und Bildformat, nur aus dem laufenden Stream. Null, solange nichts gemeldet wurde. */
    val signalVideo: String? = null,
    /** Tonformat, nur aus der gerade laufenden Audiospur. */
    val signalAudio: String? = null,
    val resize: String = "fit",
    val ended: Boolean = false,
)

/**
 * Hardware-Decoder zuerst, FFmpeg nur als Fallback (AC3, E-AC3, MP2, DTS).
 * Passthrough ist standardmäßig aus: [DefaultAudioSink.Builder] mit Context ignoriert
 * [DefaultAudioSink.Builder.setAudioCapabilities] und würde AC3 sonst durchreichen.
 */
@OptIn(UnstableApi::class)
class AppRenderersFactory(
    context: Context,
    private val passthrough: Boolean,
    private val decoderChoice: VideoDecoderChoice,
    private val audioChoice: AudioDecoderChoice = AudioDecoderChoice(),
) : DefaultRenderersFactory(context) {
    init {
        setEnableDecoderFallback(true)
        // Ton: gescheiterte Decoder überspringen, im letzten Schritt nur noch FFmpeg (Software).
        setMediaCodecSelector { mime, secure, tunneling ->
            val all = MediaCodecSelector.DEFAULT.getDecoderInfos(mime, secure, tunneling)
            if (MimeTypes.isAudio(mime)) audioChoice.select(mime, all) { it.name } else all
        }
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

    override fun buildVideoRenderers(
        context: Context,
        extensionRendererMode: Int,
        mediaCodecSelector: MediaCodecSelector,
        enableDecoderFallback: Boolean,
        eventHandler: Handler,
        eventListener: VideoRendererEventListener,
        allowedVideoJoiningTimeMs: Long,
        out: ArrayList<Renderer>,
    ) {
        val adapters = if (DevicePlayback.conservativeVideo()) {
            DefaultMediaCodecAdapterFactory(context).forceDisableAsynchronous()
        } else {
            codecAdapterFactory
        }
        out.add(
            UhdVideoRenderer(
                context,
                adapters,
                mediaCodecSelector,
                allowedVideoJoiningTimeMs,
                enableDecoderFallback,
                eventHandler,
                eventListener,
                MAX_DROPPED_VIDEO_FRAME_COUNT_TO_NOTIFY,
                decoderChoice,
            ),
        )
    }
}

/**
 * Kapselt ExoPlayer: schnelles Umschalten (ein Player, nur Medium wechseln), Audio-Fallback ohne
 * Endlosschleife, automatische Wiederverbindung, Fortschritt und Spurauswahl.
 */
@OptIn(UnstableApi::class)
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

    private val versionName = installedVersionName(context)
    private val audioDecoders = AudioDecoderChoice()
    private var audioDecoderStage = 0
    private val videoDecoders = VideoDecoderChoice()
    private val diag = PlayDiagnostics()
    private val stall = StallDetector()
    private val frames: StallDetector by lazy { StallDetector(frozenFramesMs) }
    private var bufferingSince = 0L
    @Volatile private var seamlessLive = false
    /** Der Anbieter hat die Verbindung beendet; der Rest des Puffers läuft noch. */
    @Volatile private var eofPending = false
    private var reconnectAttempts = 0
    /** Einmal .ts gegen .m3u8 tauschen, wenn der Live-Stream so nicht existiert. */
    private var triedOtherLiveContainer = false
    private var reconnectJob: Job? = null
    private var noticeJob: Job? = null
    private var pollJob: Job? = null
    private var playingSince = 0L
    private var lastSaveAt = 0L
    private var released = false
    private val gain = StreamGain()
    private val liveBufferMaxMs: Long
    private val compat: Boolean
    private val frozenFramesMs: Long
    private var lowQuality = false
    private var lastDropped = 0
    private var lastRendered = 0
    private var dropWindowSince = 0L

    init {
        // Ältere Fire-TV-Decoder können bei H.264-Zwischenzeilenbild stehen bleiben,
        // ohne dass Media3 einen Fehler meldet. Deshalb H.264 dort von Anfang an per Software,
        // aber UHD behält unten weiterhin den Hardware-Decoder, wenn einer verfügbar ist.
        val memoryClass = (context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager)?.memoryClass ?: 0
        val television = context.resources.configuration.uiMode and Configuration.UI_MODE_TYPE_MASK ==
            Configuration.UI_MODE_TYPE_TELEVISION
        compat = CompatMode.enabled(
            settings.compat,
            DevicePlayback.olderFireTv(),
            android.os.Build.SUPPORTED_64_BIT_ABIS.isNullOrEmpty(),
            memoryClass,
            television,
        )
        frozenFramesMs = if (compat) CompatMode.FROZEN_FRAMES_MS else FROZEN_FRAMES_MS
        // Im Kompatibilitätsmodus zuerst der Hardware-Decoder bei höchstens 720p: Ein Software-Decoder
        // schafft auf diesen Geräten 1080p nicht und lässt das Bild ruckeln oder stehen, während der Ton läuft.
        // Bleibt der Hardware-Decoder stehen, schaltet der Wächter auf Software um (onFrozenPicture).

        val httpFactory = DefaultDataSource.Factory(
            context,
            // Kürzeres Lese-Zeitlimit für Streams: Hängt die Verbindung, kommt nach 15 s ein Fehler
            // und die Wiederverbindung greift, statt 45 s lang mit leerem Puffer zu warten.
            OkHttpDataSource.Factory(
                okHttp.newBuilder().readTimeout(STREAM_READ_TIMEOUT_S, java.util.concurrent.TimeUnit.SECONDS).build(),
            ).setUserAgent(Net.userAgentFor(settings.userAgent)),
        )
        // Live-TS: Beendet der Anbieter die Verbindung, wird sofort neu geöffnet, ohne den Player anzufassen.
        val dataSource = DataSource.Factory {
            ReconnectingDataSource(
                httpFactory,
                enabled = { seamlessLive },
                onReconnect = { bytes, millis ->
                    diag.event("Anbieter beendete nach ${bytes / 1024} KB / ${millis / 1000} s, nahtlos neu verbunden")
                },
            )
        }
        val extractors = DefaultExtractorsFactory()
            .setTsExtractorMode(TsExtractor.MODE_SINGLE_PMT)
            .setTsExtractorTimestampSearchBytes(TsExtractor.TS_PACKET_SIZE * 5_000)
            .setTsExtractorFlags(
                DefaultTsPayloadReaderFactory.FLAG_DETECT_ACCESS_UNITS or
                    DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES or
                    DefaultTsPayloadReaderFactory.FLAG_IGNORE_SPLICE_INFO_STREAM,
            )
        val mediaSourceFactory = DefaultMediaSourceFactory(dataSource, extractors)
            .setLoadErrorHandlingPolicy(DefaultLoadErrorHandlingPolicy(6))

        // Wie bis 1.0.52: Minimum und Maximum getrennt, Zeit vor Größe. Die Gleichsetzung in 1.0.53 hat
        // das Ruckeln nicht behoben (der Anbieter beendet den Strom unabhängig vom Lesen) und ist zurückgenommen.
        val (minMs, maxMs, wantedBytes) = PlaybackBuffer.spec(settings.buffer)
        val capped = PlaybackBuffer.capBytes(wantedBytes, memoryClass)
        val bytes = if (compat) minOf(capped, CompatMode.BUFFER_BYTES) else capped
        liveBufferMaxMs = maxMs.toLong()
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(minMs, maxMs, 1_000, 2_500)
            .setTargetBufferBytes(bytes)
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()

        var params = trackSelector.buildUponParameters().setMaxAudioChannelCount(maxChannels)
        if (settings.audioLang != "auto") params = params.setPreferredAudioLanguage(settings.audioLang)
        if (compat) params = params.setMaxVideoSize(CompatMode.MAX_WIDTH, CompatMode.MAX_HEIGHT)
        trackSelector.setParameters(params)

        player = ExoPlayer.Builder(context, AppRenderersFactory(context, settings.passthrough, videoDecoders, audioDecoders))
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
        // WLAN und CPU während der Wiedergabe wach halten. Ohne diese Sperre geht das WLAN nach ein paar
        // Minuten in den Stromsparmodus, der Strom kommt stockend und der Sender hängt.
        player.setWakeMode(C.WAKE_MODE_NETWORK)
        if (compat || DevicePlayback.conservativeVideo()) {
            player.setVideoChangeFrameRateStrategy(C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_OFF)
        }

        // Untertitel: standardmäßig aus, sonst bevorzugte Sprache.
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, settings.subLang == "off")
            .apply { if (settings.subLang != "off") setPreferredTextLanguage(settings.subLang) }
            .build()

    }

    // ------------------------------------------------------------------ Steuerung

    fun load(item: PlayItem, startMs: Long = 0L) {
        triedOtherLiveContainer = false
        open(item, startMs)
    }

    private fun open(item: PlayItem, startMs: Long) {
        if (released) return
        if (Urls.httpUrl(item.url) == null) {
            current = null
            _state.update {
                it.copy(loading = false, playing = false, error = "Die Senderadresse ist ungültig.")
            }
            return
        }
        current?.let { saveProgress(it, force = true) }
        reconnectJob?.cancel()
        fallback.reset()
        maxChannels = if (settings.surround) 6 else 2
        initialAudioDone = false
        initialTextDone = false
        silentChecked = false
        selectedAudioId = null
        reconnectAttempts = 0
        stall.reset()
        frames.reset()
        dropWindowSince = 0L
        bufferingSince = 0L
        diag.reset()
        audioDecoderStage = 0
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
        val live = liveEdge(item)
        eofPending = false
        seamlessLive = live && settings.seamless && !Urls.isHls(item.url)
        diag.format = if (Urls.isHls(item.url)) "HLS" else "TS"
        diag.agent = settings.userAgent
        val mediaItem = MediaItem.Builder()
            .setUri(item.url)
            .setMediaId(item.id)
            .apply {
                if (Urls.isHls(item.url)) setMimeType(MimeTypes.APPLICATION_M3U8)
                if (live) {
                    // Ohne Zielabstand holt der Player zum Live-Rand auf und wirft den Puffer
                    // wieder auf wenige Segmente zusammen, auch wenn groß 90 Sekunden erlaubt.
                    setLiveConfiguration(
                        MediaItem.LiveConfiguration.Builder()
                            .setMinOffsetMs(4_000L.coerceAtMost(liveBufferMaxMs))
                            .setTargetOffsetMs(liveBufferMaxMs)
                            .setMaxOffsetMs(liveBufferMaxMs)
                            .setMinPlaybackSpeed(0.98f)
                            .setMaxPlaybackSpeed(1.02f)
                            .build(),
                    )
                }
            }
            .build()
        _state.update {
            it.copy(
                loading = true, playing = false, error = null, notice = null, ended = false,
                audioTracks = emptyList(), textTracks = emptyList(), positionMs = 0, durationMs = 0,
                bufferedMs = 0, bufferMaxMs = if (liveEdge(item)) liveBufferMaxMs else 0L,
                videoWidth = 0, videoHeight = 0, signalVideo = null, signalAudio = null,
                isLive = liveEdge(item),
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
        if (liveEdge(current)) player.seekToDefaultPosition()
        player.prepare()
        player.playWhenReady = true
    }

    private var stoppedForBackground = false
    @Volatile private var inBackground = false

    /** App geht in den Hintergrund: Fortschritt sichern, Live-Streams ganz beenden (keine offenen Verbindungen). */
    fun onBackground() {
        if (released) return
        inBackground = true
        reconnectJob?.cancel()
        reconnectJob = null
        current?.let { saveProgress(it, force = true) }
        player.pause()
        if (liveEdge(current)) {
            player.stop()
            stoppedForBackground = true
        }
    }

    fun onForeground() {
        if (released) return
        inBackground = false
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

    /**
     * Hebt den Ton dieses Streams an. [warnPassthrough] nur bei einer Auswahl durch den Nutzer,
     * nicht beim Wiederherstellen der gemerkten Stufe.
     */
    fun setAudioBoost(level: String, warnPassthrough: Boolean = false) {
        if (released) return
        val mb = AudioBoost.millibels(level)
        gain.setMillibels(mb)
        if (warnPassthrough && settings.passthrough && mb > 0) {
            notice("Ton lauter gilt nicht, solange Audio-Passthrough an ist.", 6_000)
        }
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
        gain.release()
        player.removeListener(listener)
        player.removeAnalyticsListener(analytics)
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
                    // Ein Live-Sender endet nie. Schließt der Anbieter die Verbindung (Zeit- oder
                    // Verbindungslimit), läuft der Puffer leer und der Player meldet "Ende": neu verbinden.
                    if (liveEdge(current) && !inBackground && !released) {
                        diag.issue("Anbieter hat den Live-Stream beendet")
                        if (tryReconnect()) return
                    }
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
            refreshSignal()
        }

        override fun onAudioSessionIdChanged(audioSessionId: Int) {
            if (!released) gain.setSession(audioSessionId)
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
            refreshSignal()
        }

        override fun onPlayerError(error: PlaybackException) {
            handleError(error)
        }
    }

    private val analytics = object : AnalyticsListener {
        override fun onVideoDecoderInitialized(
            eventTime: AnalyticsListener.EventTime,
            decoderName: String,
            initializedTimestampMs: Long,
            initializationDurationMs: Long,
        ) {
            diag.videoDecoder = decoderName
        }

        override fun onAudioDecoderInitialized(
            eventTime: AnalyticsListener.EventTime,
            decoderName: String,
            initializedTimestampMs: Long,
            initializationDurationMs: Long,
        ) {
            diag.audioDecoder = decoderName
        }

        override fun onDroppedVideoFrames(eventTime: AnalyticsListener.EventTime, droppedFrames: Int, elapsedMs: Long) {
            diag.dropped += droppedFrames
        }

        override fun onLoadCompleted(
            eventTime: AnalyticsListener.EventTime,
            loadEventInfo: androidx.media3.exoplayer.source.LoadEventInfo,
            mediaLoadData: androidx.media3.exoplayer.source.MediaLoadData,
        ) {
            // Bei einem Live-.ts-Stream endet das Laden nur, wenn der Server die Verbindung schließt.
            if (liveEdge(current)) {
                eofPending = true
                diag.event(
                    "Laden beendet: ${loadEventInfo.bytesLoaded / 1024} KB in ${loadEventInfo.loadDurationMs / 1000} s, " +
                        "Puffer ${player.totalBufferedDuration / 1000} s",
                )
            }
        }

        override fun onLoadError(
            eventTime: AnalyticsListener.EventTime,
            loadEventInfo: androidx.media3.exoplayer.source.LoadEventInfo,
            mediaLoadData: androidx.media3.exoplayer.source.MediaLoadData,
            error: java.io.IOException,
            wasCanceled: Boolean,
        ) {
            val http = (error as? HttpDataSource.InvalidResponseCodeException)?.responseCode
            diag.event(
                "Ladefehler ${error.javaClass.simpleName}${http?.let { " HTTP $it" } ?: ""}: " +
                    "${loadEventInfo.bytesLoaded / 1024} KB in ${loadEventInfo.loadDurationMs / 1000} s, " +
                    "Puffer ${player.totalBufferedDuration / 1000} s",
            )
        }

        override fun onAudioUnderrun(
            eventTime: AnalyticsListener.EventTime,
            bufferSize: Int,
            bufferSizeMs: Long,
            elapsedSinceLastFeedMs: Long,
        ) {
            diag.underruns++
        }

        override fun onVideoCodecError(eventTime: AnalyticsListener.EventTime, videoCodecError: Exception) {
            diag.issue("Video-Decoder: ${videoCodecError.message ?: videoCodecError.javaClass.simpleName}")
        }

        override fun onAudioCodecError(eventTime: AnalyticsListener.EventTime, audioCodecError: Exception) {
            diag.issue("Ton-Decoder: ${audioCodecError.message ?: audioCodecError.javaClass.simpleName}")
        }

        override fun onAudioSinkError(eventTime: AnalyticsListener.EventTime, audioSinkError: Exception) {
            diag.issue("Tonausgabe: ${audioSinkError.message ?: audioSinkError.javaClass.simpleName}")
        }
    }

    init {
        player.addListener(listener)
        player.addAnalyticsListener(analytics)
        gain.setSession(player.audioSessionId)
        startPolling()
    }

    private fun handleError(error: PlaybackException) {
        if (released) return
        val code = error.errorCode
        val isLive = liveEdge(current)
        diag.issue(describe(error))

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
        val decoderFailure = code in 4001..4004 && (audioRelated || audioDecoderCause(error))
        if (decoderFailure && !audioDisabledByUs && audioDecoderRecovery(rendererFormat?.sampleMimeType ?: player.audioFormat?.sampleMimeType)) return
        if (audioRelated || (decoderFailure && !audioDisabledByUs)) {
            audioFallback("Fehler $code")
            return
        }

        val status = httpStatus(error)
        val missing = status == 404 || status == 410 || code == PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND
        val parseRelated = isLive && code in 3001..3004
        if (isLive && !triedOtherLiveContainer && (missing || parseRelated)) {
            val alt = current?.url?.let(Urls::alternateLiveContainer)
            if (alt != null && current != null) {
                triedOtherLiveContainer = true
                reconnectAttempts = 0
                notice("Live-Format wird gewechselt …")
                open(current!!.copy(url = alt), 0L)
                return
            }
        }

        val networkRelated = code in 2000..2004
        if ((networkRelated || parseRelated) && tryReconnect()) return

        _state.update {
            it.copy(loading = false, playing = false, error = "${friendlyError(code)}\n${diagnosisLine()}")
        }
    }

    /** Fehler stammt vom Ton-Decoder, auch wenn der Player kein Format dazu meldet. */
    private fun audioDecoderCause(error: PlaybackException): Boolean {
        val audioName = diag.audioDecoder?.lowercase()
        var cause: Throwable? = error
        while (cause != null) {
            val info = (cause as? MediaCodecDecoderException)?.codecInfo?.name?.lowercase()
            if (info != null) {
                return (audioName != null && info == audioName) ||
                    info.contains("aac") || info.contains("audio") || info.contains("mp3") || info.contains("ac3")
            }
            cause = cause.cause
        }
        return diag.audioDecoder != null && diag.videoDecoder == null
    }

    /**
     * Stufen bei Ton-Decoder-Absturz: 1) nächster Hardware-/System-Decoder, 2) FFmpeg-Software-Decoder,
     * 3) Sender einmal komplett neu starten. Danach greift [audioFallback] (Spur, Stereo, zuletzt ohne Ton).
     */
    private fun audioDecoderRecovery(mime: String?): Boolean {
        val item = current ?: return false
        val decoder = diag.audioDecoder
        when (audioDecoderStage) {
            0 -> {
                audioDecoderStage = 1
                if (audioDecoders.reject(mime, decoder)) {
                    notice("Ton-Decoder wird gewechselt …")
                    restartForDecoder()
                    return true
                }
                return audioDecoderRecovery(mime)
            }
            1 -> {
                audioDecoderStage = 2
                audioDecoders.useFfmpegOnly(mime)
                notice("Ton: Software-Decoder wird geladen …")
                restartForDecoder()
                return true
            }
            2 -> {
                audioDecoders.clearFfmpegOnly(mime)
                notice("Sender wird neu gestartet …")
                open(item, 0L)
                audioDecoderStage = 3
                return true
            }
            else -> return false
        }
    }

    private fun restartForDecoder() {
        diag.audioDecoder = null
        if (liveEdge(current)) player.seekToDefaultPosition()
        player.stop()
        player.prepare()
        player.playWhenReady = true
    }

    /**
     * Bild steht, obwohl der Player "spielt". Zuerst den Ton als Ursache behandeln (hängt auf
     * manchen älteren Geräten bei Mehrkanal), danach neu verbinden, zuletzt eine Meldung.
     */
    private fun onStalled() {
        if (released || inBackground) return
        val audioActive = !audioDisabledByUs && !fallback.gaveUp && player.audioFormat != null
        if (audioActive) {
            audioFallback("Wiedergabe hängt")
            return
        }
        if (tryReconnect()) return
        stuckError("Die Wiedergabe hängt.")
    }

    /**
     * Ton läuft, das Bild nicht. Auf älteren Sticks ist das der hängende Hardware-Decoder
     * bei Zwischenzeilenbild. Einmal den Software-Decoder laden, danach eine Meldung.
     */
    private fun onFrozenPicture() {
        if (released || inBackground) return
        val mime = player.videoFormat?.sampleMimeType
        val decoder = diag.videoDecoder
        val older = compat
        val software = DevicePlayback.isSoftwareDecoder(decoder)
        if (older && software && !videoDecoders.restarted(mime)) {
            videoDecoders.fallbackFromSoftware(mime, decoder)
            videoDecoders.markRestarted(mime)
            diag.issue("Software-Bilddecoder stockt; Hardware-Fallback")
            notice("Software-Bilddecoder stockt. Hardware wird versucht …")
            stall.reset()
            frames.reset()
            player.stop()
            player.prepare()
            player.playWhenReady = true
            return
        }
        if (software || videoDecoders.restarted(mime)) {
            diag.issue("Bild steht")
            stuckError("Das Bild bleibt stehen, der Ton läuft. Dieses Bildformat schafft der Fernseher nicht.")
            return
        }
        if (older) videoDecoders.useSoftware(mime)
        val switched = videoDecoders.reject(mime, decoder)
        if (!older && !switched) {
            diag.issue("Bild steht")
            stuckError("Das Bild bleibt stehen, der Ton läuft. Dieses Bildformat schafft der Fernseher nicht.")
            return
        }
        videoDecoders.markRestarted(mime)
        diag.issue(if (older) "Software-Bilddecoder" else "Bilddecoder wird gewechselt")
        notice(
            if (older) "Bilddecoder des Sticks bleibt stehen. Software-Decoder wird geladen …"
            else "Bilddecoder wird gewechselt …",
        )
        stall.reset()
        frames.reset()
        player.stop()
        player.prepare()
        player.playWhenReady = true
    }

    /**
     * Zu viele verworfene Bilder in zehn Sekunden: einmal auf höchstens 480p senken.
     * Das greift nur, wenn der Sender mehrere Qualitätsstufen anbietet (HLS).
     */
    private fun checkOverload(now: Long, running: Boolean, rendered: Int) {
        if (!running) {
            dropWindowSince = 0L
            return
        }
        if (dropWindowSince == 0L) {
            dropWindowSince = now
            lastDropped = diag.dropped
            lastRendered = rendered
            return
        }
        if (now - dropWindowSince < 10_000L) return
        val dropped = diag.dropped - lastDropped
        val shown = rendered - lastRendered
        dropWindowSince = now
        lastDropped = diag.dropped
        lastRendered = rendered
        if (CompatMode.overloaded(dropped, shown)) {
            lowQuality = true
            diag.issue("Gerät überlastet, Auflösung gesenkt")
            player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                .setMaxVideoSize(CompatMode.LOW_WIDTH, CompatMode.LOW_HEIGHT)
                .build()
            notice("Gerät überlastet. Bildqualität wird gesenkt …")
        }
    }

    /** Der Player puffert seit Sekunden, ohne dass Daten ankommen. */
    private fun onBufferStuck() {
        if (released || inBackground) return
        diag.issue("Puffern ohne Daten")
        if (tryReconnect()) return
        stuckError("Der Sender lädt nicht weiter.")
    }

    private fun stuckError(headline: String) {
        _state.update {
            it.copy(loading = false, playing = false, error = "$headline\n${diagnosisLine()}")
        }
    }

    private fun renderedFrames(): Int? {
        val counters = player.videoDecoderCounters ?: return null
        counters.ensureUpdated()
        return counters.renderedOutputBufferCount
    }

    private fun diagnosisLine(): String {
        val size = player.videoSize
        return diag.line(
            version = versionName,
            playbackState = player.playbackState,
            bufferedMs = player.totalBufferedDuration,
            renderedFrames = renderedFrames(),
            video = StreamSignal.videoLine(player.videoFormat, size.width, size.height),
            audio = StreamSignal.audioLine(player.audioFormat),
        )
    }

    private fun describe(error: PlaybackException): String {
        var cause: Throwable = error
        while (cause.cause != null && cause.cause !== cause) cause = cause.cause!!
        val root = if (cause === error) "" else " / ${cause.javaClass.simpleName}"
        return "${error.errorCodeName}$root"
    }

    private fun httpStatus(error: PlaybackException): Int? {
        var cause: Throwable? = error
        while (cause != null) {
            if (cause is HttpDataSource.InvalidResponseCodeException) return cause.responseCode
            cause = cause.cause
        }
        return null
    }

    private fun tryReconnect(): Boolean {
        if (inBackground || released) return false
        if (!settings.reconnect) return false
        if (reconnectAttempts >= MAX_RECONNECTS) return false
        reconnectAttempts++
        diag.event("Neuverbindung $reconnectAttempts, Puffer ${player.totalBufferedDuration / 1000} s, ${diag.lastIssue ?: "ohne Fehler"}")
        val wait = (1000L shl (reconnectAttempts - 1)).coerceAtMost(8000L)
        notice(
            "Verbindung unterbrochen – neuer Versuch $reconnectAttempts/$MAX_RECONNECTS …\n${diagnosisLine()}",
            wait + 3_500,
        )
        _state.update { it.copy(loading = true) }
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            delay(wait)
            if (released || inBackground) return@launch
            if (liveEdge(current)) {
                // Alte Verbindung sicher schließen, damit der Anbieter kein Verbindungslimit meldet.
                player.stop()
                player.seekToDefaultPosition()
            }
            player.prepare()
            player.playWhenReady = true
        }
        return true
    }

    /** Sofortige Neuverbindung zum Live-Rand, ohne Wartezeit und ohne den Versuchszähler zu belasten. */
    private fun reconnectNow() {
        if (released || inBackground || !settings.reconnect) return
        diag.event("Neustart bei Puffer ${player.totalBufferedDuration / 1000} s (Anbieter hat beendet)")
        player.stop()
        player.seekToDefaultPosition()
        player.prepare()
        player.playWhenReady = true
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
                val live = liveEdge(item)
                _state.update {
                    it.copy(
                        positionMs = if (live) 0 else player.currentPosition,
                        durationMs = if (dur == C.TIME_UNSET || live) 0 else dur,
                        bufferedMs = if (live) player.totalBufferedDuration.coerceAtLeast(0L) else player.bufferedPosition,
                        bufferMaxMs = if (live) liveBufferMaxMs else 0L,
                    )
                }
                refreshSignal()
                val now = System.currentTimeMillis()
                val running = player.isPlaying && !inBackground
                if (stall.observe(now, running, player.currentPosition)) {
                    onStalled()
                }
                val rendered = renderedFrames()
                val hasVideo = player.videoFormat != null && rendered != null
                if (frames.observe(now, running && hasVideo, (rendered ?: 0).toLong())) {
                    onFrozenPicture()
                }
                if (compat && !lowQuality) checkOverload(now, running, rendered ?: 0)
                // Anbieter hat beendet: erst neu verbinden, wenn der Puffer fast leer ist. So läuft die Sendung
                // so lange wie möglich weiter und die Lücke beim Neustart bleibt kurz.
                if (eofPending && live && !inBackground && player.totalBufferedDuration <= EOF_RECONNECT_BUFFER_MS) {
                    eofPending = false
                    reconnectNow()
                }
                val buffering = player.playbackState == Player.STATE_BUFFERING && player.playWhenReady && !inBackground
                if (!buffering) {
                    bufferingSince = 0L
                } else if (bufferingSince == 0L) {
                    bufferingSince = now
                } else if (now - bufferingSince > BUFFER_STUCK_MS) {
                    bufferingSince = now
                    onBufferStuck()
                }
                if (player.isPlaying) {
                    if (playingSince == 0L) playingSince = now
                    // Stabil gelaufen: Wiederverbindungszähler zurücksetzen.
                    // Nur zählen, wenn die Wiedergabe auch vorankommt, sonst endet die Wiederverbindung nie.
                    val moving = stall.idleMs(now) < 3_000L && frames.idleMs(now) < 3_000L
                    if (moving && now - playingSince > 10_000) reconnectAttempts = 0
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

    /** Live-Rand. Archiv einer vergangenen Sendung bleibt spulbar. */
    private fun liveEdge(item: PlayItem?): Boolean = item != null && item.kind == PlayKind.LIVE && !item.catchup

    /** Liest Auflösung, Bild- und Tonformat von der Spur, die gerade decodiert wird. */
    private fun refreshSignal() {
        if (released) return
        val size = player.videoSize
        val video = StreamSignal.videoLine(player.videoFormat, size.width, size.height)
        val audio = StreamSignal.audioLine(player.audioFormat)
        _state.update { cur ->
            if (cur.videoWidth == size.width && cur.videoHeight == size.height && cur.signalVideo == video && cur.signalAudio == audio) {
                cur
            } else {
                cur.copy(videoWidth = size.width, videoHeight = size.height, signalVideo = video, signalAudio = audio)
            }
        }
    }

    private companion object {
        const val MAX_RECONNECTS = 8
        const val FROZEN_FRAMES_MS = 8_000L
        const val EOF_RECONNECT_BUFFER_MS = 5_000L
        const val BUFFER_STUCK_MS = 10_000L
        const val STREAM_READ_TIMEOUT_S = 15L
    }
}

/** Gut lesbarer Spurname, z. B. "Deutsch · AC3 5.1". */
fun buildTrackLabel(language: String?, label: String?, mime: String?, channels: Int, index: Int): String {
    val lang = StreamSignal.languageName(language)
        ?: StreamSignal.languageName(label)
        ?: label?.takeIf { it.isNotBlank() }
    val base = lang ?: "Spur ${index + 1}"
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
