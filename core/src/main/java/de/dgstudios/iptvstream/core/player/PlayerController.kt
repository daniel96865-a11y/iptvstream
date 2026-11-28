package de.dgstudios.iptvstream.core.player

import android.app.ActivityManager
import android.content.Context
import android.content.res.Configuration
import android.net.Uri
import android.os.Handler
import android.os.SystemClock
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
import androidx.media3.common.util.Util
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
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory
import androidx.media3.extractor.ts.TsExtractor
import de.dgstudios.iptvstream.core.data.PlayItem
import de.dgstudios.iptvstream.core.data.PlayKind
import de.dgstudios.iptvstream.core.data.remote.Net
import de.dgstudios.iptvstream.core.settings.AppSettings
import de.dgstudios.iptvstream.core.update.installedVersionName
import de.dgstudios.iptvstream.core.util.StreamHeaders
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
    /** Puffer-Diagnose (Einstellung), eine Zeile; null wenn aus. */
    val bufferDiag: String? = null,
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
    @Volatile private var streamUserAgent: String? = null
    @Volatile private var streamReferrer: String? = null
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
    private val bufferingWatchdog = BufferProgressWatchdog()
    @Volatile private var seamlessLive = false
    /** Live-Rand: Player-Thread liest das für den Startvorlauf ([LiveAwareLoadControl]). */
    @Volatile private var liveMode = false
    /** Der Anbieter hat die Verbindung beendet; der Rest des Puffers läuft noch. */
    @Volatile private var eofPending = false
    private val connections: StreamConnections
    /** Zuletzt hat der Anbieter ein Verbindungslimit gemeldet (elapsedRealtime, 0 = nie). */
    @Volatile private var limitedAt = 0L
    /** Live-TS: Vorlauf nach dem Start durch leicht langsameres Tempo aufbauen ([LiveCushion]). */
    private val cushion: LiveCushion
    private val cushionOn = settings.liveSlow
    private var appliedSpeed = 1f
    private var lastPlaybackState = Player.STATE_IDLE
    /** Puffer-Diagnose einblenden (Einstellung). */
    private val bufferDiagOn = settings.bufferDiag
    private val flow = FlowMeter()
    private val bytesLoaded = java.util.concurrent.atomic.AtomicLong()
    private var rateBytes = 0L
    private var rateAt = 0L
    private var networkKbps: Int? = null
    private var reconnectsThisChannel = 0
    /** Live-TS: bei Daten-Stillstand sauber neu aufbauen (Einstellung). */
    private val stallReconnectOn = settings.stallReconnect
    private var stallReconnectLive = false
    private val stallGuard = SupplyStallGuard()
    /** Gesetzt direkt vor dem absichtlichen Abbruch; [ReconnectingDataSource] meldet dann ein Ende. */
    private val stallKick = java.util.concurrent.atomic.AtomicBoolean(false)
    /** Der letzte Abbruch kam von uns (Stillstand), nicht vom Anbieter. Nur für die Diagnose. */
    @Volatile private var lastEndWasKick = false
    /** Live-TS: dasselbe MediaItem, das bei einem Ende als nächstes Stück angeschlossen wird. */
    private var liveMediaItem: MediaItem? = null
    /** Letzte PTS der alten Verbindung, damit die neue den Vorlauf überspringt. */
    private val liveJoin = LiveTsJoin()
    /** PTS-Filter nur bei Live-TS; sonst würde er HLS/MP4/MKV als 188-Byte-Pakete zerlegen. */
    @Volatile private var liveTsFilter = false
    private var chainCount = 0
    private var chainJob: Job? = null
    /** Der Anbieter hat eine angeschlossene Verbindung abgelehnt (406 …): für diesen Sender wie in 1.0.77 weiter. */
    @Volatile private var chainBlocked = false
    private var emptyChains = 0
    private var lastByteCount = -1L
    private var lastByteAt = 0L
    /** Bis hierhin (elapsedRealtime) darf der Bild-steht-Wächter nicht den Decoder wechseln. */
    private var freezeGraceUntil = 0L
    private var decoderSwitchesThisChannel = 0
    /** Grund des letzten Decoder-Wechsels (oder warum keiner), für die Puffer-Diagnose. */
    private var decoderNote: String? = null
    private val seamlessCount = java.util.concurrent.atomic.AtomicInteger()
    /** Grund der letzten Neuverbindung, für die Puffer-Diagnose. */
    @Volatile private var lastFix: String? = null
    /** Letzter erkannter Ruckler-Grund, für die Puffer-Diagnose („Ruckler: …“). */
    @Volatile private var hitchReason: String? = null
    private var hitchUntil = 0L
    private var videoDecoderInits = 0
    private var readyOnce = false
    private val byteCounter = object : androidx.media3.datasource.TransferListener {
        override fun onTransferInitializing(source: DataSource, dataSpec: androidx.media3.datasource.DataSpec, isNetwork: Boolean) {}
        override fun onTransferStart(source: DataSource, dataSpec: androidx.media3.datasource.DataSpec, isNetwork: Boolean) {}
        override fun onBytesTransferred(source: DataSource, dataSpec: androidx.media3.datasource.DataSpec, isNetwork: Boolean, bytesTransferred: Int) {
            if (isNetwork) bytesLoaded.addAndGet(bytesTransferred.toLong())
        }
        override fun onTransferEnd(source: DataSource, dataSpec: androidx.media3.datasource.DataSpec, isNetwork: Boolean) {}
    }
    private var reconnectAttempts = 0
    /** Wie oft der Player hinter das Live-Fenster gefallen ist, seit er zuletzt stabil lief. */
    private var behindLiveWindowCount = 0
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

        // Pro Sender kann eine eigene Kennung/Referer gelten (iptv-org-Listen). Die Fabrik wird je
        // Verbindung neu gebaut und liest die Werte des gerade laufenden Senders.
        // Gleicher Client wie bisher (15 s Lese-Zeitlimit), nur einmal gebaut und über [connections]:
        // so lassen sich hängende alte Verbindungen bei einer Neuverbindung sofort schließen.
        connections = StreamConnections(
            okHttp.newBuilder().readTimeout(STREAM_READ_TIMEOUT_S, java.util.concurrent.TimeUnit.SECONDS).build(),
        )
        val httpFactory = DataSource.Factory {
            val extra = streamReferrer?.let { mapOf("Referer" to it) }.orEmpty()
            TsOverlapSkipDataSource.wrap(
                DefaultDataSource.Factory(
                    context,
                    // Kürzeres Lese-Zeitlimit für Streams: Hängt die Verbindung, kommt nach 15 s ein Fehler
                    // und die Wiederverbindung greift, statt 45 s lang mit leerem Puffer zu warten.
                    OkHttpDataSource.Factory(connections).setUserAgent(streamUserAgent ?: Net.userAgentFor(settings.userAgent))
                        .setDefaultRequestProperties(extra)
                        .setTransferListener(byteCounter),
                ).createDataSource(),
                liveJoin,
                liveTsFilter,
            )
        }
        // Live-TS: Beendet der Anbieter die Verbindung, wird sofort neu geöffnet, ohne den Player anzufassen.
        val dataSource = DataSource.Factory {
            ReconnectingDataSource(
                httpFactory,
                enabled = { seamlessLive && !chainBlocked },
                stallEnabled = { stallReconnectLive },
                takeStallKick = { stallKick.getAndSet(false).also { if (it) lastEndWasKick = true } },
                onReopen = { liveJoin.arm(sameStream = true) },
                reopenMaxDelayMs = { CHAIN_DELAY_MS },
                shouldWaitBeforeReopen = {
                    try {
                        player.totalBufferedDuration > CHAIN_EARLY_BUFFER_MS
                    } catch (_: Exception) {
                        true
                    }
                },
                onReconnect = { bytes, millis, kicked ->
                    seamlessCount.incrementAndGet()
                    lastFix = if (kicked) "Stillstand → nahtlos" else "Anbieter-Ende → nahtlos"
                    val why = if (kicked) "Daten-Stillstand" else "Anbieter beendete"
                    diag.event("$why nach ${bytes / 1024} KB / ${millis / 1000} s, nahtlos im selben Strom (Decoder bleibt)")
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
            .setLoadErrorHandlingPolicy(
                // Wie DefaultLoadErrorHandlingPolicy(6); nur bei „Verbindung belegt“ (406, 429 …) mit Pause.
                StreamErrorPolicy { code ->
                    limitedAt = android.os.SystemClock.elapsedRealtime()
                    // Lehnt der Anbieter eine angeschlossene Verbindung ab, ist die alte Sitzung bei ihm noch
                    // nicht frei. Dann für diesen Sender nicht mehr anschließen (zurück zum Verhalten aus 1.0.77).
                    if ((chainCount > 0 || seamlessCount.get() > 0) && !chainBlocked) {
                        chainBlocked = true
                        seamlessLive = false
                        diag.issue("Anschließen abgeschaltet: Anbieter meldet HTTP $code")
                        scope.launch {
                            // Abgelehntes angeschlossenes Stück entfernen; der Rest des Puffers läuft, danach
                            // verbindet der Weg aus 1.0.77 neu (bei fast leerem Puffer, mit Pause).
                            val n = player.mediaItemCount
                            val i = player.currentMediaItemIndex
                            if (!released && n > i + 1) {
                                player.removeMediaItems(i + 1, n)
                                eofPending = true
                            }
                        }
                    }
                    diag.issue("Anbieter: Verbindung belegt (HTTP $code)")
                },
            )

        // Wie bis 1.0.52: Minimum und Maximum getrennt, Zeit vor Größe. Die Gleichsetzung in 1.0.53 hat
        // das Ruckeln nicht behoben (der Anbieter beendet den Strom unabhängig vom Lesen) und ist zurückgenommen.
        val (minMs, maxMs, wantedBytes) = PlaybackBuffer.spec(settings.buffer)
        val capped = PlaybackBuffer.capBytes(wantedBytes, memoryClass)
        val bytes = if (compat) minOf(capped, CompatMode.BUFFER_BYTES) else capped
        liveBufferMaxMs = maxMs.toLong()
        val (cushionBase, cushionStep, cushionCap) = PlaybackBuffer.cushion(settings.buffer)
        cushion = LiveCushion(cushionBase, cushionStep, cushionCap.coerceAtMost(maxMs - 3_000L))
        val (liveStartMs, liveAfterRebufferMs) = PlaybackBuffer.liveStart(settings.buffer)
        val loadControl = LiveAwareLoadControl(
            DefaultLoadControl.Builder()
                .setBufferDurationsMs(minMs, maxMs, 1_000, 2_500)
                .setTargetBufferBytes(bytes)
                .setPrioritizeTimeOverSizeThresholds(true)
                .build(),
            liveStartMs = liveStartMs,
            liveAfterRebufferMs = liveAfterRebufferMs,
            isLive = { liveMode },
            liveMaxMs = maxMs.toLong(),
            targetBytes = bytes,
        )

        var params = trackSelector.buildUponParameters().setMaxAudioChannelCount(maxChannels)
        if (settings.audioLang != "auto") params = params.setPreferredAudioLanguage(settings.audioLang)
        if (compat) params = params.setMaxVideoSize(CompatMode.MAX_WIDTH, CompatMode.MAX_HEIGHT)
        trackSelector.setParameters(params)

        player = ExoPlayer.Builder(context, AppRenderersFactory(context, settings.passthrough, videoDecoders, audioDecoders))
            .setTrackSelector(trackSelector)
            .setLoadControl(loadControl)
            // Media3 (seit 1.9) bricht mit ERROR_CODE_TIMEOUT ab, wenn die Position 10 s steht. Unsere eigenen
            // Wächter (Hänger 6 s, Standbild 8 s, Puffer 10 s) sollen zuerst greifen und neu verbinden.
            .setStuckPlayingDetectionTimeoutMs(STUCK_PLAYING_TIMEOUT_MS)
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
        behindLiveWindowCount = 0
        stall.reset()
        frames.reset()
        dropWindowSince = 0L
        bufferingWatchdog.reset()
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
        val hdr = StreamHeaders.parse(item.url)
        streamUserAgent = hdr.userAgent
        streamReferrer = hdr.referrer
        val live = liveEdge(item)
        liveMode = live
        eofPending = false
        val streamType = streamType(item.url)
        // Nahtlos im selben Extraktor (kein Renderer-Reset beim Anschluss): wenn „Bei Abbruch
        // oder Datenstillstand neu verbinden“ an ist (Standard) oder die Test-Einstellung
        // „Nahtlose Neuverbindung“. Der PTS-Filter verwirft Anbieter-Vorlauf, ohne auf das
        // nächste Schlüsselbild zu warten (sonst eine Lücke von bis zu einer GOP, oft ~1 s).
        stallReconnectLive = live && stallReconnectOn && streamType == C.CONTENT_TYPE_OTHER
        seamlessLive = live && streamType == C.CONTENT_TYPE_OTHER && (stallReconnectLive || settings.seamless)
        liveTsFilter = live && streamType == C.CONTENT_TYPE_OTHER
        stallGuard.reset()
        stallKick.set(false)
        lastEndWasKick = false
        liveMediaItem = null
        liveJoin.reset()
        chainCount = 0
        emptyChains = 0
        chainJob?.cancel()
        chainBlocked = false
        lastByteCount = -1L
        freezeGraceUntil = 0L
        decoderSwitchesThisChannel = 0
        decoderNote = null
        seamlessCount.set(0)
        lastFix = null
        hitchReason = null
        hitchUntil = 0L
        videoDecoderInits = 0
        readyOnce = false
        cushion.reset()
        flow.reset()
        reconnectsThisChannel = 0
        networkKbps = null
        rateAt = 0L
        lastPlaybackState = Player.STATE_IDLE
        if (appliedSpeed != 1f) {
            appliedSpeed = 1f
            player.setPlaybackSpeed(1f)
        }
        diag.format = when (streamType) {
            C.CONTENT_TYPE_HLS -> "HLS"
            C.CONTENT_TYPE_DASH -> "DASH"
            C.CONTENT_TYPE_OTHER -> "TS"
            else -> "Adaptiv"
        }
        diag.agent = settings.userAgent
        val mediaItem = MediaItem.Builder()
            .setUri(hdr.url)
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
                            // Nie schneller als Echtzeit: Mit 1,02 holt Media3 (HLS mit Zeitstempeln) zum
                            // Zielabstand auf und spielt dabei den Puffer stetig leer.
                            .setMaxPlaybackSpeed(1f)
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
        liveMediaItem = if (stallReconnectLive) mediaItem else null
        // Senderwechsel: die alte Verbindung sofort schließen (nicht erst nach dem Lese-Zeitlimit), sonst zählt der
        // Anbieter sie noch mit und lehnt die neue mit 406 ab. Der Start selbst bleibt wie in 1.0.77.
        connections.closeAll()
        player.setMediaItem(mediaItem, if (startMs > 0) startMs else C.TIME_UNSET)
        player.prepare()
        player.playWhenReady = true
    }

    fun retry() {
        if (released) return
        reconnectAttempts = 0
        bufferingWatchdog.reset()
        _state.update { it.copy(error = null, loading = true) }
        connections.closeAll()
        trimToCurrent()
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
        bufferingWatchdog.reset()
        reconnectJob?.cancel()
        reconnectJob = null
        current?.let { saveProgress(it, force = true) }
        player.pause()
        if (liveEdge(current)) {
            stopStream()
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
        chainJob?.cancel()
        player.release()
        // Hängende Verbindung nicht bis zum Lese-Zeitlimit offen lassen (zählt beim Anbieter mit).
        connections.closeAll()
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
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            // Angeschlossenes Stück läuft: das abgespielte vorherige entfernen.
            if (reason != Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) return
            val index = player.currentMediaItemIndex
            if (index <= 0) return
            freezeGraceUntil = SystemClock.elapsedRealtime() + FREEZE_GRACE_MS
            stallGuard.reset()
            // Kein Seek: Live-TS ist nicht seekbar, Media3 würde das als Sprung auf 0 werten
            // und den ganzen Puffer verwerfen. Bei anderer Zeitachse (MISMATCH) einfach
            // weiterlaufen – das neue Stück hat eine eigene Zeitachse.
            if (liveJoin.mismatch()) {
                lastFix = "Anschluss (neue Zeitachse)"
                diag.event("Zeitstempel passen nicht, neue Verbindung läuft ohne Sprung (Puffer ${player.totalBufferedDuration / 1000} s)")
            } else if (liveJoin.joined()) {
                val gap = liveJoin.keyframeGapMs()
                if (gap >= 200L) {
                    noteHitch("Lücke bis Schlüsselbild (${gap} ms)")
                    diag.event("Angeschlossene Verbindung ohne Rücksprung, ${gap} ms bis zum Schlüsselbild (Puffer ${player.totalBufferedDuration / 1000} s)")
                } else {
                    noteHitch("Anschluss (Decoder neu)")
                    diag.event("Angeschlossene Verbindung ohne Rücksprung (Puffer ${player.totalBufferedDuration / 1000} s)")
                }
            } else {
                noteHitch("Anschluss (Decoder neu)")
                diag.event("Angeschlossene Verbindung läuft (Puffer ${player.totalBufferedDuration / 1000} s)")
            }
            scope.launch { if (!released && player.currentMediaItemIndex > 0) player.removeMediaItems(0, player.currentMediaItemIndex) }
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            when (playbackState) {
                Player.STATE_READY -> {
                    lastPlaybackState = playbackState
                    _state.update { it.copy(loading = false, error = null) }
                }
                Player.STATE_BUFFERING -> {
                    // Puffer wirklich leer: Vorlauf-Ziel anheben. Ein kurzer BUFFERING-Zustand bei
                    // vollem Puffer (Anschluss, Decoder-Reset) ist ein Ruckler, kein Leerlaufen.
                    if (lastPlaybackState == Player.STATE_READY && liveMode) {
                        val buffered = player.totalBufferedDuration
                        if (buffered < 2_000L) {
                            cushion.onRebuffer()
                            diag.event("Puffer leer (${cushion.rebuffers}.), Ziel-Vorlauf ${cushion.targetMs / 1000} s")
                        } else {
                            noteHitch(hitchReason ?: hitchFromPending())
                            diag.event("Kurz gehakt bei Puffer ${buffered / 1000} s")
                        }
                    }
                    lastPlaybackState = playbackState
                    _state.update { it.copy(loading = true) }
                }
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

        override fun onPositionDiscontinuity(
            oldPosition: Player.PositionInfo,
            newPosition: Player.PositionInfo,
            reason: Int,
        ) {
            bufferingWatchdog.reset()
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
            videoDecoderInits++
            if (videoDecoderInits > 1 && liveMode) noteHitch("Decoder neu")
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
            if (droppedFrames > 0 && liveMode && player.totalBufferedDuration >= 3_000L) {
                noteHitch("Bilder verloren")
            }
        }

        override fun onLoadCompleted(
            eventTime: AnalyticsListener.EventTime,
            loadEventInfo: androidx.media3.exoplayer.source.LoadEventInfo,
            mediaLoadData: androidx.media3.exoplayer.source.MediaLoadData,
        ) {
            // Bei einem Live-.ts-Stream endet das Laden nur, wenn der Server die Verbindung schließt.
            // Bei adaptiven Streams (z. B. HLS und DASH) beendet jedes Segment regulär einen Ladevorgang.
            // Das ist kein Anbieter-Ende und darf keinen Neustart auslösen.
            val item = current
            if (item != null && liveEdge(item) && streamType(item.url) == C.CONTENT_TYPE_OTHER &&
                mediaLoadData.dataType == C.DATA_TYPE_MEDIA
            ) {
                val kicked = lastEndWasKick
                lastEndWasKick = false
                if (!chainNextLive(eventTime.windowIndex, loadEventInfo.bytesLoaded, kicked)) eofPending = true
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
            if (liveMode) noteHitch("Ton-Lücke")
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
        // Passiert das immer wieder (Anbieter-Fenster zu kurz), greift die normale Wiederverbindung
        // mit Wartezeit, statt endlos sofort neu zu laden.
        if (code == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
            behindLiveWindowCount++
            if (behindLiveWindowCount <= MAX_BEHIND_LIVE_WINDOW || !tryReconnect()) {
                connections.closeAll()
                player.seekToDefaultPosition()
                player.prepare()
                player.playWhenReady = true
            }
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
        // ERROR_CODE_TIMEOUT: Player „hängt“ laut Media3 (StuckPlayerException), z. B. nach einem
        // Neustart, wenn der Anbieter die Verbindung geschlossen hat. Still neu verbinden statt Fehlerdialog.
        val stuckTimeout = code == PlaybackException.ERROR_CODE_TIMEOUT
        if ((networkRelated || parseRelated || stuckTimeout) && tryReconnect()) return

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
        stopStream()
        player.prepare()
        player.playWhenReady = true
    }

    /**
     * Bild steht, obwohl der Player "spielt". Zuerst den Ton als Ursache behandeln (hängt auf
     * manchen älteren Geräten bei Mehrkanal), danach neu verbinden, zuletzt eine Meldung.
     */
    private fun onStalled() {
        if (released || inBackground) return
        // Nur wenn Daten im Puffer liegen und trotzdem nichts vorangeht, ist die Tonausgabe verdächtig.
        // Bei fast leerem Puffer liegt es am Strom: neu verbinden statt die Tonspur zu wechseln.
        val audioActive = !audioDisabledByUs && !fallback.gaveUp && player.audioFormat != null
        if (audioActive && player.totalBufferedDuration >= STALL_AUDIO_SUSPECT_BUFFER_MS) {
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
        // Steht das Bild wegen des Stroms (Neuverbindung gerade erst, Daten-Stillstand, Puffer leer),
        // ist der Decoder unschuldig: nicht wechseln. Der Stillstand-/Puffer-Wächter kümmert sich.
        val now = SystemClock.elapsedRealtime()
        val streamCause = when {
            now < freezeGraceUntil -> "kurz nach Neuverbindung"
            stallGuard.stalledMs(now) >= 1_000L -> "Daten-Stillstand"
            player.totalBufferedDuration < FREEZE_MIN_BUFFER_MS -> "Puffer fast leer"
            else -> null
        }
        if (streamCause != null) {
            decoderNote = "Bild stand ($streamCause), Decoder bleibt"
            diag.event("Bild steht, $streamCause: kein Decoder-Wechsel")
            return
        }
        // Höchstens ein Wechsel je Sender. Steht das Bild danach wieder, sauber neu verbinden statt hin und her.
        if (decoderSwitchesThisChannel >= 1) {
            decoderNote = "Bild stand erneut, kein 2. Wechsel → Neustart"
            diag.issue("Bild steht erneut (kein weiterer Decoder-Wechsel)")
            freezeGraceUntil = now + FREEZE_GRACE_MS
            if (tryReconnect()) return
            stuckError("Das Bild bleibt stehen.")
            return
        }
        val mime = player.videoFormat?.sampleMimeType
        val decoder = diag.videoDecoder
        val older = compat
        val software = DevicePlayback.isSoftwareDecoder(decoder)
        if (older && software && !videoDecoders.restarted(mime)) {
            videoDecoders.fallbackFromSoftware(mime, decoder)
            videoDecoders.markRestarted(mime)
            decoderSwitchesThisChannel++
            freezeGraceUntil = now + FREEZE_GRACE_MS
            decoderNote = "Software stand → Hardware"
            diag.issue("Software-Bilddecoder stockt; Hardware-Fallback")
            notice("Software-Bilddecoder stockt. Hardware wird versucht …")
            stall.reset()
            frames.reset()
            stopStream()
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
        decoderSwitchesThisChannel++
        freezeGraceUntil = now + FREEZE_GRACE_MS
        decoderNote = "gewechselt: ${decoder ?: "?"} stand ${frozenFramesMs / 1000} s bei Puffer ${player.totalBufferedDuration / 1000} s"
        diag.issue(if (older) "Software-Bilddecoder" else "Bilddecoder wird gewechselt")
        notice(
            if (older) "Bilddecoder des Sticks bleibt stehen. Software-Decoder wird geladen …"
            else "Bilddecoder wird gewechselt …",
        )
        stall.reset()
        frames.reset()
        stopStream()
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
        val bufferedAtTimeout = player.bufferedPosition
        val scheduled = tryReconnect {
            player.playbackState == Player.STATE_BUFFERING && player.playWhenReady &&
                player.bufferedPosition - bufferedAtTimeout < BufferProgressWatchdog.MIN_PROGRESS_MS
        }
        if (scheduled) return
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

    private fun tryReconnect(stillNeeded: () -> Boolean = { true }): Boolean {
        if (inBackground || released) return false
        if (!settings.reconnect) return false
        if (reconnectAttempts >= MAX_RECONNECTS) return false
        reconnectAttempts++
        diag.event("Neuverbindung $reconnectAttempts, Puffer ${player.totalBufferedDuration / 1000} s, ${diag.lastIssue ?: "ohne Fehler"}")
        val live = liveEdge(current)
        reconnectsThisChannel++
        lastFix = (diag.lastIssue ?: "Fehler") + " → Neustart"
        val limited = limitedAt != 0L && android.os.SystemClock.elapsedRealtime() - limitedAt < LIMIT_RECENT_MS
        val wait = ReconnectBackoff.waitMs(reconnectAttempts, live, limited)
        // Nach einem Fehler ist der Player schon gestoppt: hängende alte Verbindung sofort schließen,
        // damit der Anbieter sie nicht mehr zählt, wenn die neue kommt.
        if (live && player.playbackState == Player.STATE_IDLE) connections.closeAll()
        if (ReconnectBackoff.showNotice(reconnectAttempts, live)) {
            notice(
                "Verbindung unterbrochen – neuer Versuch $reconnectAttempts/$MAX_RECONNECTS …\n${diagnosisLine()}",
                wait + 3_500,
            )
        }
        _state.update { it.copy(loading = true) }
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            delay(wait)
            if (released || inBackground) return@launch
            if (!stillNeeded()) {
                reconnectAttempts = (reconnectAttempts - 1).coerceAtLeast(0)
                _state.update { it.copy(loading = player.playbackState == Player.STATE_BUFFERING) }
                return@launch
            }
            bufferingWatchdog.reset()
            freezeGraceUntil = SystemClock.elapsedRealtime() + FREEZE_GRACE_MS
            if (liveEdge(current)) {
                // Alte Verbindung sicher schließen, damit der Anbieter kein Verbindungslimit meldet.
                stopStream()
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
        reconnectsThisChannel++
        lastFix = "Anbieter-Ende → Neustart"
        freezeGraceUntil = SystemClock.elapsedRealtime() + FREEZE_GRACE_MS
        bufferingWatchdog.reset()
        stopStream()
        player.seekToDefaultPosition()
        player.prepare()
        player.playWhenReady = true
    }

    private fun noteHitch(reason: String) {
        val now = SystemClock.elapsedRealtime()
        if (reason == hitchReason && now < hitchUntil) {
            hitchUntil = now + HITCH_HOLD_MS
            return
        }
        hitchReason = reason
        hitchUntil = now + HITCH_HOLD_MS
        diag.event("Ruckler: $reason")
    }

    private fun currentHitch(): String? {
        val reason = hitchReason ?: return null
        if (SystemClock.elapsedRealtime() > hitchUntil) {
            hitchReason = null
            return null
        }
        return reason
    }

    /** Fallback, wenn der Player bei vollem Puffer kurz in BUFFERING fällt. */
    private fun hitchFromPending(): String = when {
        liveJoin.keyframeGapMs() >= 200L -> "Lücke bis Schlüsselbild (${liveJoin.keyframeGapMs()} ms)"
        player.mediaItemCount > 1 -> "Anschluss (Decoder neu)"
        else -> "Bildstand bei vollem Puffer"
    }

    /**
     * Eine Zeile für die Puffer-Diagnose: Puffer/Ziel, Tempo, Zulauf und Wiedergabe als Vielfaches der
     * Echtzeit, Datenrate, wie oft der Puffer leer lief und neu verbunden wurde.
     */
    private fun refreshBufferDiag(item: PlayItem) {
        val now = android.os.SystemClock.elapsedRealtime()
        // Geladen = Position + gesamter Puffer (auch ein schon angeschlossenes nächstes Stück).
        flow.add(now, player.currentPosition + player.totalBufferedDuration.coerceAtLeast(0L), player.currentPosition)
        val bytes = bytesLoaded.get()
        if (bytes != lastByteCount) {
            lastByteCount = bytes
            lastByteAt = now
        }
        if (rateAt == 0L) {
            rateAt = now
            rateBytes = bytes
        } else if (now - rateAt >= 5_000L) {
            networkKbps = ((bytes - rateBytes) * 8 / (now - rateAt)).toInt()
            rateAt = now
            rateBytes = bytes
        }
        val live = liveEdge(item)
        val fmt = when (streamType(item.url)) {
            C.CONTENT_TYPE_HLS -> "HLS"
            C.CONTENT_TYPE_DASH -> "DASH"
            C.CONTENT_TYPE_OTHER -> "TS"
            else -> "?"
        }
        val videoKbps = player.videoFormat?.let { f -> (f.averageBitrate.takeIf { it > 0 } ?: f.bitrate.takeIf { it > 0 })?.div(1000) }
        val line = BufferDiag.line(
            format = fmt,
            bufferedMs = player.totalBufferedDuration,
            targetMs = if (live && cushionOn) cushion.targetMs else null,
            speed = player.playbackParameters.speed,
            supply = flow.supplyRate(),
            play = flow.playRate(),
            networkKbps = networkKbps,
            videoKbps = videoKbps,
            rebuffers = cushion.rebuffers,
            reconnects = reconnectsThisChannel,
            seamless = seamlessCount.get(),
            // Seit wann kein Byte mehr kam – nur, wenn der Player auch Daten will (nicht bei bewusster Ladepause).
            stallMs = if (player.isLoading || eofPending) now - lastByteAt else 0L,
            chains = chainCount,
            loadPaused = live && !player.isLoading && !eofPending && player.playbackState == Player.STATE_READY,
            lastFix = lastFix,
            decoderNote = decoderNote,
            hitch = currentHitch(),
        )
        _state.update { if (it.bufferDiag == line) it else it.copy(bufferDiag = line) }
    }

    /**
     * Live-TS: Der Player will nachladen, aber seit [SupplyStallGuard.THRESHOLD_MS] kommt kaum ein Byte (der Anbieter
     * hält die Verbindung offen, schickt aber nichts). Dann die hängende Verbindung abbrechen; die Datenquelle meldet
     * das als Ende, und [chainNextLive] schließt sofort eine neue Verbindung als nächstes Stück an, während der
     * restliche Puffer weiterläuft. Nicht beim Start (erst nach dem ersten Bild), nicht bei vollem Puffer.
     */
    private fun checkSupplyStall() {
        val now = SystemClock.elapsedRealtime()
        val active = stallReconnectLive && readyOnce && !inBackground && player.playWhenReady &&
            player.isLoading && player.playbackState != Player.STATE_IDLE
        // Nicht schon nach 3 s bei viel Restpuffer abbrechen: erst wenn der Puffer knapp wird
        // (unter [CHAIN_EARLY_BUFFER_MS]), sonst würde eine neue Verbindung mit Vorlauf zu früh
        // angeschlossen.
        if (stallGuard.observe(now, active, bytesLoaded.get(), player.totalBufferedDuration, CHAIN_EARLY_BUFFER_MS)) {
            val stalledS = stallGuard.stalledMs(now) / 1000
            diag.issue("Kein Datenzulauf seit $stalledS s")
            stallKick.set(true)
            val closed = connections.closeAll()
            if (closed == 0) {
                stallKick.set(false)
            } else {
                lastFix = if (seamlessLive) "Stillstand $stalledS s → nahtlos" else "Stillstand $stalledS s → neue Verbindung"
                diag.event("Daten-Stillstand $stalledS s bei Puffer ${player.totalBufferedDuration / 1000} s: Verbindung abgebrochen")
            }
        }
        // Daten fließen wieder: eine nicht abgeholte Markierung darf keinen späteren Fehler verdecken.
        if (stallGuard.kicks == 0) stallKick.set(false)
    }

    /**
     * Live-TS: Die Verbindung ist zu Ende (Anbieter hat geschlossen oder wir haben wegen Stillstand abgebrochen).
     * Fallback, wenn die nahtlose Neuöffnung im selben Strom nicht greift: dieselbe Adresse als
     * nächstes Stück der Wiedergabeliste anhängen. Media3 lädt es, während der Rest des Puffers läuft.
     * Der Wechsel ist ein neuer Extraktor (Renderer-Reset möglich). Liefern mehrere Verbindungen
     * nacheinander kaum Daten, wird nicht mehr angeschlossen und die normale Wiederverbindung greift.
     */
    private fun chainNextLive(windowIndex: Int, bytes: Long, kicked: Boolean): Boolean {
        val next = liveMediaItem ?: return false
        if (released || inBackground || !settings.reconnect) return false
        // Nur das letzte Stück der Liste bekommt einen Nachfolger.
        if (windowIndex != player.mediaItemCount - 1) return true
        if (chainBlocked) return false
        val now = SystemClock.elapsedRealtime()
        if (limitedAt != 0L && now - limitedAt < LIMIT_RECENT_MS) {
            diag.event("Kein Anschluss: Anbieter meldete eben „belegt“")
            return false
        }
        emptyChains = if (bytes < CHAIN_MIN_BYTES) emptyChains + 1 else 0
        if (emptyChains > MAX_EMPTY_CHAINS) {
            diag.issue("Anbieter liefert nach Neuverbindung nichts")
            return false
        }
        val item = current
        chainJob?.cancel()
        chainJob = scope.launch {
            // Nie zwei Verbindungen gleichzeitig: die alte ist zu Ende; dem Anbieter 2,5 s Zeit geben, die Sitzung
            // freizugeben (sofort neu → 406 auf manchen Anschlüssen). Die Wartezeit entfällt, sobald der Puffer
            // unter 6 s fällt – dann muss die neue Verbindung rechtzeitig sitzen.
            val until = SystemClock.elapsedRealtime() + CHAIN_DELAY_MS
            while (SystemClock.elapsedRealtime() < until && player.totalBufferedDuration > CHAIN_EARLY_BUFFER_MS) delay(100)
            val state = player.playbackState
            if (released || inBackground || chainBlocked || current !== item ||
                state == Player.STATE_IDLE || state == Player.STATE_ENDED ||
                windowIndex != player.mediaItemCount - 1
            ) return@launch
            connections.closeAll()
            chainCount++
            liveJoin.arm(sameStream = false)
            lastFix = if (kicked) "Stillstand → angeschlossen" else "Anbieter-Ende → angeschlossen"
            diag.event("Neue Verbindung angeschlossen ($chainCount.), Puffer ${player.totalBufferedDuration / 1000} s")
            player.addMediaItem(next)
        }
        return true
    }

    /** Vor einem Neuaufbau: nur das laufende Stück behalten. */
    private fun trimToCurrent() {
        val n = player.mediaItemCount
        if (n <= 1) return
        val i = player.currentMediaItemIndex
        if (i + 1 < n) player.removeMediaItems(i + 1, n)
        if (i > 0) player.removeMediaItems(0, i)
    }

    /** Player anhalten und offene Stream-Verbindungen sofort schließen (nicht erst nach dem Lese-Zeitlimit). */
    private fun stopStream() {
        chainJob?.cancel()
        player.stop()
        trimToCurrent()
        val closed = connections.closeAll()
        if (closed > 0) diag.event("Alte Verbindung geschlossen ($closed)")
    }

    /**
     * Live: Tempo nach dem Vorlauf ([LiveCushion]), nur bei laufender Wiedergabe. Filme, Serien und
     * Archiv bleiben immer bei normalem Tempo. Media3 hält die Tonhöhe gleich.
     */
    private fun adjustLiveSpeed(item: PlayItem) {
        // Alle Live-Sender (TS, HLS, DASH). Bei HLS/DASH schaltet ein Tempo unter 1 Media3s eigene
        // Live-Tempo-Steuerung so lange ab; die darf ohnehin nie schneller als 1 spielen.
        val wanted = when {
            !liveEdge(item) || !cushionOn || inBackground -> 1f
            player.playbackState != Player.STATE_READY || !player.playWhenReady -> appliedSpeed
            else -> cushion.speedFor(player.totalBufferedDuration.coerceAtLeast(0L), appliedSpeed)
        }
        if (wanted == appliedSpeed) return
        diag.event(if (wanted < 1f) "Vorlauf aufbauen: Tempo ${(wanted * 100).toInt()} %" else "Vorlauf ${cushion.targetMs / 1000} s erreicht")
        appliedSpeed = wanted
        player.setPlaybackSpeed(wanted)
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
                adjustLiveSpeed(item)
                if (player.playbackState == Player.STATE_READY) readyOnce = true
                checkSupplyStall()
                if (bufferDiagOn) refreshBufferDiag(item)
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
                if (bufferingWatchdog.observe(SystemClock.elapsedRealtime(), buffering, player.bufferedPosition)) {
                    onBufferStuck()
                }
                if (player.isPlaying) {
                    if (playingSince == 0L) playingSince = now
                    // Stabil gelaufen: Wiederverbindungszähler zurücksetzen.
                    // Nur zählen, wenn die Wiedergabe auch vorankommt, sonst endet die Wiederverbindung nie.
                    val moving = stall.idleMs(now) < 3_000L && frames.idleMs(now) < 3_000L
                    if (moving && now - playingSince > 10_000) {
                        reconnectAttempts = 0
                        behindLiveWindowCount = 0
                    }
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

    private fun streamType(url: String): Int =
        if (Urls.isHls(url)) C.CONTENT_TYPE_HLS else Util.inferContentType(Uri.parse(url))

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
        const val MAX_BEHIND_LIVE_WINDOW = 3
        const val FROZEN_FRAMES_MS = 8_000L
        /** Nach einer Neuverbindung oder einem Decoder-Wechsel so lange keinen Decoder-Wechsel. */
        const val FREEZE_GRACE_MS = 8_000L
        /** Darunter steht das Bild wegen des Stroms, nicht wegen des Decoders. */
        const val FREEZE_MIN_BUFFER_MS = 1_000L
        /** Weniger als das in einer Verbindung zählt als „nichts geliefert“. */
        const val CHAIN_MIN_BYTES = 64L * 1024
        const val MAX_EMPTY_CHAINS = 2
        /** Pause zwischen dem Ende einer Verbindung und der angeschlossenen neuen. */
        const val CHAIN_DELAY_MS = 2_500L
        /** Wird der Puffer knapper, sofort anschließen. */
        const val CHAIN_EARLY_BUFFER_MS = 6_000L
        const val HITCH_HOLD_MS = 20_000L
        const val EOF_RECONNECT_BUFFER_MS = 750L
        const val STREAM_READ_TIMEOUT_S = 15L
        const val STALL_AUDIO_SUSPECT_BUFFER_MS = 2_000L
        const val STUCK_PLAYING_TIMEOUT_MS = 30_000
        const val LIMIT_RECENT_MS = 20_000L
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
