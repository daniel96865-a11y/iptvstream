package de.dgstudios.iptvstream

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.OrientationEventListener
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Forward10
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Replay10
import androidx.compose.material.icons.rounded.ScreenRotation
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarBorder
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import de.dgstudios.iptvstream.core.data.PlayKind
import de.dgstudios.iptvstream.core.data.db.EpgEntity
import de.dgstudios.iptvstream.core.player.PlayerController
import de.dgstudios.iptvstream.core.ui.LocalAppStyle
import de.dgstudios.iptvstream.core.ui.VideoSurface
import de.dgstudios.iptvstream.core.ui.formatClock
import de.dgstudios.iptvstream.core.ui.formatDuration
import de.dgstudios.iptvstream.core.ui.formatProgrammeRange
import de.dgstudios.iptvstream.core.vm.PlayerUi
import de.dgstudios.iptvstream.core.vm.PlayerViewModel
import kotlinx.coroutines.delay

private fun Context.findActivity(): Activity? {
    var c: Context? = this
    while (c is ContextWrapper) {
        if (c is Activity) return c
        c = c.baseContext
    }
    return null
}

@Composable
fun PlayerScreen(onBack: () -> Unit) {
    val vm: PlayerViewModel = viewModel()
    val ui by vm.ui.collectAsStateWithLifecycle()
    val controller by vm.controller.collectAsStateWithLifecycle()
    val view = LocalView.current
    val context = LocalContext.current
    var autoRotate by remember {
        mutableStateOf(
            Settings.System.getInt(context.contentResolver, Settings.System.ACCELEROMETER_ROTATION, 0) == 1,
        )
    }
    var userLandscape by remember { mutableStateOf(false) }

    // Player öffnet in der aktuellen Ausrichtung. Querformat nur nach physischer Drehung,
    // und nur wenn die System-Autorotation an ist.
    DisposableEffect(userLandscape) {
        val act = view.context.findActivity()
        val window = act?.window
        val insets = window?.let { WindowCompat.getInsetsController(it, view) }
        insets?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        insets?.hide(WindowInsetsCompat.Type.systemBars())
        val resolver = act?.contentResolver
        val main = Handler(Looper.getMainLooper())
        var lastDegrees = OrientationEventListener.ORIENTATION_UNKNOWN
        fun apply(degrees: Int) {
            val run = {
                if (act != null && resolver != null) {
                    val auto = Settings.System.getInt(resolver, Settings.System.ACCELEROMETER_ROTATION, 0) == 1
                    autoRotate = auto
                    if (auto && userLandscape) userLandscape = false
                    act.requestedOrientation = playerRequestedOrientation(degrees, auto, userLandscape && !auto)
                }
            }
            if (Looper.myLooper() == Looper.getMainLooper()) run() else main.post(run)
        }
        val listener = object : OrientationEventListener(view.context) {
            override fun onOrientationChanged(orientation: Int) {
                lastDegrees = orientation
                apply(orientation)
            }
        }
        if (listener.canDetectOrientation()) listener.enable() else apply(lastDegrees)
        val observer = object : ContentObserver(main) {
            override fun onChange(selfChange: Boolean) {
                apply(lastDegrees)
            }
        }
        resolver?.registerContentObserver(
            Settings.System.getUriFor(Settings.System.ACCELEROMETER_ROTATION),
            false,
            observer,
        )
        onDispose {
            listener.disable()
            resolver?.unregisterContentObserver(observer)
            insets?.show(WindowInsetsCompat.Type.systemBars())
            act?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
    }

    // Beim Wechsel in den Hintergrund Wiedergabe beenden/pausieren, danach wieder aufnehmen.
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
            PlayerContent(
                vm, ctrl, ui, onBack,
                showRotate = !autoRotate,
                landscapeLocked = userLandscape,
                onToggleOrientation = { userLandscape = !userLandscape },
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlayerContent(
    vm: PlayerViewModel,
    ctrl: PlayerController,
    ui: PlayerUi,
    onBack: () -> Unit,
    showRotate: Boolean,
    landscapeLocked: Boolean,
    onToggleOrientation: () -> Unit,
) {
    val s = LocalAppStyle.current
    val st by ctrl.state.collectAsStateWithLifecycle()
    val stateRef = rememberUpdatedState(st)
    var showControls by remember { mutableStateOf(true) }
    var sheet by remember { mutableStateOf(false) }
    var archiveOpen by remember { mutableStateOf(false) }
    var interaction by remember { mutableIntStateOf(0) }
    val item = ui.item
    val isLive = item?.kind == PlayKind.LIVE
    val liveEdge = isLive && !ui.catchingUp

    BackHandler {
        when {
            archiveOpen -> archiveOpen = false
            sheet -> sheet = false
            else -> onBack()
        }
    }

    LaunchedEffect(st.ended, item?.kind) {
        if (st.ended && item?.kind == PlayKind.MOVIE) onBack()
    }

    // Steuerung nach kurzer Zeit ausblenden, solange es läuft.
    LaunchedEffect(ui.item?.id) { archiveOpen = false }

    LaunchedEffect(showControls, st.playing, interaction, sheet, archiveOpen) {
        if (showControls && st.playing && !sheet && !archiveOpen && st.error == null) {
            delay(4_000)
            showControls = false
        }
    }

    Box(Modifier.fillMaxSize()) {
        VideoSurface(
            player = ctrl.player,
            resize = st.resize,
            videoWidth = st.videoWidth,
            videoHeight = st.videoHeight,
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = { showControls = !showControls },
                        onDoubleTap = { offset ->
                            if (!stateRef.value.isLive) {
                                ctrl.seekBy(if (offset.x < size.width / 2) -10_000L else 10_000L)
                                interaction++
                            }
                        },
                    )
                },
        )

        if (st.loading && st.error == null) {
            CircularProgressIndicator(Modifier.align(Alignment.Center), color = Color.White, strokeWidth = 3.dp)
        }

        // Hinweise (Audiowechsel, Wiederverbindung …)
        val notice = st.notice ?: ui.toast
        if (notice != null) {
            Text(
                notice,
                color = Color.White,
                fontSize = 13.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .windowInsetsPadding(WindowInsets.safeDrawing)
                    .padding(top = 56.dp)
                    .glass(RoundedCornerShape(50), strong = true)
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }

        AnimatedVisibility(
            visible = showControls || st.error != null,
            enter = fadeIn(),
            exit = fadeOut(),
        ) {
            Box(Modifier.fillMaxSize()) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                listOf(Color(0xB3000000), Color.Transparent, Color.Transparent, Color(0xCC000000)),
                            ),
                        ),
                )
                BoxWithConstraints(Modifier.fillMaxSize()) {
                    // Hochformat: Bild ist bildschirmbreit und vertikal zentriert (Seitenverhältnis des Videos, sonst 16:9).
                    val portrait = maxHeight > maxWidth
                    val aspect = if (st.videoWidth > 0 && st.videoHeight > 0) st.videoHeight.toFloat() / st.videoWidth else 9f / 16f
                    val videoH = (maxWidth * aspect).coerceAtMost(maxHeight)
                    val videoBottom = (maxHeight + videoH) / 2
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .align(Alignment.TopCenter)
                            .windowInsetsPadding(WindowInsets.safeDrawing)
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                    ) {
                            // Oben: Zurück, Titel, Favorit, Einstellungen
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                GlassIconButton(Icons.AutoMirrored.Rounded.ArrowBack, "Zurück", onBack)
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        item?.title ?: "",
                                        color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold,
                                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    )
                                    val sub = when {
                                        ui.catchingUp -> item?.subtitle
                                        isLive -> ui.nowNext?.now?.title
                                        else -> item?.subtitle
                                    }
                                    if (!sub.isNullOrBlank()) {
                                        Text(sub, color = Color(0xCCFFFFFF), fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    }
                                }
                                if (isLive) {
                                    GlassIconButton(
                                        if (ui.isFavorite) Icons.Rounded.Star else Icons.Rounded.StarBorder,
                                        "Favorit",
                                        { vm.toggleFavorite() },
                                        tint = if (ui.isFavorite) Color(0xFFFFC857) else null,
                                    )
                                    Spacer(Modifier.width(8.dp))
                                }
                                if (showRotate) {
                                    GlassIconButton(
                                        Icons.Rounded.ScreenRotation,
                                        if (landscapeLocked) "Hochformat" else "Vollbild",
                                        onToggleOrientation,
                                    )
                                    Spacer(Modifier.width(8.dp))
                                }
                                GlassIconButton(Icons.Rounded.Tune, "Einstellungen", {
                                    sheet = true
                                    interaction++
                                })
                            }

                    }
                    // Mitte: Transport, mittig auf dem Bild
                    Box(Modifier.align(Alignment.Center).padding(horizontal = 16.dp)) {
                            // Mitte: Transport
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.Center,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                if (ui.canPrev) {
                                    TransportButton(Icons.Rounded.SkipPrevious, 52) { vm.prev(); interaction++ }
                                    Spacer(Modifier.width(18.dp))
                                }
                                if (!liveEdge) {
                                    TransportButton(Icons.Rounded.Replay10, 52) { ctrl.seekBy(-10_000); interaction++ }
                                    Spacer(Modifier.width(18.dp))
                                }
                                TransportButton(if (st.playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, 72, accent = true) {
                                    ctrl.togglePlay()
                                    interaction++
                                }
                                if (!liveEdge) {
                                    Spacer(Modifier.width(18.dp))
                                    TransportButton(Icons.Rounded.Forward10, 52) { ctrl.seekBy(10_000); interaction++ }
                                }
                                if (ui.canNext) {
                                    Spacer(Modifier.width(18.dp))
                                    TransportButton(Icons.Rounded.SkipNext, 52) { vm.next(); interaction++ }
                                }
                            }

                    }
                    Column(
                        if (portrait) {
                            Modifier
                                .fillMaxWidth()
                                .align(Alignment.TopCenter)
                                .padding(top = videoBottom + 12.dp)
                                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom))
                                .padding(horizontal = 16.dp)
                        } else {
                            Modifier
                                .fillMaxWidth()
                                .align(Alignment.BottomCenter)
                                .windowInsetsPadding(WindowInsets.safeDrawing)
                                .padding(horizontal = 16.dp, vertical = 10.dp)
                        },
                    ) {
                            // Unten: Fortschritt
                            if (liveEdge) {
                                LiveInfo(ui)
                                Spacer(Modifier.height(10.dp))
                                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                                    ArchivePill(enabled = ui.archive) {
                                        archiveOpen = true
                                        interaction++
                                    }
                                    if (!ui.archive) {
                                        Text(
                                            "Sender unterstützt kein Zurückblicken",
                                            color = Color.White.copy(alpha = 0.55f),
                                            fontSize = 11.sp,
                                            modifier = Modifier.padding(top = 4.dp),
                                        )
                                    }
                                }
                            } else {
                                SeekBar(ctrl, st.positionMs, st.durationMs) { interaction++ }
                                if (isLive && (ui.archive || ui.catchingUp)) {
                                    Spacer(Modifier.height(8.dp))
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        if (ui.archive) {
                                            ArchiveAction("Zurückblicken", Modifier.weight(1f), filled = false) {
                                                archiveOpen = true
                                                interaction++
                                            }
                                        }
                                        ArchiveAction("Live", Modifier.weight(1f), filled = true) {
                                            vm.returnToLive()
                                            interaction++
                                        }
                                    }
                                }
                            }
                    }
                }
            }
        }

        // Fehleranzeige mit Wiederholen
        val err = st.error
        if (err != null) {
            Column(
                Modifier
                    .align(Alignment.Center)
                    .padding(24.dp)
                    .glass(RoundedCornerShape(22.dp), strong = true)
                    .padding(22.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("Wiedergabe nicht möglich", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(6.dp))
                Text(err, color = Color(0xE6FFFFFF), fontSize = 14.sp, textAlign = TextAlign.Center)
                Spacer(Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    PrimaryButton("Erneut versuchen", onClick = { ctrl.retry() })
                    SecondaryButton("Zurück", onClick = onBack)
                }
            }
        }

        // Nächste Episode
        if (ui.nextOfferSeconds > 0) {
            Column(
                Modifier
                    .align(Alignment.BottomEnd)
                    .windowInsetsPadding(WindowInsets.safeDrawing)
                    .padding(20.dp)
                    .glass(RoundedCornerShape(20.dp), strong = true)
                    .padding(16.dp),
            ) {
                Text("Nächste Episode in ${ui.nextOfferSeconds} s", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                Text(ui.nextTitle.orEmpty(), color = Color(0xCCFFFFFF), fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PrimaryButton("Jetzt", onClick = { vm.playNextNow() })
                    SecondaryButton("Abbrechen", onClick = { vm.cancelNextOffer() })
                }
            }
        }
    }

    if (sheet) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = { sheet = false },
            sheetState = sheetState,
            containerColor = s.backgroundColors[1],
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp)
                    .padding(bottom = 28.dp),
            ) {
                SheetTitle("Bildmodus")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for ((id, label) in listOf("fit" to "Anpassen", "fill" to "Füllen", "stretch" to "Strecken", "original" to "Original")) {
                        GlassChip(label, st.resize == id) { vm.setResize(id) }
                    }
                }
                SheetTitle("Audio")
                if (st.audioTracks.isEmpty()) {
                    Text("Keine Audiospuren gefunden", color = s.onSurfaceDim, fontSize = 14.sp)
                }
                for (t in st.audioTracks) {
                    TrackRow(t.label + if (!t.supported) " (nicht unterstützt)" else "", t.selected) {
                        if (t.supported) ctrl.selectAudio(t.id)
                    }
                }
                SheetTitle("Untertitel")
                TrackRow("Aus", !st.textEnabled) { ctrl.selectText(null) }
                for (t in st.textTracks) {
                    TrackRow(t.label, t.selected && st.textEnabled) { ctrl.selectText(t.id) }
                }
                if (st.textTracks.isEmpty()) {
                    Text("Keine Untertitel verfügbar", color = s.onSurfaceDim, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp))
                }
            }
        }
    }

    if (archiveOpen) {
        ArchiveDialog(vm, item?.title.orEmpty()) { archiveOpen = false }
    }
}

