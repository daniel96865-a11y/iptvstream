package de.dgstudios.iptvstream.tv

import androidx.compose.foundation.border
import de.dgstudios.iptvstream.core.ui.AccentBar
import android.view.KeyEvent as AKey
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.animation.core.tween
import android.app.Activity
import android.os.Build
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import de.dgstudios.iptvstream.core.data.PlayKind
import de.dgstudios.iptvstream.core.data.db.EpgEntity
import de.dgstudios.iptvstream.core.player.AudioBoost
import de.dgstudios.iptvstream.core.player.PlayerController
import de.dgstudios.iptvstream.core.player.ExternalPlayer
import de.dgstudios.iptvstream.core.player.StreamStats
import de.dgstudios.iptvstream.core.ui.DelayedSpinner
import de.dgstudios.iptvstream.core.ui.LiveBufferBar
import de.dgstudios.iptvstream.core.ui.StreamInfoOverlay
import de.dgstudios.iptvstream.core.ui.LocalAppStyle
import de.dgstudios.iptvstream.core.ui.NumberOverlay
import de.dgstudios.iptvstream.core.ui.VideoSurface
import de.dgstudios.iptvstream.core.ui.digitOrNull
import de.dgstudios.iptvstream.core.ui.formatClock
import de.dgstudios.iptvstream.core.ui.formatDuration
import de.dgstudios.iptvstream.core.ui.formatProgrammeRange
import de.dgstudios.iptvstream.core.ui.rememberClockTick
import de.dgstudios.iptvstream.core.ui.rememberNumberEntry
import de.dgstudios.iptvstream.core.vm.PlayerUi
import de.dgstudios.iptvstream.core.vm.PlayerViewModel
import kotlinx.coroutines.delay

private enum class PMode { HIDDEN, CONTROLS, PANEL, ARCHIVE }

/** „Letzter Sender“-Taste vieler Fernbedienungen (KeyEvent.KEYCODE_LAST_CHANNEL, ab Android 9 benannt). */
private const val KEY_LAST_CHANNEL = 229

/** So lange (Wiederholungen der Taste) muss ◄ gehalten werden, um zum vorigen Sender zu springen. */
private const val LONG_PRESS_REPEATS = 4

@Composable
fun TvPlayerScreen(onBack: () -> Unit) {
    val vm: PlayerViewModel = viewModel()
    val ui by vm.ui.collectAsStateWithLifecycle()
    val controller by vm.controller.collectAsStateWithLifecycle()
    val leave: () -> Unit = {
        vm.close()
        onBack()
    }

    // Beim Verlassen der App pausieren/freigeben, danach wieder aufnehmen.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, vm) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> vm.onBackground()
                Lifecycle.Event.ON_START -> vm.onForeground()
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(ui.noRequest) { if (ui.noRequest) leave() }

    val ctrl = controller
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (ctrl == null) {
            CircularProgressIndicator(Modifier.align(Alignment.Center), color = Color.White)
        } else {
            TvPlayerContent(vm, ctrl, ui, leave)
        }
    }
}

