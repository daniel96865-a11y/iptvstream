package de.dgstudios.iptvstream.tv

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.android.resources.Density
import com.android.resources.ScreenOrientation
import com.android.resources.UiMode
import de.dgstudios.iptvstream.core.settings.AppSettings
import de.dgstudios.iptvstream.core.ui.AppBackground
import de.dgstudios.iptvstream.core.ui.AppTheme
import de.dgstudios.iptvstream.core.ui.LocalAppStyle
import org.junit.Rule
import org.junit.Test

/**
 * Play-Store-Screenshots für Android TV (1920 × 1080) mit neutralen Platzhalter-Sendern, ohne echte
 * Sender-, Marken- oder Programmnamen. `./gradlew :tv:recordPaparazziDebug --tests '*PlayStoreScreenshots*'`
 */
class PlayStoreScreenshots {
    @get:Rule
    val paparazzi = Paparazzi(
        deviceConfig = DeviceConfig(
            screenHeight = 1080,
            screenWidth = 1920,
            xdpi = 320,
            ydpi = 320,
            orientation = ScreenOrientation.LANDSCAPE,
            density = Density.XHIGH,
            uiMode = UiMode.TELEVISION,
            softButtons = false,
        ),
        showSystemUi = false,
        maxPercentDifference = 1.0,
    )

    private val settings = AppSettings()

    private fun img(name: String): ImageBitmap =
        BitmapFactory.decodeStream(javaClass.classLoader!!.getResourceAsStream("demo/$name")).asImageBitmap()

    private val channels = listOf(
        Triple("Demo 1", "Tagesüberblick", "Danach: Magazin am Mittag"),
        Triple("Nachrichten HD", "Nachrichten", "Danach: Wetter kompakt"),
        Triple("Sport Live", "Live-Übertragung", "Danach: Sport am Abend"),
        Triple("Kino Klassik", "Spielfilm", "Danach: Filmtipps"),
        Triple("Doku Welt", "Dokumentation", "Danach: Reportage"),
        Triple("Musik TV", "Charts", "Danach: Konzert"),
    )
    private val badge = listOf(Color(0xFF4F8CFF), Color(0xFFFF5C7A), Color(0xFF34D399), Color(0xFFFFA94D), Color(0xFFA78BFA), Color(0xFF2DD4BF))

    @Composable
    private fun Tabs(selected: TvTab) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            for (tab in TvTab.entries) {
                val requester = remember(tab) { FocusRequester() }
                TabButton(t = tab, selected = tab == selected, requester = requester, onFocus = {})
            }
        }
    }

    @Composable
    private fun Screen(tab: TvTab, content: @Composable () -> Unit) {
        AppTheme(settings, isTv = true) {
            AppBackground {
                Column(Modifier.fillMaxSize().padding(horizontal = 48.dp, vertical = 27.dp)) {
                    Tabs(tab)
                    Spacer(Modifier.height(22.dp))
                    content()
                }
            }
        }
    }

    @Test
    fun liveTv() = paparazzi.snapshot {
        Screen(TvTab.LIVE) {
            val s = LocalAppStyle.current
            Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(28.dp)) {
                Column(Modifier.width(300.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    listOf("Alle Sender", "Favoriten", "Nachrichten", "Unterhaltung", "Sport", "Filme", "Dokus", "Kinder").forEachIndexed { i, n ->
                        Box(
                            Modifier.fillMaxWidth().height(48.dp).clip(RoundedCornerShape(14.dp))
                                .background(if (i == 0) s.accent.copy(alpha = 0.35f) else s.card),
                            contentAlignment = Alignment.CenterStart,
                        ) { Text(n, color = s.onSurface, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 18.dp)) }
                    }
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    channels.forEachIndexed { i, (name, now, next) ->
                        Row(
                            Modifier.fillMaxWidth().height(96.dp)
                                .then(if (i == 1) Modifier.tvFocus(onClick = {}, shape = RoundedCornerShape(16.dp), selected = true) else Modifier.background(s.card, RoundedCornerShape(16.dp)))
                                .padding(horizontal = 18.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text("${i + 1}", color = s.onSurfaceDim, fontSize = 20.sp, modifier = Modifier.width(48.dp))
                            Box(Modifier.size(60.dp).clip(RoundedCornerShape(14.dp)).background(badge[i]), contentAlignment = Alignment.Center) {
                                Text(name.take(1), color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Bold)
                            }
                            Spacer(Modifier.width(18.dp))
                            Column(Modifier.weight(1f)) {
                                Text(name, color = s.onSurface, fontSize = 22.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                                Text(now, color = s.onSurface.copy(alpha = 0.85f), fontSize = 17.sp, maxLines = 1)
                                LinearProgressIndicator(
                                    progress = { 0.2f + (i * 0.37f) % 0.7f },
                                    modifier = Modifier.padding(top = 5.dp).fillMaxWidth(0.7f).height(4.dp).clip(RoundedCornerShape(2.dp)),
                                    color = s.accent,
                                    trackColor = Color.White.copy(alpha = 0.14f),
                                )
                            }
                            Text(next, color = s.onSurfaceDim, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
    }

    @Test
    fun filme() = paparazzi.snapshot {
        val titles = listOf("Der lange Weg", "Nachtzug", "Sommerwind", "Die Insel", "Stadt aus Glas", "Blaue Stunde", "Funkstille", "Am Horizont")
        Screen(TvTab.MOVIES) {
            val s = LocalAppStyle.current
            Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
                for (r in 0 until 2) {
                    Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                        for (c in 0 until 4) {
                            val i = r * 4 + c
                            Column(Modifier.weight(1f)) {
                                Box(
                                    Modifier.fillMaxWidth().aspectRatio(16f / 11f)
                                        .then(if (i == 1) Modifier.tvFocus(onClick = {}, shape = RoundedCornerShape(16.dp), selected = true) else Modifier),
                                ) {
                                    Image(img("poster$i.jpg"), null, Modifier.fillMaxSize().clip(RoundedCornerShape(16.dp)), contentScale = ContentScale.Crop)
                                }
                                Text(titles[i], color = s.onSurface, fontSize = 18.sp, fontWeight = FontWeight.Medium, maxLines = 1, modifier = Modifier.padding(top = 6.dp, start = 2.dp))
                            }
                        }
                    }
                }
            }
        }
    }

    @Test
    fun player() = paparazzi.snapshot {
        AppTheme(settings, isTv = true) {
            val s = LocalAppStyle.current
            Box(Modifier.fillMaxSize().background(Color.Black)) {
                Image(img("frame.jpg"), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0x99000000), Color.Transparent, Color.Transparent, Color(0xDD000000)))))
                Column(Modifier.align(Alignment.BottomStart).padding(horizontal = 64.dp, vertical = 48.dp)) {
                    Text("2  Nachrichten HD", color = Color.White, fontSize = 34.sp, fontWeight = FontWeight.Bold)
                    Text("Nachrichten · Danach: Wetter kompakt", color = Color(0xCCFFFFFF), fontSize = 22.sp, modifier = Modifier.padding(top = 4.dp))
                    LinearProgressIndicator(
                        progress = { 0.4f },
                        modifier = Modifier.padding(top = 14.dp).fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
                        color = s.accent,
                        trackColor = Color.White.copy(alpha = 0.2f),
                    )
                    Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        listOf("1080p", "16:9", "Stereo").forEach {
                            Text(it, color = Color.White, fontSize = 16.sp, modifier = Modifier.background(Color(0x33FFFFFF), CircleShape).padding(horizontal = 14.dp, vertical = 4.dp))
                        }
                    }
                }
            }
        }
    }
}
