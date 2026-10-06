package de.dgstudios.iptvstream

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.dgstudios.iptvstream.core.data.EpgState
import de.dgstudios.iptvstream.core.data.SyncState
import de.dgstudios.iptvstream.core.ui.LocalAppStyle

/** Glas-Optik: halbtransparente Fläche mit hellem Rand und Verlauf. */
@Composable
fun Modifier.glass(shape: Shape = RoundedCornerShape(22.dp), strong: Boolean = false): Modifier {
    val s = LocalAppStyle.current
    val a = s.glassAlpha * (if (strong) 1.7f else 1f)
    val fill = if (s.dark) {
        Brush.verticalGradient(listOf(Color.White.copy(alpha = a + 0.05f), Color.White.copy(alpha = a * 0.6f)))
    } else {
        Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.78f), Color.White.copy(alpha = 0.52f)))
    }
    val border = Brush.linearGradient(
        listOf(
            Color.White.copy(alpha = if (s.dark) 0.45f else 0.95f),
            Color.White.copy(alpha = if (s.dark) 0.06f else 0.3f),
        ),
    )
    return this.clip(shape).background(fill, shape).border(1.dp, border, shape)
}

/** Klick mit leichter Federanimation (wenn Animationen aktiv sind). */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun Modifier.pressable(onClick: () -> Unit, onLongClick: (() -> Unit)? = null): Modifier {
    val s = LocalAppStyle.current
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed && s.animations) 0.965f else 1f,
        animationSpec = spring(dampingRatio = 0.6f, stiffness = 500f),
        label = "press",
    )
    return this
        .graphicsLayer {
            scaleX = scale
            scaleY = scale
        }
        .combinedClickable(
            interactionSource = source,
            indication = null,
            onLongClick = onLongClick,
            onClick = onClick,
        )
}

@Composable
fun GlassChip(text: String, selected: Boolean, onClick: () -> Unit) {
    val s = LocalAppStyle.current
    val shape = RoundedCornerShape(50)
    val base = Modifier.height(36.dp)
    val m = if (selected) {
        base.clip(shape).background(s.accent, shape)
    } else {
        base.glass(shape)
    }
    Box(
        modifier = m.pressable(onClick).padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            color = if (selected) Color.White else s.onSurface,
            fontSize = 14.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
fun ScreenHeader(title: String, subtitle: String? = null) {
    val s = LocalAppStyle.current
    Column(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 14.dp, bottom = 6.dp)) {
        Text(title, color = s.onSurface, fontSize = 30.sp, fontWeight = FontWeight.Bold)
        if (subtitle != null) {
            Text(subtitle, color = s.onSurfaceDim, fontSize = 13.sp)
        }
    }
}

@Composable
fun EmptyState(title: String, message: String? = null, busy: Boolean = false) {
    val s = LocalAppStyle.current
    Box(Modifier.fillMaxWidth().padding(40.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            if (busy) {
                CircularProgressIndicator(color = s.accent, strokeWidth = 3.dp)
                Spacer(Modifier.height(16.dp))
            }
            Text(title, color = s.onSurface, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            if (message != null) {
                Spacer(Modifier.height(6.dp))
                Text(message, color = s.onSurfaceDim, fontSize = 14.sp)
            }
        }
    }
}

/** Zeigt Lade-/EPG-Fortschritt und Fehler der Synchronisierung. */
@Composable
fun SyncBanner(sync: SyncState, epg: EpgState, onRetry: () -> Unit) {
    val s = LocalAppStyle.current
    val showSync = sync.running || sync.error != null
    val showEpg = epg.running || epg.error != null
    if (!showSync && !showEpg) return
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .glass(RoundedCornerShape(16.dp))
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        if (sync.running) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Inhalte laden: ${sync.stage}", color = s.onSurface, fontSize = 13.sp, modifier = Modifier.weight(1f))
            }
            Spacer(Modifier.height(6.dp))
            LinearProgressIndicator(
                progress = { sync.fraction.coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth(),
                color = s.accent,
                trackColor = Color.White.copy(alpha = 0.15f),
            )
        } else if (sync.error != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(sync.error!!, color = Color(0xFFFF8A8A), fontSize = 13.sp, modifier = Modifier.weight(1f))
                Spacer(Modifier.width(8.dp))
                Text(
                    "Erneut",
                    color = s.accent,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 13.sp,
                    modifier = Modifier.pressable(onRetry).padding(6.dp),
                )
            }
        }
        if (epg.running) {
            if (showSync) Spacer(Modifier.height(8.dp))
            Text(epg.message, color = s.onSurfaceDim, fontSize = 12.sp)
            Spacer(Modifier.height(4.dp))
            if (epg.fraction >= 0f) {
                LinearProgressIndicator(
                    progress = { epg.fraction.coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth(),
                    color = s.accent,
                    trackColor = Color.White.copy(alpha = 0.15f),
                )
            } else {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth(),
                    color = s.accent,
                    trackColor = Color.White.copy(alpha = 0.15f),
                )
            }
        } else if (epg.error != null) {
            if (showSync) Spacer(Modifier.height(6.dp))
            Text(epg.error!!, color = Color(0xFFFFB27A), fontSize = 12.sp)
        }
    }
}

@Composable
fun StarBadge(modifier: Modifier = Modifier) {
    Box(
        modifier
            .clip(CircleShape)
            .background(Color(0xCC000000))
            .padding(4.dp),
    ) {
        Icon(
            Icons.Rounded.Star,
            contentDescription = "Favorit",
            tint = Color(0xFFFFC857),
            modifier = Modifier.width(14.dp).height(14.dp),
        )
    }
}