/** Kompakter Glas-Knopf zum Zurückblicken, mittig unter der Programmkarte. */
@Composable
private fun ArchivePill(enabled: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(50)
    val base = Modifier
        .height(40.dp)
        .glass(shape, strong = true)
        .let { if (enabled) it.pressable(onClick) else it.alpha(0.45f) }
        .padding(horizontal = 16.dp)
    Row(base, verticalAlignment = Alignment.CenterVertically) {
        Icon(androidx.compose.material.icons.Icons.Rounded.History, null, tint = Color.White, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text("Zurückblicken", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
    }
}

@Composable
private fun ArchiveAction(
    label: String,
    modifier: Modifier = Modifier,
    filled: Boolean = true,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val s = LocalAppStyle.current
    val base = modifier
        .height(44.dp)
        .clip(RoundedCornerShape(14.dp))
        .background(if (filled && enabled) s.accent else s.backgroundColors[2])
    Box(
        if (enabled) base.pressable(onClick) else base,
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            color = if (enabled) Color.White else Color.White.copy(alpha = 0.45f),
            fontWeight = FontWeight.SemiBold,
            fontSize = 15.sp,
        )
    }
}

@Composable
private fun ArchiveDialog(vm: PlayerViewModel, channel: String, onDismiss: () -> Unit) {
    val s = LocalAppStyle.current
    var loading by remember { mutableStateOf(true) }
    var rows by remember { mutableStateOf<List<EpgEntity>>(emptyList()) }
    LaunchedEffect(channel) {
        loading = true
        rows = vm.archiveProgrammes()
        loading = false
    }
    val now = System.currentTimeMillis()
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize()) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.45f))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onDismiss,
                    ),
            )
            Column(
                Modifier
                    .align(Alignment.Center)
                    .fillMaxWidth(0.92f)
                    .fillMaxHeight(0.82f)
                    .clip(RoundedCornerShape(22.dp))
                    .background(s.backgroundColors[0])
                    .windowInsetsPadding(WindowInsets.safeDrawing)
                    .padding(16.dp),
            ) {
                Text("Zurückblicken", color = s.onSurface, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                if (channel.isNotBlank()) {
                    Text(channel, color = s.onSurfaceDim, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Spacer(Modifier.height(12.dp))
                when {
                    loading -> CircularProgressIndicator(Modifier.align(Alignment.CenterHorizontally), color = s.accent)
                    rows.isEmpty() -> Text(
                        "Keine vergangenen Sendungen gefunden. Programm (EPG) in den Einstellungen neu laden und erneut versuchen.",
                        color = s.onSurfaceDim,
                        fontSize = 15.sp,
                    )
                    else -> LazyColumn(
                        Modifier.weight(1f).fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        contentPadding = PaddingValues(bottom = 8.dp),
                    ) {
                        items(rows, key = { it.start }) { programme ->
                            val running = programme.stop > now
                            Column(
                                Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(18.dp))
                                    .background(s.backgroundColors[2])
                                    .pressable({ if (vm.playCatchup(programme)) onDismiss() })
                                    .padding(horizontal = 14.dp, vertical = 12.dp),
                            ) {
                                Text(
                                    formatProgrammeRange(programme.start, programme.stop, now) + if (running) " · läuft" else "",
                                    color = if (running) s.accent else s.onSurfaceDim,
                                    fontSize = 12.sp,
                                )
                                Text(
                                    programme.title,
                                    color = s.onSurface,
                                    fontSize = 15.sp,
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
    }
}

@Composable
private fun SheetTitle(text: String) {
    val s = LocalAppStyle.current
    Text(text, color = s.onSurface, fontSize = 17.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 16.dp, bottom = 8.dp))
}

@Composable
private fun TrackRow(label: String, selected: Boolean, onClick: () -> Unit) {
    val s = LocalAppStyle.current
    Row(
        Modifier.fillMaxWidth().pressable(onClick).padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = if (selected) s.accent else s.onSurface, fontSize = 15.sp, modifier = Modifier.weight(1f))
        if (selected) Icon(Icons.Rounded.Check, null, tint = s.accent, modifier = Modifier.size(20.dp))
    }
}

@Composable
private fun TransportButton(icon: androidx.compose.ui.graphics.vector.ImageVector, size: Int, accent: Boolean = false, onClick: () -> Unit) {
    val s = LocalAppStyle.current
    val m = if (accent) Modifier.size(size.dp).clip(CircleShape).background(s.accent, CircleShape) else Modifier.size(size.dp).glass(CircleShape, strong = true)
    Box(m.pressable(onClick), contentAlignment = Alignment.Center) {
        Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size((size * 0.5f).dp))
    }
}

@Composable
private fun SeekBar(ctrl: PlayerController, positionMs: Long, durationMs: Long, onInteract: () -> Unit) {
    val s = LocalAppStyle.current
    var dragging by remember { mutableStateOf(false) }
    var dragValue by remember { mutableFloatStateOf(0f) }
    val dur = durationMs.coerceAtLeast(1L).toFloat()
    val shown = if (dragging) dragValue else positionMs.toFloat().coerceIn(0f, dur)
    Column(Modifier.fillMaxWidth()) {
        Slider(
            value = shown,
            onValueChange = {
                dragging = true
                dragValue = it
                onInteract()
            },
            onValueChangeFinished = {
                ctrl.seekTo(dragValue.toLong())
                dragging = false
            },
            valueRange = 0f..dur,
            colors = SliderDefaults.colors(
                thumbColor = Color.White,
                activeTrackColor = s.accent,
                inactiveTrackColor = Color.White.copy(alpha = 0.3f),
            ),
        )
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(formatDuration(shown.toLong()), color = Color.White, fontSize = 12.sp)
            Text(formatDuration(durationMs), color = Color(0xCCFFFFFF), fontSize = 12.sp)
        }
    }
}

@Composable
private fun LiveInfo(ui: PlayerUi) {
    val s = LocalAppStyle.current
    val nn = ui.nowNext
    val cur = nn?.now
    val next = nn?.next
    Column(
        Modifier
            .fillMaxWidth()
            .glass(RoundedCornerShape(18.dp), strong = true)
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        if (cur != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "${formatClock(cur.start)} – ${formatClock(cur.stop)}",
                    color = Color(0xCCFFFFFF), fontSize = 12.sp,
                )
                Spacer(Modifier.width(10.dp))
                Text(cur.title, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            }
            Spacer(Modifier.height(8.dp))
            LinearProgressIndicator(
                progress = { nn?.progress(System.currentTimeMillis()) ?: 0f },
                modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)),
                color = s.accent,
                trackColor = Color.White.copy(alpha = 0.2f),
            )
            if (next != null) {
                Text(
                    "Danach ${formatClock(next.start)}: ${next.title}",
                    color = Color(0xCCFFFFFF), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        } else {
            Text("Keine Programminformationen verfügbar", color = Color(0xCCFFFFFF), fontSize = 13.sp)
        }
    }
}

/** Querformat nur bei physischer Drehung und eingeschalteter Autorotation, sonst Hochformat. */
private fun playerRequestedOrientation(sensorDegrees: Int, autoRotate: Boolean, userLandscape: Boolean): Int {
    if (!autoRotate) {
        return if (userLandscape) {
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        } else {
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
    }
    if (sensorDegrees == OrientationEventListener.ORIENTATION_UNKNOWN) {
        return ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
    }
    val landscape = sensorDegrees in 45..134 || sensorDegrees in 225..314
    return if (landscape) {
        ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
    } else {
        ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
    }
}
