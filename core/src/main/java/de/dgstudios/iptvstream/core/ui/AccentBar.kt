package de.dgstudios.iptvstream.core.ui

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * Fortschrittsbalken in Akzentfarbe, beim Thema „Glas & Verlauf“ als Verlauf Violett → Cyan.
 * Der Fortschritt wird erst beim Zeichnen gelesen, ein Tick löst also keine Neukomposition aus.
 */
@Composable
fun AccentBar(progress: () -> Float, modifier: Modifier = Modifier, trackColor: Color = Color.White.copy(alpha = 0.15f)) {
    val s = LocalAppStyle.current
    val brush = s.accentBrush
    Spacer(
        Modifier.height(4.dp).then(modifier).drawBehind {
            val r = CornerRadius(size.height / 2f)
            drawRoundRect(trackColor, cornerRadius = r)
            val w = size.width * progress().coerceIn(0f, 1f)
            if (w > 0f) drawRoundRect(brush, size = Size(w, size.height), cornerRadius = r)
        },
    )
}
