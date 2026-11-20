package de.dgstudios.iptvstream.tv

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
 * Google-Play-Screenshots für Android TV (1920x1080) mit neutralen Demo-Daten. Die echten
 * TV-Bildschirme hängen an ViewModels und Datenbank, deshalb sind Live-Liste, Filme und Player
 * hier aus den echten Bausteinen (Tab-Leiste, Theme) plus nachgebauten Zeilen zusammengesetzt.
 * `./gradlew :tv:recordPaparazziPlayDebug --tests '*TvStoreScreenshots*'`
 */
class TvStoreScreenshots {
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

    private fun img(name: String): ImageBitmap =
        BitmapFactory.decodeStream(javaClass.classLoader!!.getResourceAsStream("demo/$name")).asImageBitmap()

    private val channels = listOf(
        Triple("Demo 1", "Tagesüberblick", "Magazin am Mittag"),
        Triple("Nachrichten HD", "Nachrichten", "Wetter kompakt"),
        Triple("Sport Live", "Live-Übertragung", "Sport am Abend"),
        Triple("Kino Klassik", "Spielfilm", "Kurzfilmnacht"),
        Triple("Doku Welt", "Naturdoku", "Reisereportage"),
        Triple("Musik", "Konzertabend", "Charts"),
        Triple("Kinderkanal Demo", "Zeichentrick", "Lernmagazin"),
    )

    @Composable
    private fun Frame(tab: TvTab, content: @Composable () -> Unit) {
        AppTheme(AppSettings(), isTv = true) {
            AppBackground {
                Column(Modifier.fillMaxSize().padding(horizontal = 48.dp, vertical = 27.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        for (t in TvTab.entries) TabButton(t = t, selected = t == tab, requester = FocusRequester(), onFocus = {})
                    }
                    Spacer(Modifier.height(20.dp))
                    content()
                }
            }
        }
    }

    @Composable
    private fun Card(modifier: Modifier = Modifier, focused: Boolean = false, content: @Composable () -> Unit) {
        val s = LocalAppStyle.current
        Box(
            modifier
                .clip(RoundedCornerShape(14.dp))
                .background(if (focused) s.cardStrong else s.card)
                .then(if (focused) Modifier.border(2.dp, s.accentBrush, RoundedCornerShape(14.dp)) else Modifier),
        ) { content() }
    }

    @Test
    fun liveTv() = paparazzi.snapshot {
        Frame(TvTab.LIVE) {
            val s = LocalAppStyle.current
            Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                Column(Modifier.width(180.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("Alle Sender", "★ Favoriten", "Nachrichten", "Sport", "Filme", "Doku", "Musik", "Kinder").forEachIndexed { i, c ->
                        Card(Modifier.fillMaxWidth(), focused = i == 0) {
                            Text(c, color = s.onSurface, fontSize = 17.sp, fontWeight = if (i == 0) FontWeight.Bold else FontWeight.Normal, modifier = Modifier.padding(horizontal = 16.dp, vertical = 11.dp))
                        }
                    }
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    channels.forEachIndexed { i, (name, now, next) ->
                        Card(Modifier.fillMaxWidth().height(66.dp), focused = i == 1) {
                            Row(Modifier.fillMaxSize().padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text("${i + 1}", color = s.onSurfaceDim, fontSize = 15.sp, modifier = Modifier.width(30.dp))
                                Image(img("$i.png"), null, Modifier.size(46.dp).clip(RoundedCornerShape(10.dp)))
                                Spacer(Modifier.width(14.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(name, color = s.onSurface, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                                    Text("$now · danach: $next", color = s.onSurfaceDim, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                                if (i == 1 || i == 4) Text("★", color = Color(0xFFFFC94D), fontSize = 20.sp)
                            }
                        }
                    }
                }
                Column(Modifier.width(300.dp)) {
                    Image(img("frame.jpg"), null, Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(14.dp)), contentScale = ContentScale.Crop)
                    Spacer(Modifier.height(14.dp))
                    Text("Nachrichten HD", color = s.onSurface, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                    Text("Nachrichten · 12:00 – 12:30", color = s.onSurfaceDim, fontSize = 15.sp)
                    Spacer(Modifier.height(10.dp))
                    Box(Modifier.fillMaxWidth().height(5.dp).clip(RoundedCornerShape(3.dp)).background(s.card)) {
                        Box(Modifier.fillMaxWidth(0.45f).fillMaxHeight().background(s.accentBrush))
                    }
                    Spacer(Modifier.height(10.dp))
                    Text("Danach: Wetter kompakt", color = s.onSurfaceDim, fontSize = 15.sp)
                }
            }
        }
    }

    @Test
    fun filme() = paparazzi.snapshot {
        Frame(TvTab.MOVIES) {
            val s = LocalAppStyle.current
            val titles = listOf("Der lange Weg", "Nachtzug", "Sommerwind", "Die Insel", "Stadt aus Glas", "Blaue Stunde", "Funkstille", "Am Horizont")
            Text("Filme · Demo-Bibliothek", color = s.onSurface, fontSize = 24.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(14.dp))
            for (row in 0..1) Row(Modifier.padding(bottom = 14.dp), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                for (col in 0 until 6) {
                    val i = (row * 6 + col) % titles.size
                    val t = titles[i]
                    Column(Modifier.width(118.dp)) {
                        Card(Modifier.fillMaxWidth().height(150.dp), focused = row == 0 && col == 2) {
                            Image(img("poster$i.jpg"), null, Modifier.fillMaxSize().padding(if (row == 0 && col == 2) 3.dp else 0.dp).clip(RoundedCornerShape(12.dp)), contentScale = ContentScale.Crop)
                        }
                        Spacer(Modifier.height(6.dp))
                        Text(t, color = s.onSurface, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                        Text("★ ${"%.1f".format(6.4 + (i * 0.37) % 2)}", color = s.onSurfaceDim, fontSize = 13.sp)
                    }
                }
            }
        }
    }

    @Test
    fun player() = paparazzi.snapshot {
        AppTheme(AppSettings(), isTv = true) {
            val s = LocalAppStyle.current
            Box(Modifier.fillMaxSize().background(Color.Black)) {
                Image(img("frame.jpg"), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xDD000000)))))
                Column(Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(48.dp)) {
                    Text("Sport Live", color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Bold)
                    Text("Live-Übertragung · danach: Sport am Abend", color = Color(0xCCFFFFFF), fontSize = 18.sp)
                    Spacer(Modifier.height(16.dp))
                    Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(Color(0x33FFFFFF))) {
                        Box(Modifier.fillMaxWidth(0.62f).fillMaxHeight().background(s.accentBrush))
                    }
                    Spacer(Modifier.height(10.dp))
                    Row {
                        Text("20:15", color = Color(0xCCFFFFFF), fontSize = 15.sp)
                        Spacer(Modifier.weight(1f))
                        Text("OK: Senderliste  ·  ▲▼: Sender wechseln", color = Color(0xCCFFFFFF), fontSize = 15.sp)
                        Spacer(Modifier.weight(1f))
                        Text("22:00", color = Color(0xCCFFFFFF), fontSize = 15.sp)
                    }
                }
            }
        }
    }
}