@Composable
private fun TvPlayerContent(vm: PlayerViewModel, ctrl: PlayerController, ui: PlayerUi, onBack: () -> Unit) {
    val st by ctrl.state.collectAsStateWithLifecycle()
    var mode by remember { mutableStateOf(PMode.CONTROLS) }
    var interaction by remember { mutableIntStateOf(0) }
    val root = remember { FocusRequester() }
    val retryFocus = remember { FocusRequester() }
    val nextFocus = remember { FocusRequester() }
    val panelFocus = remember { FocusRequester() }
    val archiveFocus = remember { FocusRequester() }
    val item = ui.item
    val isLive = item?.kind == PlayKind.LIVE
    val liveEdge = isLive && !ui.catchingUp
    var programmes by remember { mutableStateOf<List<EpgEntity>?>(null) }
    val tick by rememberClockTick(15_000)
    val numEntry = rememberNumberEntry { n -> vm.zapNumber(n) }
    val offerActive = ui.nextOfferSeconds > 0
    val hasError = st.error != null
    var infoOn by remember { mutableStateOf(ui.settings.streamInfo) }
    LaunchedEffect(infoOn) { ctrl.setInfoVisible(infoOn) }
    val context = LocalContext.current

    // Bildwiederholrate „Immer“: Fernseher auf eine zur Bildrate passende Rate schalten, beim Verlassen zurück.
    val fps = st.info.fps
    DisposableEffect(ui.settings.afr, fps, st.videoWidth) {
        val activity = context as? Activity
        if (ui.settings.afr == "always" && fps != null && activity != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val window = activity.window
            val display = window.decorView.display
            val current = display?.mode
            if (display != null && current != null) {
                val sameSize = display.supportedModes.filter {
                    it.physicalWidth == current.physicalWidth && it.physicalHeight == current.physicalHeight
                }
                val rate = StreamStats.pickRefreshRate(fps, sameSize.map { it.refreshRate })
                val mode = sameSize.firstOrNull { it.refreshRate == rate }
                if (mode != null && mode.modeId != window.attributes.preferredDisplayModeId) {
                    window.attributes = window.attributes.also { it.preferredDisplayModeId = mode.modeId }
                }
            }
        }
        onDispose {}
    }
    DisposableEffect(Unit) {
        onDispose {
            val activity = context as? Activity
            if (activity != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
                activity.window.attributes.preferredDisplayModeId != 0
            ) {
                activity.window.attributes = activity.window.attributes.also { it.preferredDisplayModeId = 0 }
            }
        }
    }

    fun openExternal() {
        val url = ctrl.currentStreamUrl() ?: return
        if (!ExternalPlayer.open(context, url, item?.title, ctrl.currentUserAgent())) {
            vm.toast("Kein externer Player gefunden (z. B. VLC installieren)")
        }
    }

    // Zurück: Panel schließen, sonst Player verlassen (ExoPlayer wird mit dem ViewModel freigegeben).
    BackHandler {
        if (numEntry.text.isNotEmpty()) numEntry.cancel() else onBack()
    }
    BackHandler(enabled = mode == PMode.PANEL || mode == PMode.ARCHIVE) { mode = PMode.CONTROLS }

    LaunchedEffect(mode) {
        if (mode == PMode.ARCHIVE) {
            programmes = null
            programmes = vm.archiveProgrammes()
        }
    }

    LaunchedEffect(st.ended, item?.kind) {
        if (st.ended && item?.kind == PlayKind.MOVIE) onBack()
    }

    // Beim Senderwechsel kurz die Infoleiste zeigen.
    LaunchedEffect(item?.id) {
        if (item == null) return@LaunchedEffect
        if (mode == PMode.ARCHIVE) {
            mode = PMode.CONTROLS
            interaction++
        } else if (mode != PMode.PANEL) {
            mode = PMode.CONTROLS
            interaction++
        }
    }

    // Steuerung nach 5 s ausblenden (Filme nur, solange sie laufen).
    LaunchedEffect(mode, interaction, st.playing, isLive) {
        if (mode == PMode.CONTROLS && (isLive || st.playing)) {
            delay(5_000)
            mode = PMode.HIDDEN
        }
    }

    // Tastenfokus: Wenn kein Panel/Fehler/Angebot Fokus braucht, sammelt die Fläche alle Tasten ein.
    LaunchedEffect(mode, hasError, offerActive, programmes) {
        when {
            mode == PMode.ARCHIVE && programmes != null -> archiveFocus.tryFocus()
            mode == PMode.PANEL -> panelFocus.tryFocus()
            hasError -> retryFocus.tryFocus()
            offerActive -> nextFocus.tryFocus()
            else -> root.tryFocus()
        }
    }

    fun show() {
        if (mode == PMode.HIDDEN) mode = PMode.CONTROLS
        interaction++
    }

    fun seek(deltaMs: Long) {
        if (liveEdge) return
        ctrl.seekBy(deltaMs)
        mode = PMode.CONTROLS
        interaction++
    }

    val onKey: (KeyEvent) -> Boolean = key@{ ev ->
        if (ev.type != KeyEventType.KeyDown) return@key false
        val code = ev.nativeKeyEvent.keyCode
        val rep = ev.nativeKeyEvent.repeatCount
        val step = when {
            rep > 8 -> 60_000L
            rep > 3 -> 30_000L
            else -> 10_000L
        }
        val digit = ev.digitOrNull()
        when {
            digit != null -> {
                if (isLive) numEntry.digit(digit)
                true
            }
            code == AKey.KEYCODE_DPAD_CENTER || code == AKey.KEYCODE_ENTER || code == AKey.KEYCODE_NUMPAD_ENTER -> {
                if (numEntry.text.isNotEmpty()) {
                    numEntry.commit()
                } else if (rep == 0) {
                    if (liveEdge) {
                        mode = if (mode == PMode.CONTROLS) PMode.HIDDEN else PMode.CONTROLS
                    } else {
                        ctrl.togglePlay()
                        mode = PMode.CONTROLS
                    }
                    interaction++
                }
                true
            }
            code == KEY_LAST_CHANNEL -> {
                if (isLive) vm.jumpBack()
                true
            }
            code == AKey.KEYCODE_MENU -> {
                mode = PMode.PANEL
                true
            }
            code == AKey.KEYCODE_MEDIA_PLAY_PAUSE -> {
                ctrl.togglePlay()
                show()
                true
            }
            code == AKey.KEYCODE_MEDIA_PLAY -> {
                if (!st.playing) ctrl.togglePlay()
                show()
                true
            }
            code == AKey.KEYCODE_MEDIA_PAUSE -> {
                ctrl.pause()
                show()
                true
            }
            code == AKey.KEYCODE_MEDIA_NEXT -> {
                vm.next()
                true
            }
            code == AKey.KEYCODE_MEDIA_PREVIOUS -> {
                vm.prev()
                true
            }
            code == AKey.KEYCODE_CHANNEL_UP -> {
                vm.next()
                true
            }
            code == AKey.KEYCODE_CHANNEL_DOWN -> {
                vm.prev()
                true
            }
            code == AKey.KEYCODE_MEDIA_FAST_FORWARD -> {
                seek(30_000)
                true
            }
            code == AKey.KEYCODE_MEDIA_REWIND -> {
                seek(-30_000)
                true
            }
            code == AKey.KEYCODE_DPAD_UP -> {
                if (isLive) vm.next() else show()
                true
            }
            code == AKey.KEYCODE_DPAD_DOWN -> {
                if (isLive) vm.prev() else mode = PMode.PANEL
                true
            }
            code == AKey.KEYCODE_DPAD_RIGHT -> {
                if (liveEdge) mode = PMode.PANEL else seek(step)
                true
            }
            code == AKey.KEYCODE_DPAD_LEFT -> {
                // Live: kurz drücken zeigt die Leiste, lang halten springt zum vorigen Sender.
                if (liveEdge) {
                    if (rep == 0) show() else if (rep == LONG_PRESS_REPEATS) vm.jumpBack()
                } else {
                    seek(-step)
                }
                true
            }
            else -> false
        }
    }

    Box(Modifier.fillMaxSize()) {
        VideoSurface(
            player = ctrl.player,
            resize = st.resize,
            videoWidth = st.videoWidth,
            videoHeight = st.videoHeight,
            modifier = Modifier.fillMaxSize(),
        )

        // Unsichtbare Fläche, die alle Fernbedienungstasten annimmt.
        Box(
            Modifier
                .fillMaxSize()
                .focusRequester(root)
                .onPreviewKeyEvent(onKey)
                .focusProperties { canFocus = mode != PMode.PANEL && mode != PMode.ARCHIVE }
                .focusable(),
        )

        DelayedSpinner(st.loading && !hasError, Modifier.align(Alignment.Center), strokeWidth = 4.dp)

        if (infoOn) {
            StreamInfoOverlay(
                st.info,
                Modifier.align(Alignment.TopStart).padding(start = tvSafe.horizontal, top = tvSafe.vertical),
                fontSize = 15.sp,
            )
        }

        val notice = st.notice ?: ui.toast
        if (notice != null) {
            Text(
                notice,
                color = Color.White,
                fontSize = 18.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 70.dp)
                    .background(Color.Black.copy(alpha = 0.65f), RoundedCornerShape(50))
                    .padding(horizontal = 22.dp, vertical = 10.dp),
            )
        }

        // Infoleiste beim Umschalten ohne Einblend-Verzögerung.
        AnimatedVisibility(
            visible = mode == PMode.CONTROLS,
            modifier = Modifier.align(Alignment.BottomCenter),
            enter = fadeIn(tween(80)),
            exit = fadeOut(),
        ) {
            ControlsBar(
                ui, st.playing, st.positionMs, st.durationMs, st.bufferedMs, st.bufferMaxMs,
                tick, isLive, st.signalVideo, st.signalAudio,
            )
        }

        AnimatedVisibility(
            visible = mode == PMode.ARCHIVE,
            modifier = Modifier.align(Alignment.CenterEnd),
            enter = fadeIn(),
            exit = fadeOut(),
        ) {
            ArchivePanel(
                channel = item?.title.orEmpty(),
                programmes = programmes,
                firstFocus = archiveFocus,
                onPick = { programme ->
                    if (vm.playCatchup(programme)) mode = PMode.CONTROLS
                },
            )
        }

        AnimatedVisibility(
            visible = mode == PMode.PANEL,
            modifier = Modifier.align(Alignment.CenterEnd),
            enter = fadeIn(),
            exit = fadeOut(),
        ) {
            SettingsPanel(
                vm, ctrl, ui, st.resize, st.audioTracks, st.textTracks, st.textEnabled, isLive, panelFocus,
                infoOn = infoOn,
                onToggleInfo = { infoOn = !infoOn },
                onExternal = {
                    mode = PMode.CONTROLS
                    openExternal()
                },
                onJumpBack = {
                    vm.jumpBack()
                    mode = PMode.CONTROLS
                },
                onArchive = { mode = PMode.ARCHIVE },
                onLive = {
                    vm.returnToLive()
                    mode = PMode.CONTROLS
                },
            )
        }

        val err = st.error
        if (err != null) {
            Column(
                Modifier
                    .align(Alignment.Center)
                    .width(620.dp)
                    .background(Color(0xE6101420), RoundedCornerShape(22.dp))
                    .padding(28.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("Wiedergabe nicht möglich", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                Text(err, color = Color(0xE6FFFFFF), fontSize = 18.sp, textAlign = TextAlign.Center)
                Spacer(Modifier.height(20.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    TvButton("Erneut versuchen", { ctrl.retry() }, requester = retryFocus)
                    TvButton("Zurück", onBack)
                }
            }
        }

        if (offerActive) {
            Column(
                Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = tvSafe.horizontal, bottom = maxOf(tvSafe.vertical, 40.dp))
                    .background(Color(0xE6101420), RoundedCornerShape(20.dp))
                    .padding(20.dp),
            ) {
                Text("Nächste Episode in ${ui.nextOfferSeconds} s", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 20.sp)
                Text(ui.nextTitle.orEmpty(), color = Color(0xCCFFFFFF), fontSize = 16.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.width(380.dp))
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    TvButton("Jetzt", { vm.playNextNow() }, requester = nextFocus)
                    TvButton("Abbrechen", { vm.cancelNextOffer() })
                }
            }
        }

        NumberOverlay(numEntry.text, Modifier.padding(horizontal = 36.dp, vertical = 15.dp))
    }
}

