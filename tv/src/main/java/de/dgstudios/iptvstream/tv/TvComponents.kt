package de.dgstudios.iptvstream.tv

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import de.dgstudios.iptvstream.core.data.EpgState
import de.dgstudios.iptvstream.core.data.SyncState
import de.dgstudios.iptvstream.core.ui.LocalAppStyle

/**
 * Fokusfähiges Element für die Fernbedienung: deutlich sichtbarer weißer Rahmen,
 * Akzentfüllung und leichte Vergrößerung bei Fokus. OK/Enter löst [onClick] aus,
 * langes OK [onLongClick].
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun Modifier.tvFocus(
    onClick: () -> Unit,
    shape: Shape = RoundedCornerShape(14.dp),
    onLongClick: (() -> Unit)? = null,
    onFocus: ((Boolean) -> Unit)? = null,
    requester: FocusRequester? = null,
    selected: Boolean = false,
    opaque: Boolean = false,
): Modifier {
    val s = LocalAppStyle.current
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (focused && s.animations) 1.035f else 1f,
        animationSpec = tween(120),
        label = "focusScale",
    )
    val card = s.backgroundColors[2]
    val bg = if (opaque) {
        when {
            focused -> solidMix(card, s.accent, 0.72f)
            selected -> solidMix(card, s.accent, 0.5f)
            else -> card
        }
    } else {
        when {
            focused -> s.accent.copy(alpha = 0.60f)
            selected -> s.accent.copy(alpha = 0.22f)
            else -> s.card
        }
    }
    return this
        .then(if (requester != null) Modifier.focusRequester(requester) else Modifier)
        .onFocusChanged {
            focused = it.isFocused
            onFocus?.invoke(it.isFocused)
        }
        .graphicsLayer {
            scaleX = scale
            scaleY = scale
        }
        .clip(shape)
        .background(bg, shape)
        .then(
            when {
                focused -> Modifier.border(3.dp, Color.White, shape)
                selected -> Modifier.border(2.dp, s.accent, shape)
                else -> Modifier
            },
        )
        .combinedClickable(onClick = onClick, onLongClick = onLongClick)
}

private fun solidMix(base: Color, accent: Color, amount: Float): Color = Color(
    red = base.red * (1f - amount) + accent.red * amount,
    green = base.green * (1f - amount) + accent.green * amount,
    blue = base.blue * (1f - amount) + accent.blue * amount,
    alpha = 1f,
)

@Composable
fun TvButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    requester: FocusRequester? = null,
    selected: Boolean = false,
) {
    val s = LocalAppStyle.current
    Row(
        modifier
            .height(52.dp)
            .tvFocus(onClick = onClick, shape = RoundedCornerShape(26.dp), requester = requester, selected = selected)
            .padding(horizontal = 24.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = s.onSurface, modifier = Modifier.size(24.dp))
            Spacer(Modifier.width(10.dp))
        }
        Text(text, color = s.onSurface, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

@Composable
fun TvTitle(text: String, modifier: Modifier = Modifier) {
    val s = LocalAppStyle.current
    Text(text, color = s.onSurface, fontSize = 30.sp, fontWeight = FontWeight.Bold, modifier = modifier)
}

@Composable
fun TvEmpty(title: String, message: String? = null, busy: Boolean = false) {
    val s = LocalAppStyle.current
    Box(Modifier.fillMaxWidth().padding(48.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            if (busy) {
                CircularProgressIndicator(color = s.accent, strokeWidth = 4.dp)
                Spacer(Modifier.height(16.dp))
            }
            Text(title, color = s.onSurface, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
            if (message != null) {
                Spacer(Modifier.height(6.dp))
                Text(message, color = s.onSurfaceDim, fontSize = 17.sp)
            }
        }
    }
}

/** Einzeiliger Status für Synchronisierung und EPG (nur sichtbar, wenn etwas läuft oder fehlschlägt). */
@Composable
fun TvStatusLine(sync: SyncState, epg: EpgState) {
    val s = LocalAppStyle.current
    val text: String
    val color: Color
    var progress: Float? = null
    when {
        sync.running -> {
            text = "Inhalte laden: ${sync.stage}"
            color = s.onSurface
            progress = sync.fraction
        }
        sync.error != null -> {
            text = sync.error!!
            color = Color(0xFFFF8A8A)
        }
        epg.running -> {
            text = epg.message
            color = s.onSurfaceDim
            progress = if (epg.fraction >= 0f) epg.fraction else null
        }
        epg.error != null -> {
            text = epg.error!!
            color = Color(0xFFFFB27A)
        }
        else -> return
    }
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(text, color = color, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (sync.running || epg.running) {
            Spacer(Modifier.height(4.dp))
            if (progress != null) {
                LinearProgressIndicator(
                    progress = { progress.coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth().height(4.dp),
                    color = s.accent,
                    trackColor = Color.White.copy(alpha = 0.15f),
                )
            } else {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth().height(4.dp),
                    color = s.accent,
                    trackColor = Color.White.copy(alpha = 0.15f),
                )
            }
        }
    }
}

