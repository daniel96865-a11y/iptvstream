package de.dgstudios.iptvstream.core.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.Player
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import de.dgstudios.iptvstream.core.container
import de.dgstudios.iptvstream.core.data.NowNext
import de.dgstudios.iptvstream.core.data.db.ProfileEntity
import de.dgstudios.iptvstream.core.data.db.ProfileType
import de.dgstudios.iptvstream.core.settings.AppSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// ---------------------------------------------------------------------- Zeit

@Composable
fun rememberClockTick(periodMs: Long = 30_000): State<Long> =
    produceState(initialValue = System.currentTimeMillis()) {
        while (true) {
            delay(periodMs)
            value = System.currentTimeMillis()
        }
    }

fun formatClock(epochMs: Long): String = SimpleDateFormat("HH:mm", Locale.GERMANY).format(Date(epochMs))

/** Uhrzeit, an einem anderen Tag mit Datum davor. */
fun formatProgrammeRange(startMs: Long, stopMs: Long, nowMs: Long = System.currentTimeMillis()): String {
    val day = SimpleDateFormat("dd.MM.", Locale.GERMANY)
    val clock = SimpleDateFormat("HH:mm", Locale.GERMANY)
    val startLabel = if (day.format(Date(startMs)) == day.format(Date(nowMs))) {
        clock.format(Date(startMs))
    } else {
        day.format(Date(startMs)) + " " + clock.format(Date(startMs))
    }
    return "$startLabel – ${clock.format(Date(stopMs))}"
}

fun formatDuration(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

fun formatRating(r: Double): String? = if (r > 0.0) "★ %.1f".format(Locale.GERMANY, r) else null

// ---------------------------------------------------------------------- EPG

@Composable
fun rememberNowNext(profileId: Long, epgKey: String?, tick: Long): State<NowNext?> {
    val repo = LocalContext.current.container.repo
    return produceState<NowNext?>(null, profileId, epgKey, tick / 30_000) {
        value = repo.nowNext(profileId, epgKey)
    }
}

// ---------------------------------------------------------------------- Uhr

@Composable
fun ClockOverlay(settings: AppSettings, modifier: Modifier = Modifier) {
    if (!settings.clockOn) return
    val tick by rememberClockTick(15_000)
    val align = when (settings.clockPos) {
        "tl" -> Alignment.TopStart
        "bl" -> Alignment.BottomStart
        "br" -> Alignment.BottomEnd
        else -> Alignment.TopEnd
    }
    val alpha = settings.clockAlpha
    Box(
        modifier = modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(12.dp),
        contentAlignment = align,
    ) {
        Text(
            text = formatClock(tick),
            color = Color.White.copy(alpha = alpha),
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier
                .background(Color.Black.copy(alpha = 0.35f * alpha), RoundedCornerShape(10.dp))
                .padding(horizontal = 10.dp, vertical = 4.dp),
        )
    }
}

// ---------------------------------------------------------------------- Bilder

@Composable
fun Poster(
    url: String?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
) {
    val placeholder = ColorPainter(Color(0x22FFFFFF))
    if (url.isNullOrBlank()) {
        Box(modifier.background(Color(0x22FFFFFF)))
        return
    }
    AsyncImage(
        model = url,
        contentDescription = null,
        modifier = modifier,
        contentScale = contentScale,
        placeholder = placeholder,
        error = placeholder,
    )
}

// ---------------------------------------------------------------------- Video

@Composable
fun VideoSurface(
    player: Player,
    resize: String,
    videoWidth: Int,
    videoHeight: Int,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val original = resize == "original" && videoWidth > 0 && videoHeight > 0
    Box(modifier = modifier.background(Color.Black), contentAlignment = Alignment.Center) {
        val inner = if (original) {
            with(density) { Modifier.requiredSize(videoWidth.toDp(), videoHeight.toDp()) }
        } else {
            Modifier.fillMaxSize()
        }
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    useController = false
                    setShutterBackgroundColor(android.graphics.Color.BLACK)
                    keepScreenOn = true
                }
            },
            update = { view ->
                if (view.player !== player) view.player = player
                view.resizeMode = when (resize) {
                    "fill" -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                    "stretch" -> AspectRatioFrameLayout.RESIZE_MODE_FILL
                    else -> AspectRatioFrameLayout.RESIZE_MODE_FIT
                }
            },
            onRelease = { view -> view.player = null },
            modifier = inner,
        )
    }
}

