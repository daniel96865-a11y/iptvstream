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
        "glas" to ColorTheme(accent = 6, background = 5),
        "blue" to ColorTheme(accent = 0, background = 0),
        "violet" to ColorTheme(accent = 1, background = 2),
        "aqua" to ColorTheme(accent = 2, background = 1),
        "green" to ColorTheme(accent = 3, background = 2),
        "sunset" to ColorTheme(accent = 4, background = 3),
        "coral" to ColorTheme(accent = 5, background = 3),
        "graphite" to ColorTheme(accent = 5, background = 4),
    )

    val accents = listOf(
        Color(0xFF2F7BFF), Color(0xFF9B7BFF), Color(0xFF22D3C5),
        Color(0xFF34D399), Color(0xFFFFA24C), Color(0xFFFF5C7A),
        Color(0xFF8C70FF), // Glas & Verlauf (Violett, Verlauf nach Cyan)
    )

    /** Dunkle Verläufe (oben -> unten). */
    val darkBackgrounds = listOf(
        listOf(Color(0xFF030712), Color(0xFF071230), Color(0xFF0C1C45)), // Mitternacht (tiefes Marineblau)
        listOf(Color(0xFF031A26), Color(0xFF053247), Color(0xFF0A4A63)), // Ozean
        listOf(Color(0xFF07121A), Color(0xFF0E2A2F), Color(0xFF1B2F4A)), // Aurora
        listOf(Color(0xFF1B0B1E), Color(0xFF3A1626), Color(0xFF5A2A2A)), // Sonnenuntergang
        listOf(Color(0xFF0C0C0E), Color(0xFF17171B), Color(0xFF222228)), // Graphit
        listOf(Color(0xFF0B0D1A), Color(0xFF120F2E), Color(0xFF0A1C2C)), // Glas & Verlauf
    )

    val lightBackgrounds = listOf(
        listOf(Color(0xFFF3F6FF), Color(0xFFE6ECFA), Color(0xFFD9E2F5)),
        listOf(Color(0xFFEAF6FA), Color(0xFFD5EBF3), Color(0xFFC1E0EC)),
        listOf(Color(0xFFEDF7F5), Color(0xFFDCEEEA), Color(0xFFCCE3EA)),
        listOf(Color(0xFFFFF1EE), Color(0xFFFBE0DA), Color(0xFFF5D0CC)),
        listOf(Color(0xFFF4F4F6), Color(0xFFE8E8EC), Color(0xFFDCDCE2)),
        listOf(Color(0xFFF2F0FF), Color(0xFFE6E8FA), Color(0xFFDDF1F5)),
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
    /** Ende des Akzentverlaufs. Gleich [accent] = einfarbig (alle Themen außer Glas). */
    val accentEnd: Color = accent,
    /** Zweiter Farbfleck im Hintergrund. */
    val glow2: Color = accent,
    /** Schwarz-Weiß: nur Schwarz, Weiß und Grau; reiner schwarzer Hintergrund ohne Farbflecken. */
    val mono: Boolean = false,
) {
    val gradient: Boolean get() = accentEnd != accent
    /** Verlauf für Flächen mit weißer Schrift (Chips, Leisten-Kapsel): dunkleres Cyan für Kontrast. */
    val accentFillBrush: Brush get() = if (gradient) Brush.horizontalGradient(listOf(accent, Color(0xFF1E8FC8))) else androidx.compose.ui.graphics.SolidColor(accent)
    val accentBrush: Brush get() = if (gradient) Brush.horizontalGradient(listOf(accent, accentEnd)) else androidx.compose.ui.graphics.SolidColor(accent)

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
    val (style, scheme) = if (settings.mono) monoTheme(isTv, settings) else colorTheme(settings, isTv, dark)
    ThemeContent(style, scheme, content)
}

private fun colorTheme(settings: AppSettings, isTv: Boolean, dark: Boolean): Pair<AppStyle, androidx.compose.material3.ColorScheme> {
    val preset = Palette.colorThemes[settings.colorTheme]
    val accentIndex = preset?.accent ?: settings.accent.coerceIn(0, Palette.accents.lastIndex)
    val bgIndex = preset?.background ?: settings.background.coerceIn(0, Palette.darkBackgrounds.lastIndex)
    val accent = Palette.accents[accentIndex]
    val bg = if (dark) Palette.darkBackgrounds[bgIndex] else Palette.lightBackgrounds[bgIndex]
    val glas = accentIndex == 6
    val style = AppStyle(
        accent, bg, settings.glass.coerceIn(1, 3), settings.animations, dark, isTv,
        accentEnd = if (glas) Color(0xFF22D3EE) else accent,
        glow2 = if (glas) Color(0xFF0E9AB8) else accent,
    )

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
    return style to scheme
}

/**
 * Gemeinsamer Rahmen für normal und Schwarz-Weiß: gleicher Aufbau, damit beim Umschalten der Inhalt
 * (Navigation, Listenposition) erhalten bleibt und nur die Farben wechseln.
 */
@Composable
private fun ThemeContent(style: AppStyle, scheme: androidx.compose.material3.ColorScheme, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalAppStyle provides style, LocalMono provides style.mono) {
        MaterialTheme(colorScheme = scheme) {
            if (style.mono) MonoSystemBars()
            content()
        }
    }
}

