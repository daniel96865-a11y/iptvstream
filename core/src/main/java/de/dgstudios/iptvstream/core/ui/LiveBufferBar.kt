package de.dgstudios.iptvstream.core.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.dgstudios.iptvstream.core.player.PlaybackBuffer

/** Live-Puffer unten am Sender. Die Balkenlänge folgt der gewählten Puffergröße. */
@Composable
fun LiveBufferBar(
    bufferedMs: Long,
    maxMs: Long,
    modifier: Modifier = Modifier,
    barHeight: Dp = 4.dp,
    fontSize: TextUnit = 13.sp,
) {
    if (maxMs <= 0L) return
    val track = PlaybackBuffer.trackFraction(maxMs)
    val fill = animatedProgress(PlaybackBuffer.fill(bufferedMs, maxMs))
    Column(modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "Puffer",
                color = Color.White.copy(alpha = 0.8f),
                fontSize = fontSize,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
            )
            Spacer(Modifier.weight(1f))
            Text(
                PlaybackBuffer.label(bufferedMs, maxMs),
                color = Color.White.copy(alpha = 0.8f),
                fontSize = fontSize,
                maxLines = 1,
                softWrap = false,
            )
        }
        Spacer(Modifier.height(3.dp))
        Box(
            Modifier
                .fillMaxWidth(track)
                .height(barHeight)
                .clip(RoundedCornerShape(2.dp))
                .background(Color.White.copy(alpha = 0.18f)),
        ) {
            Box(
                Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(fill)
                    .background(Color.White.copy(alpha = 0.92f)),
            )
        }
    }
}