// ---------------------------------------------------------------------- Fokus-Verwaltung

/**
 * Merkt sich die FocusRequester der aktuell zusammengesetzten Listeneinträge, damit nach
 * Rückkehr aus dem Player gezielt auf den zuletzt fokussierten Eintrag gesprungen werden kann.
 */
class FocusRegistry {
    private val map = HashMap<Int, FocusRequester>()

    fun register(index: Int, r: FocusRequester) {
        map[index] = r
    }

    fun unregister(index: Int, r: FocusRequester) {
        if (map[index] === r) map.remove(index)
    }

    /** Versucht mehrere Frames lang, den Eintrag zu fokussieren (er muss erst zusammengesetzt werden). */
    suspend fun focus(index: Int, tries: Int = 15): Boolean {
        repeat(tries) {
            val r = map[index]
            if (r != null) {
                try {
                    r.requestFocus()
                    return true
                } catch (e: IllegalStateException) {
                    // noch nicht platziert – nächster Frame
                }
            }
            withFrameNanos { }
        }
        return false
    }
}

@Composable
fun Modifier.registered(registry: FocusRegistry, index: Int): Modifier {
    val r = remember { FocusRequester() }
    DisposableEffect(registry, index, r) {
        registry.register(index, r)
        onDispose { registry.unregister(index, r) }
    }
    return this.focusRequester(r)
}

/** Einstiegspunkt für Fokus von der Tab-Leiste in den Inhalt (vom aktuellen Bildschirm gesetzt). */
class EntryHandle {
    var action: (() -> Unit)? = null
}

@Composable
fun RegisterEntry(handle: EntryHandle, action: () -> Unit) {
    val current = androidx.compose.runtime.rememberUpdatedState(action)
    DisposableEffect(handle) {
        val a: () -> Unit = { current.value() }
        handle.action = a
        onDispose { if (handle.action === a) handle.action = null }
    }
}

// ---------------------------------------------------------------------- Dialog

class TvAction(val label: String, val onClick: () -> Unit)

/** Fernbedienungstauglicher Dialog mit Fokus-Einschluss (eigenes Fenster) und Zurück zum Schließen. */
@Composable
fun TvActionDialog(title: String, actions: List<TvAction>, onDismiss: () -> Unit) {
    val s = LocalAppStyle.current
    val first = remember { FocusRequester() }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier
                .width(520.dp)
                .clip(RoundedCornerShape(24.dp))
                .background(s.backgroundColors[1])
                .border(1.dp, Color.White.copy(alpha = 0.2f), RoundedCornerShape(24.dp))
                .padding(28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(title, color = s.onSurface, fontSize = 24.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            actions.forEachIndexed { i, a ->
                TvButton(
                    text = a.label,
                    onClick = {
                        onDismiss()
                        a.onClick()
                    },
                    modifier = Modifier.fillMaxWidth(),
                    requester = if (i == 0) first else null,
                )
            }
        }
    }
    LaunchedEffect(Unit) {
        withFrameNanos { }
        try {
            first.requestFocus()
        } catch (e: IllegalStateException) {
        }
    }
}

fun Modifier.dimmed(): Modifier = this.graphicsLayer { alpha = 0.6f }

// ---------------------------------------------------------------------- Texteingabe (TV)

/**
 * Texteingabe über einen eigenen Dialog: Das Feld in der Liste ist nur ein fokussierbarer Knopf,
 * die eigentliche Eingabe (mit Bildschirmtastatur) passiert im Dialog. Das ist mit der
 * Fernbedienung zuverlässiger als ein Textfeld direkt in einer Liste.
 */