/** Schwarz-Weiß: Akzent ist ein mittleres Grau (weiße Schrift darauf und Grau auf Schwarz bleiben lesbar). */
object Mono {
    val accent = Color(0xFF7A7A7A)
    val light = Color(0xFFD6D6D6)
    val mid = Color(0xFF9E9E9E)
    val dark = Color(0xFF2A2A2A)
    val darker = Color(0xFF141414)
}

private fun monoTheme(isTv: Boolean, settings: AppSettings): Pair<AppStyle, androidx.compose.material3.ColorScheme> {
    val black = Color.Black
    val style = AppStyle(
        Mono.accent, listOf(black, black, black), settings.glass.coerceIn(1, 3), settings.animations,
        dark = true, isTv = isTv, mono = true,
    )
    // Alle Rollen ausdrücklich grau: Material3 setzt sonst für nicht genannte Rollen lila/rosa Standardtöne
    // (Schalter, Chips, Dialoge, Fehler).
    val scheme = darkColorScheme(
        primary = Mono.accent, onPrimary = Color.White,
        primaryContainer = Mono.dark, onPrimaryContainer = Color.White,
        inversePrimary = Mono.dark,
        secondary = Mono.mid, onSecondary = black,
        secondaryContainer = Mono.dark, onSecondaryContainer = Color.White,
        tertiary = Mono.mid, onTertiary = black,
        tertiaryContainer = Mono.dark, onTertiaryContainer = Color.White,
        background = black, onBackground = Color.White,
        surface = black, onSurface = Color.White,
        surfaceVariant = Mono.dark, onSurfaceVariant = Color(0xB3FFFFFF),
        surfaceTint = Color.White,
        inverseSurface = Mono.light, inverseOnSurface = black,
        error = Mono.light, onError = black,
        errorContainer = Mono.dark, onErrorContainer = Color.White,
        outline = Mono.mid, outlineVariant = Mono.dark,
        scrim = black,
        surfaceBright = Mono.dark, surfaceDim = black,
        surfaceContainer = Mono.darker, surfaceContainerHigh = Color(0xFF1E1E1E),
        surfaceContainerHighest = Mono.dark, surfaceContainerLow = Color(0xFF0E0E0E),
        surfaceContainerLowest = black,
    )
    return style to scheme
}

/**
 * Schwarz-Weiß: Status- und Navigationsleiste schwarz mit hellen Symbolen, ohne helle System-Abdunklung
 * (enableEdgeToEdge folgt sonst dem hellen Systemmodus). Beim Abschalten wieder wie vorher.
 */
@Composable
private fun MonoSystemBars() {
    val view = androidx.compose.ui.platform.LocalView.current
    androidx.compose.runtime.DisposableEffect(view) {
        var ctx = view.context
        while (ctx is android.content.ContextWrapper && ctx !is android.app.Activity) ctx = ctx.baseContext
        val window = (ctx as? android.app.Activity)?.window
        if (window == null || view.isInEditMode) return@DisposableEffect onDispose {}
        val ctl = androidx.core.view.WindowCompat.getInsetsController(window, window.decorView)
        val lightStatus = ctl.isAppearanceLightStatusBars
        val lightNav = ctl.isAppearanceLightNavigationBars
        @Suppress("DEPRECATION") val navColor = window.navigationBarColor
        @Suppress("DEPRECATION") val statusColor = window.statusBarColor
        val contrast = if (android.os.Build.VERSION.SDK_INT >= 29) window.isNavigationBarContrastEnforced else false
        ctl.isAppearanceLightStatusBars = false
        ctl.isAppearanceLightNavigationBars = false
        @Suppress("DEPRECATION") run {
            window.statusBarColor = android.graphics.Color.TRANSPARENT
            window.navigationBarColor = android.graphics.Color.BLACK
        }
        if (android.os.Build.VERSION.SDK_INT >= 29) window.isNavigationBarContrastEnforced = false
        onDispose {
            ctl.isAppearanceLightStatusBars = lightStatus
            ctl.isAppearanceLightNavigationBars = lightNav
            @Suppress("DEPRECATION") run {
                window.statusBarColor = statusColor
                window.navigationBarColor = navColor
            }
            if (android.os.Build.VERSION.SDK_INT >= 29) window.isNavigationBarContrastEnforced = contrast
        }
    }
}

/** Schwarz-Weiß aktiv? Auch außerhalb von [AppTheme] lesbar (dann false). */
val LocalMono = staticCompositionLocalOf { false }

/** Grauton gleicher Helligkeit (Rec. 601), Deckkraft bleibt. */
fun Color.toGrey(): Color {
    val l = (0.299f * red + 0.587f * green + 0.114f * blue).coerceIn(0f, 1f)
    return Color(l, l, l, alpha)
}

/**
 * Feste UI-Farbe (Fehlerrot, Favoriten-Gelb, Paneele …): bei Schwarz-Weiß als Grauton, sonst unverändert.
 * Nur für Oberflächenfarben; Bilder (Senderlogos, Poster) werden nie umgefärbt.
 */
val Color.ui: Color
    @Composable @androidx.compose.runtime.ReadOnlyComposable get() = if (LocalMono.current) toGrey() else this

/** Vollflächiger Hintergrund mit Verlauf und weichen Farbflecken. */
@Composable
fun AppBackground(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val style = LocalAppStyle.current
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(style.backgroundColors))
            .drawBehind {
                if (style.mono) return@drawBehind
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
                        listOf(style.glow2.copy(alpha = a * if (style.gradient) 1.1f else 0.7f), Color.Transparent),
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