@Composable
private fun ControlsBar(
    ui: PlayerUi,
    playing: Boolean,
    positionMs: Long,
    durationMs: Long,
    bufferedMs: Long,
    bufferMaxMs: Long,
    tick: Long,
    isLive: Boolean,
    signalVideo: String?,
    signalAudio: String?,
) {
    val s = LocalAppStyle.current
    val item = ui.item
    Box(
        Modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xB3000000))))
            .padding(horizontal = 40.dp)
            .padding(top = 60.dp, bottom = 28.dp),
    ) {
        // Glas-Leiste wie im Design „Glas & Verlauf“: dunkle, fast deckende Fläche (kein echter Blur,
        // das wäre auf alten Fire-TV-Sticks zu teuer), heller Rand, Schrift bleibt gut lesbar.
        val panel = RoundedCornerShape(28.dp)
        Column(
            Modifier
                .fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Color(0xD91A1733), Color(0xE60E1424))), panel)
                .border(1.dp, Brush.linearGradient(listOf(Color(0x66DCD6FF), Color(0x14DCD6FF))), panel)
                .padding(horizontal = 32.dp, vertical = 24.dp),
        ) {
            if (isLive && !ui.catchingUp) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if ((item?.number ?: 0) > 0) {
                        Text("${item?.number}", color = s.accent, fontSize = 34.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.width(16.dp))
                    }
                    Text(item?.title.orEmpty(), color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                SignalLine(signalVideo, signalAudio)
                val nn = ui.nowNext
                val now = nn?.now
                if (now != null) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "${formatClock(now.start)}–${formatClock(now.stop)}  ${now.title}",
                        color = Color.White, fontSize = 20.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    AccentBar(
                        progress = { nn?.progress(tick) ?: 0f },
                        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp).height(5.dp),
                        trackColor = Color.White.copy(alpha = 0.25f),
                    )
                    val next = nn?.next
                    if (next != null) {
                        Text("Danach  ${formatClock(next.start)}  ${next.title}", color = Color(0xCCFFFFFF), fontSize = 17.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                if (bufferMaxMs > 0L) {
                    Spacer(Modifier.height(8.dp))
                    LiveBufferBar(bufferedMs, bufferMaxMs, barHeight = 5.dp, fontSize = 15.sp)
                }
                Spacer(Modifier.height(10.dp))
                Text(
                    if (ui.archive) {
                        "▲▼ Sender   ·   ◄ halten: voriger Sender   ·   ► Zurückblicken   ·   Zahlen: Sender direkt"
                    } else {
                        "▲▼ Sender   ·   ◄ halten: voriger Sender   ·   ► Einstellungen   ·   Zahlen: Sender direkt"
                    },
                    color = Color(0x99FFFFFF),
                    fontSize = 15.sp,
                )
            } else if (ui.catchingUp) {
                Text(item?.title.orEmpty(), color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                SignalLine(signalVideo, signalAudio)
                val sub = item?.subtitle
                if (!sub.isNullOrBlank()) Text(sub, color = Color(0xCCFFFFFF), fontSize = 19.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(10.dp))
                val fraction = if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
                AccentBar(
                    progress = { fraction },
                    modifier = Modifier.fillMaxWidth().height(6.dp),
                    trackColor = Color.White.copy(alpha = 0.25f),
                )
                Spacer(Modifier.height(6.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text((if (playing) "▶ " else "❚❚ ") + formatDuration(positionMs), color = Color.White, fontSize = 18.sp)
                    Text(formatDuration(durationMs), color = Color(0xCCFFFFFF), fontSize = 18.sp)
                }
                Spacer(Modifier.height(6.dp))
                Text("◄ ► Spulen   ·   ▲▼ Sender   ·   Menü: Zurückblicken oder Live", color = Color(0x99FFFFFF), fontSize = 15.sp)
            } else {
                Text(item?.title.orEmpty(), color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                SignalLine(signalVideo, signalAudio)
                val sub = item?.subtitle
                if (!sub.isNullOrBlank()) Text(sub, color = Color(0xCCFFFFFF), fontSize = 19.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(10.dp))
                val fraction = if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
                AccentBar(
                    progress = { fraction },
                    modifier = Modifier.fillMaxWidth().height(6.dp),
                    trackColor = Color.White.copy(alpha = 0.25f),
                )
                Spacer(Modifier.height(6.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text((if (playing) "▶ " else "❚❚ ") + formatDuration(positionMs), color = Color.White, fontSize = 18.sp)
                    Text(formatDuration(durationMs), color = Color(0xCCFFFFFF), fontSize = 18.sp)
                }
                Spacer(Modifier.height(6.dp))
                Text("◄ ► Spulen   ·   OK Pause   ·   ▼ Einstellungen", color = Color(0x99FFFFFF), fontSize = 15.sp)
            }
        }
    }
}

@Composable
private fun SignalLine(video: String?, audio: String?) {
    val text = listOfNotNull(video, audio).joinToString("   ·   ")
    if (text.isEmpty()) return
    Spacer(Modifier.height(6.dp))
    Text(text, color = Color(0xCCFFFFFF), fontSize = 16.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
}

@Composable
private fun ArchivePanel(
    channel: String,
    programmes: List<EpgEntity>?,
    firstFocus: FocusRequester,
    onPick: (EpgEntity) -> Unit,
) {
    val s = LocalAppStyle.current
    val now = System.currentTimeMillis()
    Column(
        Modifier
            .padding(end = tvSafe.horizontal, top = tvSafe.vertical, bottom = tvSafe.vertical)
            .width(520.dp)
            .fillMaxHeight()
            .background(s.backgroundColors[0], RoundedCornerShape(22.dp))
            .padding(18.dp),
    ) {
        Text("Zurückblicken", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
        if (channel.isNotBlank()) {
            Text(channel, color = Color(0xB3FFFFFF), fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.height(10.dp))
        when {
            programmes == null -> CircularProgressIndicator(Modifier.align(Alignment.CenterHorizontally), color = s.accent)
            programmes.isEmpty() -> Text(
                "Keine Sendungen im Archiv. Das Programm muss geladen sein.",
                color = Color(0xB3FFFFFF),
                fontSize = 16.sp,
                modifier = Modifier.focusRequester(firstFocus).focusable(),
            )
            else -> LazyColumn(Modifier.fillMaxWidth().weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                // Stabile Schlüssel (Beginn/Ende), damit Fokus und Scrollposition beim Nachladen bleiben.
                val keys = uniqueKeys(programmes) { "${it.start}-${it.stop}" }
                itemsIndexed(programmes, key = { index, _ -> keys[index] }) { index, programme ->
                    val running = programme.stop > now
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .then(if (index == 0) Modifier.focusRequester(firstFocus) else Modifier)
                            .tvFocus(onClick = { onPick(programme) }, shape = RoundedCornerShape(14.dp), opaque = true)
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                    ) {
                        Text(
                            formatProgrammeRange(programme.start, programme.stop, now) + if (running) " · läuft" else "",
                            color = if (running) s.accent else Color(0xB3FFFFFF),
                            fontSize = 14.sp,
                        )
                        Text(
                            programme.title,
                            color = Color.White,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

/** Eindeutige, stabile LazyColumn-Schlüssel: gleiche Namen (z. B. zwei Tonspuren „Deutsch“) bekommen #2, #3 … */
internal fun <T> uniqueKeys(items: List<T>, base: (T) -> String): List<String> {
    val seen = HashMap<String, Int>()
    return items.map { item ->
        val k = base(item)
        val n = (seen[k] ?: 0) + 1
        seen[k] = n
        if (n == 1) k else "$k#$n"
    }
}

private sealed interface PanelEntry {
    class Header(val text: String) : PanelEntry
    class Info(val text: String) : PanelEntry
    class Option(val label: String, val selected: Boolean, val onClick: () -> Unit) : PanelEntry
}

@Composable
private fun SettingsPanel(
    vm: PlayerViewModel,
    ctrl: PlayerController,
    ui: PlayerUi,
    resize: String,
    audio: List<de.dgstudios.iptvstream.core.player.TrackOption>,
    text: List<de.dgstudios.iptvstream.core.player.TrackOption>,
    textEnabled: Boolean,
    isLive: Boolean,
    firstFocus: FocusRequester,
    infoOn: Boolean,
    onToggleInfo: () -> Unit,
    onExternal: () -> Unit,
    onJumpBack: () -> Unit,
    onArchive: () -> Unit,
    onLive: () -> Unit,
) {
    val s = LocalAppStyle.current
    val entries = buildList {
        if (isLive && ui.canJumpBack) {
            add(PanelEntry.Option("↩ Voriger Sender" + (ui.previousTitle?.let { ": $it" } ?: ""), false, onJumpBack))
        }
        if (ui.archive) add(PanelEntry.Option("Zurückblicken", false, onArchive))
        if (ui.catchingUp) add(PanelEntry.Option("Zum Live-TV", false, onLive))
        if (isLive) {
            add(PanelEntry.Option(if (ui.isFavorite) "★ Aus Favoriten entfernen" else "☆ Zu Favoriten hinzufügen", ui.isFavorite) { vm.toggleFavorite() })
        }
        add(PanelEntry.Header("Ton lauter"))
        add(PanelEntry.Info("Wird für diesen Sender gemerkt."))
        if (ui.settings.passthrough) add(PanelEntry.Info("Bei Audio-Passthrough ohne Wirkung."))
        for ((id, label) in AudioBoost.choices) {
            add(PanelEntry.Option(label, ui.audioBoost == id) { vm.setAudioBoost(id) })
        }
        add(PanelEntry.Header("Tonspur"))
        if (audio.isEmpty()) add(PanelEntry.Info("Keine Tonspuren gefunden"))
        for (t in audio) {
            add(PanelEntry.Option(t.label, t.selected) { ctrl.selectAudio(t.id) })
        }
        add(PanelEntry.Header("Bildmodus"))
        for ((id, label) in listOf("fit" to "Anpassen", "fill" to "Füllen", "stretch" to "Strecken", "original" to "Original")) {
            add(PanelEntry.Option(label, resize == id) { vm.setResize(id) })
        }
        add(PanelEntry.Header("Untertitel"))
        add(PanelEntry.Option("Aus", !textEnabled) { ctrl.selectText(null) })
        for (t in text) add(PanelEntry.Option(t.label, t.selected && textEnabled) { ctrl.selectText(t.id) })
        if (text.isEmpty()) add(PanelEntry.Info("Keine Untertitel verfügbar"))
        add(PanelEntry.Header("Mehr"))
        add(PanelEntry.Option("Stream-Infos anzeigen", infoOn, onToggleInfo))
        add(PanelEntry.Option("In externem Player öffnen (VLC …)", false, onExternal))
    }
    val firstOption = entries.indexOfFirst { it is PanelEntry.Option }

    Column(
        Modifier
            .padding(end = tvSafe.horizontal, top = tvSafe.vertical, bottom = tvSafe.vertical)
            .width(460.dp)
            .fillMaxHeight()
            .background(Color(0xEE0E1322), RoundedCornerShape(22.dp))
            .padding(18.dp),
    ) {
        Text("Einstellungen", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(10.dp))
        LazyColumn(Modifier.fillMaxWidth().weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            val keys = uniqueKeys(entries) {
                when (it) {
                    is PanelEntry.Header -> "h:" + it.text
                    is PanelEntry.Info -> "i:" + it.text
                    is PanelEntry.Option -> "o:" + it.label
                }
            }
            itemsIndexed(entries, key = { index, _ -> keys[index] }, contentType = { _, e -> e::class }) { index, e ->
                when (e) {
                    is PanelEntry.Header ->
                        Text(e.text, color = s.accent, fontSize = 17.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 10.dp, start = 4.dp))
                    is PanelEntry.Info ->
                        Text(e.text, color = Color(0xB3FFFFFF), fontSize = 16.sp, modifier = Modifier.padding(start = 6.dp))
                    is PanelEntry.Option ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .height(48.dp)
                                .then(if (index == firstOption) Modifier.focusRequester(firstFocus) else Modifier)
                                .tvFocus(onClick = e.onClick, shape = RoundedCornerShape(12.dp), selected = e.selected)
                                .padding(horizontal = 14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(e.label, color = Color.White, fontSize = 18.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                            if (e.selected) Text("✓", color = s.accent, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                        }
                }
            }
        }
    }
}