// ---------------------------------------------------------------------- Zahleneingabe (Fernbedienung)

/** Sammelt Ziffern der Fernbedienung und löst nach kurzer Pause (oder OK) die Eingabe aus. */
class NumberEntry(private val scope: CoroutineScope, private val onCommit: (Int) -> Unit) {
    var text by mutableStateOf("")
        private set
    private var job: Job? = null

    fun digit(d: Int) {
        if (text.length >= 5) return
        text += d.toString()
        job?.cancel()
        job = scope.launch {
            delay(1600)
            commit()
        }
    }

    fun commit() {
        job?.cancel()
        val n = text.toIntOrNull()
        text = ""
        if (n != null) onCommit(n)
    }

    fun cancel() {
        job?.cancel()
        text = ""
    }
}

@Composable
fun rememberNumberEntry(onCommit: (Int) -> Unit): NumberEntry {
    val scope = rememberCoroutineScope()
    val callback by rememberUpdatedState(onCommit)
    return remember { NumberEntry(scope) { callback(it) } }
}

/** Liefert 0..9, wenn das Ereignis ein Ziffern-Tastendruck ist. */
fun KeyEvent.digitOrNull(): Int? {
    if (type != KeyEventType.KeyDown) return null
    val code = nativeKeyEvent.keyCode
    return when (code) {
        in android.view.KeyEvent.KEYCODE_0..android.view.KeyEvent.KEYCODE_9 -> code - android.view.KeyEvent.KEYCODE_0
        in android.view.KeyEvent.KEYCODE_NUMPAD_0..android.view.KeyEvent.KEYCODE_NUMPAD_9 ->
            code - android.view.KeyEvent.KEYCODE_NUMPAD_0
        else -> null
    }
}

@Composable
fun NumberOverlay(text: String, modifier: Modifier = Modifier) {
    if (text.isEmpty()) return
    Box(modifier = modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(24.dp), contentAlignment = Alignment.TopCenter) {
        Text(
            text = text,
            color = Color.White,
            fontSize = 44.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier
                .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(16.dp))
                .padding(horizontal = 28.dp, vertical = 10.dp),
        )
    }
}

// ---------------------------------------------------------------------- Profil-Formular

/** Formularzustand, den Handy- und TV-Oberfläche gleichermaßen verwenden. */
class ProfileFormState(private val initial: ProfileEntity?) {
    var name by mutableStateOf(initial?.name ?: "")
    var type by mutableStateOf(initial?.type ?: ProfileType.XTREAM)
    var url by mutableStateOf(initial?.url ?: "")
    var username by mutableStateOf(initial?.username ?: "")
    var password by mutableStateOf(initial?.password ?: "")
    var epgUrl by mutableStateOf(initial?.epgUrl ?: "")
    var busy by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)

    val isEdit: Boolean get() = initial != null

    val valid: Boolean
        get() = if (type == ProfileType.XTREAM) {
            url.isNotBlank() && username.isNotBlank() && password.isNotBlank()
        } else {
            url.isNotBlank()
        }

    fun toEntity() = ProfileEntity(
        id = initial?.id ?: 0L,
        name = name,
        type = type,
        url = url,
        username = username,
        password = password,
        epgUrl = epgUrl,
        lastSync = initial?.lastSync ?: 0L,
        lastEpgSync = initial?.lastEpgSync ?: 0L,
    )
}
