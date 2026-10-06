package de.dgstudios.iptvstream.tv

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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import de.dgstudios.iptvstream.core.data.PlayKind
import de.dgstudios.iptvstream.core.data.db.EpgEntity
import de.dgstudios.iptvstream.core.player.PlayerController
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

@Composable
fun TvPlayerScreen(onBack: () -> Unit) {
    val vm: PlayerViewModel = viewModel()
    val ui by vm.ui.collectAsStateWithLifecycle()
    val controller by vm.controller.collectAsStateWithLifecycle()

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

    LaunchedEffect(ui.noRequest) { if (ui.noRequest) onBack() }

    val ctrl = controller
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (ctrl == null) {
            CircularProgressIndicator(Modifier.align(Alignment.Center), color = Color.White)
        } else {
            TvPlayerContent(vm, ctrl, ui, onBack)
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
                if (liveEdge) show() else seek(-step)
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

        if (st.loading && !hasError) {
            CircularProgressIndicator(Modifier.align(Alignment.Center), color = Color.White, strokeWidth = 4.dp)
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

        AnimatedVisibility(
            visible = mode == PMode.CONTROLS,
            modifier = Modifier.align(Alignment.BottomCenter),
            enter = fadeIn(),
            exit = fadeOut(),
        ) {
            ControlsBar(ui, st.playing, st.positionMs, st.durationMs, tick, isLive)
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
                    .padding(end = 48.dp, bottom = 40.dp)
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
private fun ControlsBar(ui: PlayerUi, playing: Boolean, positionMs: Long, durationMs: Long, tick: Long, isLive: Boolean) {
    val s = LocalAppStyle.current
    val item = ui.item
    Box(
        Modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xE6000000))))
            .padding(horizontal = 48.dp)
            .padding(top = 60.dp, bottom = 36.dp),
    ) {
        Column(Modifier.fillMaxWidth()) {
            if (isLive && !ui.catchingUp) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if ((item?.number ?: 0) > 0) {
                        Text("${item?.number}", color = s.accent, fontSize = 34.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.width(16.dp))
                    }
                    Text(item?.title.orEmpty(), color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                val nn = ui.nowNext
                val now = nn?.now
                if (now != null) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "${formatClock(now.start)}–${formatClock(now.stop)}  ${now.title}",
                        color = Color.White, fontSize = 20.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    LinearProgressIndicator(
                        progress = { nn?.progress(tick) ?: 0f },
                        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp).height(5.dp),
                        color = s.accent,
                        trackColor = Color.White.copy(alpha = 0.25f),
                    )
                    val next = nn?.next
                    if (next != null) {
                        Text("Danach  ${formatClock(next.start)}  ${next.title}", color = Color(0xCCFFFFFF), fontSize = 17.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                Spacer(Modifier.height(10.dp))
                Text(
                    if (ui.archive) {
                        "▲▼ Sender   ·   ► Zurückblicken   ·   Zahlen: Sender direkt wählen"
                    } else {
                        "▲▼ Sender   ·   ► Einstellungen   ·   Zahlen: Sender direkt wählen"
                    },
                    color = Color(0x99FFFFFF),
                    fontSize = 15.sp,
                )
            } else if (ui.catchingUp) {
                Text(item?.title.orEmpty(), color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val sub = item?.subtitle
                if (!sub.isNullOrBlank()) Text(sub, color = Color(0xCCFFFFFF), fontSize = 19.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(10.dp))
                val fraction = if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
                LinearProgressIndicator(
                    progress = { fraction },
                    modifier = Modifier.fillMaxWidth().height(6.dp),
                    color = s.accent,
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
                val sub = item?.subtitle
                if (!sub.isNullOrBlank()) Text(sub, color = Color(0xCCFFFFFF), fontSize = 19.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(10.dp))
                val fraction = if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
                LinearProgressIndicator(
                    progress = { fraction },
                    modifier = Modifier.fillMaxWidth().height(6.dp),
                    color = s.accent,
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
            .padding(end = 48.dp, top = 27.dp, bottom = 27.dp)
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
                itemsIndexed(programmes) { index, programme ->
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
    onArchive: () -> Unit,
    onLive: () -> Unit,
) {
    val s = LocalAppStyle.current
    val entries = buildList {
        if (ui.archive) add(PanelEntry.Option("Zurückblicken", false, onArchive))
        if (ui.catchingUp) add(PanelEntry.Option("Zum Live-TV", false, onLive))
        if (isLive) {
            add(PanelEntry.Option(if (ui.isFavorite) "★ Aus Favoriten entfernen" else "☆ Zu Favoriten hinzufügen", ui.isFavorite) { vm.toggleFavorite() })
        }
        add(PanelEntry.Header("Bildmodus"))
        for ((id, label) in listOf("fit" to "Anpassen", "fill" to "Füllen", "stretch" to "Strecken", "original" to "Original")) {
            add(PanelEntry.Option(label, resize == id) { vm.setResize(id) })
        }
        add(PanelEntry.Header("Audio"))
        if (audio.isEmpty()) add(PanelEntry.Info("Keine Audiospuren gefunden"))
        for (t in audio) {
            add(PanelEntry.Option(t.label + if (!t.supported) " (nicht unterstützt)" else "", t.selected) {
                if (t.supported) ctrl.selectAudio(t.id)
            })
        }
        add(PanelEntry.Header("Untertitel"))
        add(PanelEntry.Option("Aus", !textEnabled) { ctrl.selectText(null) })
        for (t in text) add(PanelEntry.Option(t.label, t.selected && textEnabled) { ctrl.selectText(t.id) })
        if (text.isEmpty()) add(PanelEntry.Info("Keine Untertitel verfügbar"))
    }
    val firstOption = entries.indexOfFirst { it is PanelEntry.Option }

    Column(
        Modifier
            .padding(end = 48.dp, top = 27.dp, bottom = 27.dp)
            .width(460.dp)
            .fillMaxHeight()
            .background(Color(0xEE0E1322), RoundedCornerShape(22.dp))
            .padding(18.dp),
    ) {
        Text("Einstellungen", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(10.dp))
        LazyColumn(Modifier.fillMaxWidth().weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            itemsIndexed(entries) { index, e ->
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
