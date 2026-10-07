package de.dgstudios.iptvstream.core.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import de.dgstudios.iptvstream.core.settings.AppSettings

object Palette {
    data class ColorTheme(val accent: Int, val background: Int)

    val colorThemes = mapOf(
        "blue" to ColorTheme(accent = 0, background = 0),
        "violet" to ColorTheme(accent = 1, background = 2),
        "aqua" to ColorTheme(accent = 2, background = 1),
        "green" to ColorTheme(accent = 3, background = 2),
        "sunset" to ColorTheme(accent = 4, background = 3),
        "coral" to ColorTheme(accent = 5, background = 3),
        "graphite" to ColorTheme(accent = 5, background = 4),
    )

    val accents = listOf(
        Color(0xFF4C8DFF), Color(0xFF9B7BFF), Color(0xFF22D3C5),
        Color(0xFF34D399), Color(0xFFFFA24C), Color(0xFFFF5C7A),
    )

    /** Dunkle Verläufe (oben -> unten). */
    val darkBackgrounds = listOf(
        listOf(Color(0xFF05070F), Color(0xFF0B1226), Color(0xFF111A33)), // Mitternacht
        listOf(Color(0xFF031A26), Color(0xFF053247), Color(0xFF0A4A63)), // Ozean
        listOf(Color(0xFF07121A), Color(0xFF0E2A2F), Color(0xFF1B2F4A)), // Aurora
        listOf(Color(0xFF1B0B1E), Color(0xFF3A1626), Color(0xFF5A2A2A)), // Sonnenuntergang
        listOf(Color(0xFF0C0C0E), Color(0xFF17171B), Color(0xFF222228)), // Graphit
    )

    val lightBackgrounds = listOf(
        listOf(Color(0xFFF3F6FF), Color(0xFFE6ECFA), Color(0xFFD9E2F5)),
        listOf(Color(0xFFEAF6FA), Color(0xFFD5EBF3), Color(0xFFC1E0EC)),
        listOf(Color(0xFFEDF7F5), Color(0xFFDCEEEA), Color(0xFFCCE3EA)),
        listOf(Color(0xFFFFF1EE), Color(0xFFFBE0DA), Color(0xFFF5D0CC)),
        listOf(Color(0xFFF4F4F6), Color(0xFFE8E8EC), Color(0xFFDCDCE2)),
    )
}

data class AppStyle(
    val accent: Color,
    val backgroundColors: List<Color>,
    /** 1..3 */
    val glassLevel: Int,
    val animations: Boolean,
    val dark: Boolean,
    val isTv: Boolean,
) {
    val onSurface: Color get() = if (dark) Color.White else Color(0xFF0D1220)
    val onSurfaceDim: Color get() = if (dark) Color(0xB3FFFFFF) else Color(0xB30D1220)
    val card: Color get() = if (dark) Color(0x1FFFFFFF) else Color(0xCCFFFFFF)
    val cardStrong: Color get() = if (dark) Color(0x33FFFFFF) else Color(0xFFFFFFFF)

    /** Deckkraft der Glass-Flächen je nach Stärke. */
    val glassAlpha: Float get() = when (glassLevel) {
        1 -> 0.10f
        3 -> 0.24f
        else -> 0.16f
    }
}

val LocalAppStyle = staticCompositionLocalOf<AppStyle> { error("AppTheme fehlt") }

@Composable
fun AppTheme(settings: AppSettings, isTv: Boolean, content: @Composable () -> Unit) {
    val dark = when (settings.theme) {
        "light" -> false
        "system" -> isSystemInDarkTheme()
        else -> true
    }
    val preset = Palette.colorThemes[settings.colorTheme]
    val accentIndex = preset?.accent ?: settings.accent.coerceIn(0, Palette.accents.lastIndex)
    val bgIndex = preset?.background ?: settings.background.coerceIn(0, Palette.darkBackgrounds.lastIndex)
    val accent = Palette.accents[accentIndex]
    val bg = if (dark) Palette.darkBackgrounds[bgIndex] else Palette.lightBackgrounds[bgIndex]
    val style = AppStyle(accent, bg, settings.glass.coerceIn(1, 3), settings.animations, dark, isTv)

    val scheme = if (dark) {
        darkColorScheme(
            primary = accent, onPrimary = Color.White,
            background = bg[0], onBackground = Color.White,
            surface = bg[1], onSurface = Color.White,
            surfaceVariant = bg[2], onSurfaceVariant = Color(0xB3FFFFFF),
        )
    } else {
        lightColorScheme(
            primary = accent, onPrimary = Color.White,
            background = bg[0], onBackground = Color(0xFF0D1220),
            surface = bg[1], onSurface = Color(0xFF0D1220),
            surfaceVariant = bg[2], onSurfaceVariant = Color(0xB30D1220),
        )
    }
    CompositionLocalProvider(LocalAppStyle provides style) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}

/** Vollflächiger Hintergrund mit Verlauf und weichen Farbflecken. */
@Composable
fun AppBackground(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val style = LocalAppStyle.current
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(style.backgroundColors))
            .drawBehind {
                val a = if (style.dark) 0.22f else 0.16f
                drawCircle(
                    brush = Brush.radialGradient(
                        listOf(style.accent.copy(alpha = a), Color.Transparent),
                        center = Offset(size.width * 0.15f, size.height * 0.1f),
                        radius = size.minDimension * 0.9f,
                    ),
                    radius = size.minDimension * 0.9f,
                    center = Offset(size.width * 0.15f, size.height * 0.1f),
                )
                drawCircle(
                    brush = Brush.radialGradient(
                        listOf(style.accent.copy(alpha = a * 0.7f), Color.Transparent),
                        center = Offset(size.width * 0.95f, size.height * 0.85f),
                        radius = size.minDimension * 0.8f,
                    ),
                    radius = size.minDimension * 0.8f,
                    center = Offset(size.width * 0.95f, size.height * 0.85f),
                )
            },
    ) {
        content()
    }
}
