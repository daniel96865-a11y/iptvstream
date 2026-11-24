package de.dgstudios.iptvstream.core.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.dgstudios.iptvstream.core.player.StreamInfo
import kotlinx.coroutines.delay

/**
 * Ladekreis erst nach einer kurzen Wartezeit: Beim schnellen Umschalten steht das Bild meist vorher,
 * dann blitzt kein Kreis auf.
 */
@Composable
fun DelayedSpinner(loading: Boolean, modifier: Modifier = Modifier, strokeWidth: Dp = 3.dp, delayMs: Long = 400) {
    var show by remember { mutableStateOf(false) }
    LaunchedEffect(loading) {
        show = false
        if (loading) {
            delay(delayMs)
            show = true
        }
    }
    if (show && loading) CircularProgressIndicator(modifier, color = Color.White, strokeWidth = strokeWidth)
}

/** Stream-Infos (Auflösung, Bildrate, Bitrate, Decoder, Umschaltzeit) als kleine Einblendung. */
@Composable
fun StreamInfoOverlay(info: StreamInfo, modifier: Modifier = Modifier, fontSize: TextUnit = 12.sp) {
    Column(
        modifier
            .background(Color.Black.copy(alpha = 0.72f), RoundedCornerShape(10.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        for (line in info.lines()) {
            Text(line, color = Color.White, fontSize = fontSize, fontFamily = FontFamily.Monospace)
        }
    }
}