@Composable
fun TvTextInputDialog(
    title: String,
    initial: String,
    password: Boolean = false,
    onDone: (String) -> Unit,
    onCancel: () -> Unit,
) {
    val s = LocalAppStyle.current
    var value by remember { mutableStateOf(androidx.compose.ui.text.input.TextFieldValue(initial, androidx.compose.ui.text.TextRange(initial.length))) }
    var revealed by remember { mutableStateOf(false) }
    val field = remember { FocusRequester() }
    val eye = remember { FocusRequester() }
    val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    val shape = RoundedCornerShape(14.dp)
    Dialog(onDismissRequest = onCancel, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier
                .width(640.dp)
                .clip(RoundedCornerShape(24.dp))
                .background(s.backgroundColors[1])
                .border(1.dp, Color.White.copy(alpha = 0.2f), RoundedCornerShape(24.dp))
                .padding(28.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(title, color = s.onSurface, fontSize = 24.sp, fontWeight = FontWeight.Bold)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                androidx.compose.foundation.text.BasicTextField(
                    value = value,
                    onValueChange = { value = it },
                    singleLine = true,
                    textStyle = androidx.compose.ui.text.TextStyle(color = s.onSurface, fontSize = 22.sp),
                    cursorBrush = androidx.compose.ui.graphics.SolidColor(s.accent),
                    visualTransformation = if (password && !revealed) {
                        androidx.compose.ui.text.input.PasswordVisualTransformation()
                    } else {
                        androidx.compose.ui.text.input.VisualTransformation.None
                    },
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                        imeAction = androidx.compose.ui.text.input.ImeAction.Done,
                        keyboardType = if (password) {
                            androidx.compose.ui.text.input.KeyboardType.Password
                        } else {
                            androidx.compose.ui.text.input.KeyboardType.Text
                        },
                    ),
                    keyboardActions = androidx.compose.foundation.text.KeyboardActions(onDone = { onDone(value.text) }),
                    modifier = Modifier
                        .weight(1f)
                        .focusRequester(field)
                        .then(if (password) Modifier.focusProperties { right = eye } else Modifier)
                        .onPreviewKeyEvent { event ->
                            if (
                                password &&
                                event.type == KeyEventType.KeyDown &&
                                event.key == Key.DirectionRight &&
                                value.selection.end >= value.text.length
                            ) {
                                try {
                                    eye.requestFocus()
                                } catch (_: IllegalStateException) {
                                }
                                true
                            } else {
                                false
                            }
                        }
                        .background(Color.White.copy(alpha = 0.10f), shape)
                        .border(2.dp, s.accent, shape)
                        .padding(16.dp),
                )
                if (password) {
                    Box(
                        Modifier
                            .size(56.dp)
                            .focusProperties { left = field }
                            .onPreviewKeyEvent { event ->
                                if (event.type == KeyEventType.KeyDown && event.key == Key.DirectionLeft) {
                                    try {
                                        field.requestFocus()
                                    } catch (_: IllegalStateException) {
                                    }
                                    true
                                } else {
                                    false
                                }
                            }
                            .tvFocus(
                                onClick = { revealed = !revealed },
                                requester = eye,
                                shape = RoundedCornerShape(14.dp),
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            if (revealed) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                            contentDescription = if (revealed) "Passwort verbergen" else "Passwort anzeigen",
                            tint = s.onSurface,
                            modifier = Modifier.size(28.dp),
                        )
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TvButton("OK", onClick = { onDone(value.text) })
                TvButton("Abbrechen", onClick = onCancel)
            }
        }
    }
    LaunchedEffect(Unit) {
        repeat(12) {
            withFrameNanos { }
            try {
                field.requestFocus()
                keyboard?.show()
                return@LaunchedEffect
            } catch (e: IllegalStateException) {
                // Dialogfenster noch nicht fertig – nächster Frame
            }
        }
    }
}

/** Eingabefeld-Zeile für Formulare (öffnet beim Klick den Eingabedialog). */
@Composable
fun TvField(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    password: Boolean = false,
) {
    val s = LocalAppStyle.current
    var open by remember { mutableStateOf(false) }
    var revealed by remember { mutableStateOf(false) }
    if (password) {
        Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(
                Modifier
                    .weight(1f)
                    .tvFocus(onClick = { open = true })
                    .padding(horizontal = 18.dp, vertical = 10.dp),
            ) {
                Text(label, color = s.onSurfaceDim, fontSize = 14.sp)
                val shown = when {
                    value.isEmpty() -> "–"
                    revealed -> value
                    else -> "•".repeat(value.length.coerceAtMost(24))
                }
                Text(shown, color = s.onSurface, fontSize = 20.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.width(8.dp))
            Box(
                Modifier
                    .size(52.dp)
                    .tvFocus(onClick = { revealed = !revealed }, shape = RoundedCornerShape(14.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    if (revealed) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                    contentDescription = if (revealed) "Passwort verbergen" else "Passwort anzeigen",
                    tint = s.onSurface,
                    modifier = Modifier.size(26.dp),
                )
            }
        }
    } else {
        Column(
            modifier
                .fillMaxWidth()
                .tvFocus(onClick = { open = true })
                .padding(horizontal = 18.dp, vertical = 10.dp),
        ) {
            Text(label, color = s.onSurfaceDim, fontSize = 14.sp)
            val shown = if (value.isEmpty()) "–" else value
            Text(shown, color = s.onSurface, fontSize = 20.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
    if (open) {
        TvTextInputDialog(
            title = label,
            initial = value,
            password = password,
            onDone = {
                onChange(it)
                open = false
            },
            onCancel = { open = false },
        )
    }
}

/** Merkt, ob beim ersten Aufbau automatisch in den Inhalt fokussiert werden soll (bis zur ersten Taste). */
class FirstFocus {
    @Volatile
    var pending = true
}

/** Scrollt zum Index, falls er gerade nicht sichtbar ist (damit er zusammengesetzt und fokussierbar wird). */
suspend fun androidx.compose.foundation.lazy.LazyListState.ensureVisible(index: Int) {
    val last = layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
    if (index < firstVisibleItemIndex || index > last) scrollToItem((index - 2).coerceAtLeast(0))
}

suspend fun androidx.compose.foundation.lazy.grid.LazyGridState.ensureVisible(index: Int) {
    if (layoutInfo.visibleItemsInfo.none { it.index == index }) scrollToItem((index - 4).coerceAtLeast(0))
}
