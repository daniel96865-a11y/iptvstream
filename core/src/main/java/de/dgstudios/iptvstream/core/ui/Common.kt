package de.dgstudios.iptvstream.core.ui

import android.annotation.SuppressLint
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
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
import androidx.compose.ui.graphics.painter.ColorPainter
import coil.compose.AsyncImage
import coil.compose.SubcomposeAsyncImage
import coil.request.ImageRequest
import coil.size.Size
import androidx.media3.common.Player
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import de.dgstudios.iptvstream.core.container
import de.dgstudios.iptvstream.core.player.UhdPlayback
import de.dgstudios.iptvstream.core.data.NowNext
import de.dgstudios.iptvstream.core.data.db.ProfileEntity
import de.dgstudios.iptvstream.core.data.db.ProfileType
import de.dgstudios.iptvstream.core.settings.AppSettings
import de.dgstudios.iptvstream.core.util.Urls
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

// Fehlalarm dieser Lint-Version: value wird im Block unten gesetzt.
@SuppressLint("ProduceStateDoesNotAssignValue")
@Composable
fun rememberNowNext(profileId: Long, epgKey: String?, tick: Long): State<NowNext?> {
    val repo = LocalContext.current.container.repo
    val initial = repo.peekNowNext(profileId, epgKey)
    val state = produceState(initial, profileId, epgKey, tick / 30_000) {
        value = repo.nowNext(profileId, epgKey)
    }
    return state
}

/** Fortschrittsbalken gleitet, statt zu springen. Bei ausgeschalteten Animationen bleibt er sofort. */
@Composable
fun animatedProgress(target: Float): Float {
    val enabled = LocalAppStyle.current.animations
    val value by animateFloatAsState(
        targetValue = target.coerceIn(0f, 1f),
        animationSpec = if (enabled) tween(600, easing = FastOutSlowInEasing) else tween(0),
        label = "progress",
    )
    return value
}

/** Kurzes Aufpoppen, wenn ein Element neu in die Fläche kommt (zum Beispiel ein Favoriten-Stern). */
@Composable
fun Modifier.popIn(): Modifier {
    val enabled = LocalAppStyle.current.animations
    val scale = remember { Animatable(if (enabled) 0.55f else 1f) }
    LaunchedEffect(enabled) {
        if (enabled) scale.animateTo(1f, spring(dampingRatio = 0.42f, stiffness = 460f))
    }
    return graphicsLayer {
        scaleX = scale.value
        scaleY = scale.value
    }
}

/** Inhalt fährt beim Öffnen leicht nach oben. */
@Composable
fun RiseIn(content: @Composable () -> Unit) {
    val enabled = LocalAppStyle.current.animations
    var visible by remember { mutableStateOf(!enabled) }
    LaunchedEffect(Unit) { visible = true }
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(280)) + slideInVertically(tween(420, easing = FastOutSlowInEasing)) { it / 6 },
    ) {
        content()
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

enum class PosterFrame(val width: Int, val height: Int) {
    Logo(192, 192),
    Poster(540, 810),
    Backdrop(1280, 720),
}

@Composable
fun Poster(
    url: String?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
    frame: PosterFrame = PosterFrame.Poster,
    /** In Listen: kein Shimmer und kein Überblenden, damit das Scrollen nicht ruckelt. */
    lite: Boolean = false,
) {
    val safe = Urls.httpUrl(url)
    if (safe == null) {
        Box(modifier.background(Color(0x22FFFFFF)))
        return
    }
    val context = LocalContext.current
    val fade = LocalAppStyle.current.animations && !lite
    val target = if (lite && frame == PosterFrame.Poster) Size(360, 540) else Size(frame.width, frame.height)
    val model = remember(safe, target, fade) {
        ImageRequest.Builder(context)
            .data(safe)
            .size(target)
            .crossfade(if (fade) 220 else 0)
            .build()
    }
    if (lite) {
        val hold = remember { ColorPainter(Color(0x22FFFFFF)) }
        AsyncImage(
            model = model,
            contentDescription = null,
            modifier = modifier,
            contentScale = contentScale,
            placeholder = hold,
            error = hold,
        )
    } else {
        SubcomposeAsyncImage(
            model = model,
            contentDescription = null,
            modifier = modifier,
            contentScale = contentScale,
            loading = { ShimmerBox(Modifier.fillMaxSize()) },
            error = { Box(Modifier.fillMaxSize().background(Color(0x22FFFFFF))) },
        )
    }
}

@Composable
private fun ShimmerBox(modifier: Modifier = Modifier) {
    val style = LocalAppStyle.current
    if (!style.animations) {
        Box(modifier.background(Color(0x18FFFFFF)))
        return
    }
    val shift by rememberInfiniteTransition(label = "shimmer").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1100, easing = LinearEasing), RepeatMode.Restart),
        label = "shift",
    )
    Box(
        modifier.background(Color(0x18FFFFFF)).drawBehind {
            val band = size.width * 0.55f
            val start = -band + (size.width + band * 2) * shift
            drawRect(
                brush = Brush.linearGradient(
                    listOf(Color(0x10FFFFFF), Color(0x55FFFFFF), Color(0x10FFFFFF)),
                    start = Offset(start, 0f),
                    end = Offset(start + band, size.height),
                ),
            )
        },
    )
}

// ---------------------------------------------------------------------- Video

@OptIn(androidx.media3.common.util.UnstableApi::class)
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
    BoxWithConstraints(modifier = modifier.background(Color.Black), contentAlignment = Alignment.Center) {
        val maxW = if (maxWidth.value.isFinite()) with(density) { maxWidth.roundToPx() } else 0
        val maxH = if (maxHeight.value.isFinite()) with(density) { maxHeight.roundToPx() } else 0
        val fitted = if (original && maxW > 0 && maxH > 0) {
            UhdPlayback.fitInside(videoWidth, videoHeight, maxW, maxH)
        } else {
            0 to 0
        }
        val inner = if (fitted.first > 0 && fitted.second > 0) {
            with(density) { Modifier.requiredSize(fitted.first.toDp(), fitted.second.toDp()) }
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
