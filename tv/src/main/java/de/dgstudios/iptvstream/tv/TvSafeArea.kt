package de.dgstudios.iptvstream.tv

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Sicherer Bereich gegen Overscan (Android-TV-Richtlinie: etwa 5 % Rand = 48 dp seitlich, 27 dp
 * oben/unten bei 960×540 dp). Viele Fernseher schneiden mehr ab; darum in den Einstellungen
 * („Bildschirmrand (TV)“) einstellbar. Hintergründe dürfen bis zum Rand gehen, Inhalte nicht.
 */
data class TvSafe(val horizontal: Dp, val vertical: Dp)

val LocalTvSafe = staticCompositionLocalOf { TvSafe(48.dp, 27.dp) }

/** Werte der Einstellung „tv_margin“. */
fun tvSafeFor(setting: String): TvSafe = when (setting) {
    "small" -> TvSafe(24.dp, 14.dp) // 2,5 %
    "more" -> TvSafe(72.dp, 40.dp) // 7,5 %
    "max" -> TvSafe(96.dp, 54.dp) // 10 %
    else -> TvSafe(48.dp, 27.dp) // 5 % (Richtlinie)
}

val tvSafe: TvSafe
    @Composable @ReadOnlyComposable get() = LocalTvSafe.current

/** Innenabstand des sicheren Bereichs. [minVertical] für Seiten, die oben/unten mehr Luft wollen. */
@Composable
fun Modifier.tvSafePadding(minVertical: Dp = 0.dp): Modifier {
    val s = LocalTvSafe.current
    return padding(horizontal = s.horizontal, vertical = maxOf(s.vertical, minVertical))
}

@Composable
fun tvSafeContentPadding(minVertical: Dp = 0.dp): PaddingValues {
    val s = LocalTvSafe.current
    return PaddingValues(horizontal = s.horizontal, vertical = maxOf(s.vertical, minVertical))
}
