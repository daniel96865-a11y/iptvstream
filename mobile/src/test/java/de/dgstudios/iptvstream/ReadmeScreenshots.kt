package de.dgstudios.iptvstream

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarBorder
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import de.dgstudios.iptvstream.core.data.NowNext
import de.dgstudios.iptvstream.core.data.db.EpgEntity
import de.dgstudios.iptvstream.core.settings.AppSettings
import de.dgstudios.iptvstream.core.settings.SettingsSchema
import de.dgstudios.iptvstream.core.ui.AppBackground
import de.dgstudios.iptvstream.core.ui.AppTheme
import de.dgstudios.iptvstream.core.ui.LocalAppStyle
import de.dgstudios.iptvstream.core.vm.PlayerUi
import org.junit.Rule
import org.junit.Test

/**
 * Erzeugt die README-Bilder auf der JVM (Paparazzi) mit neutralen Demo-Daten.
 * Echte Bausteine der App (Theme, Glas, Kopfzeile, Leiste, Player-Knöpfe, Einstellungszeilen);
 * Listenzeilen ohne Datenbank nachgebaut. `./gradlew :mobile:recordPaparazziDebug`
 */
class ReadmeScreenshots {
    @get:Rule
    val paparazzi = Paparazzi(
        deviceConfig = DeviceConfig.PIXEL_5.copy(softButtons = false),
        showSystemUi = false,
        maxPercentDifference = 1.0,
    )

    private val settings = AppSettings()
    private val base = 1_760_000_000_000L
    private val now = base + 20 * 60_000L

    private fun img(name: String): ImageBitmap =
        BitmapFactory.decodeStream(javaClass.classLoader!!.getResourceAsStream("demo/$name")).asImageBitmap()

    private val channels = listOf(
        "Demo 1" to ("Tagesüberblick" to "Magazin am Mittag"),
        "Nachrichten HD" to ("Nachrichten" to "Wetter kompakt"),
        "Sport Live" to ("Live-Übertragung" to "Sport am Abend"),
        "Kino Klassik" to ("Spielfilm" to "Filmtipps"),
        "Doku Welt" to ("Dokumentation" to "Reportage"),
        "Musik TV" to ("Charts" to "Konzert"),
        "Kinderkanal" to ("Zeichentrick" to "Wissen für Kinder"),
        "Natur & Reisen" to ("Wilde Küsten" to "Reiseziele"),
        "Wetter 24" to ("Wetter aktuell" to "Wetter aktuell"),
    )

    private fun nn(cur: String, next: String, done: Float) = NowNext(
        EpgEntity(0, "", (now - done * 3_600_000L).toLong(), (now + (1 - done) * 3_600_000L).toLong(), cur, null),
        EpgEntity(0, "", (now + (1 - done) * 3_600_000L).toLong(), (now + (2 - done) * 3_600_000L).toLong(), next, null),
    )

    @Composable
    private fun Frame(tab: Tab, content: @Composable () -> Unit) {
        AppTheme(settings, isTv = false) {
            AppBackground {
                Box(Modifier.fillMaxSize()) {
                    Column(Modifier.fillMaxSize().padding(top = 28.dp)) { content() }
                    Box(Modifier.align(Alignment.BottomCenter)) { BottomBar(tab) {} }
                }
            }
        }
    }

    @Composable
    private fun Chips(vararg names: String) {
        Row(
            Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            names.forEachIndexed { i, n -> GlassChip(n, i == 0) {} }
        }
    }

    @Composable
    private fun ChannelRowDemo(num: Int, name: String, logo: ImageBitmap, nowNext: NowNext, fav: Boolean) {
        val s = LocalAppStyle.current
        Row(
            Modifier
                .fillMaxWidth()
                .glass(RoundedCornerShape(18.dp))
                .padding(start = 12.dp, end = 4.dp, top = 9.dp, bottom = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(num.toString(), color = s.onSurfaceDim, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center, modifier = Modifier.width(38.dp))
            Box(Modifier.size(48.dp).clip(RoundedCornerShape(12.dp)).background(Color(0x1AFFFFFF)).padding(5.dp)) {
                Image(logo, null, Modifier.fillMaxSize().clip(RoundedCornerShape(8.dp)), contentScale = ContentScale.Fit)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(name, color = s.onSurface, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                Text(nowNext.now!!.title, color = s.onSurface.copy(alpha = 0.85f), fontSize = 12.5.sp, maxLines = 1)
                Spacer(Modifier.height(4.dp))
                LinearProgressIndicator(
                    progress = { nowNext.progress(now) },
                    modifier = Modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(2.dp)),
                    color = s.accent,
                    trackColor = Color.White.copy(alpha = 0.14f),
                )
                Text("Danach: ${nowNext.next!!.title}", color = s.onSurfaceDim, fontSize = 11.5.sp, maxLines = 1, modifier = Modifier.padding(top = 3.dp))
            }
            Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                Icon(if (fav) Icons.Rounded.Star else Icons.Rounded.StarBorder, null, tint = if (fav) Color(0xFFFFC857) else s.onSurfaceDim)
            }
        }
    }

    @Test
    fun liveTv() = paparazzi.snapshot {
        Frame(Tab.LIVE) {
            ScreenHeader("Live TV", "${channels.size} Sender")
            Chips("Alle Sender", "★ Favoriten", "Nachrichten", "Unterhaltung")
            Column(Modifier.padding(horizontal = 14.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                channels.forEachIndexed { i, (n, p) ->
                    ChannelRowDemo(i + 1, n, img("$i.png"), nn(p.first, p.second, 0.15f + (i * 0.37f) % 0.8f), fav = i == 1 || i == 4)
                }
            }
        }
    }

    @Test
    fun filme() = paparazzi.snapshot {
        val titles = listOf("Der lange Weg", "Nachtzug", "Sommerwind", "Die Insel", "Stadt aus Glas", "Blaue Stunde", "Funkstille", "Am Horizont", "Der lange Weg")
        Frame(Tab.MOVIES) {
            ScreenHeader("Filme", "8 Titel")
            Chips("Alle Filme", "★ Favoriten", "Abenteuer", "Drama")
            val s = LocalAppStyle.current
            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                for (r in 0 until 3) {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        for (c in 0 until 3) {
                            val i = r * 3 + c
                            Column(Modifier.weight(1f)) {
                                Box(Modifier.fillMaxWidth().aspectRatio(2f / 3f).glass(RoundedCornerShape(16.dp))) {
                                    Image(img("poster${i % 8}.jpg"), null, Modifier.fillMaxSize().clip(RoundedCornerShape(16.dp)), contentScale = ContentScale.Crop)
                                    if (i == 2) StarBadge(Modifier.align(Alignment.TopEnd).padding(6.dp))
                                }
                                Text(titles[i], color = s.onSurface, fontSize = 12.5.sp, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp, start = 2.dp))
                            }
                        }
                    }
                }
            }
        }
    }

    @Test
    fun player() = paparazzi.snapshot {
        AppTheme(settings, isTv = false) {
            Box(Modifier.fillMaxSize().background(Color.Black)) {
                Image(img("frame.jpg"), null, Modifier.fillMaxWidth().aspectRatio(16f / 9f).align(Alignment.Center), contentScale = ContentScale.Crop)
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xB3000000), Color.Transparent, Color.Transparent, Color(0xCC000000)))))
                Row(Modifier.fillMaxWidth().padding(top = 36.dp, start = 16.dp, end = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    GlassIconButton(Icons.AutoMirrored.Rounded.ArrowBack, "Zurück", {})
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Nachrichten HD", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                        Text("Nachrichten", color = Color(0xCCFFFFFF), fontSize = 13.sp)
                    }
                    GlassIconButton(Icons.Rounded.Star, "Favorit", {}, tint = Color(0xFFFFC857))
                    Spacer(Modifier.width(8.dp))
                    GlassIconButton(Icons.Rounded.Tune, "Einstellungen", {})
                }
                Row(Modifier.align(Alignment.Center), verticalAlignment = Alignment.CenterVertically) {
                    TransportButton(Icons.Rounded.SkipPrevious, 52) {}
                    Spacer(Modifier.width(18.dp))
                    TransportButton(Icons.Rounded.Pause, 72, accent = true) {}
                    Spacer(Modifier.width(18.dp))
                    TransportButton(Icons.Rounded.SkipNext, 52) {}
                }
                // Programmkarte unter dem Bild (Pixel 5: 393 × 851 dp, Bild 221 dp hoch)
                Box(Modifier.fillMaxWidth().padding(top = (851.dp + 221.dp) / 2 + 12.dp, start = 16.dp, end = 16.dp)) {
                    LiveInfo(PlayerUi(nowNext = nn("Nachrichten", "Wetter kompakt", 0.4f)))
                }
                Box(Modifier.align(Alignment.BottomCenter).padding(bottom = 40.dp)) { ArchivePill(enabled = true) {} }
            }
        }
    }

    @Test
    fun einstellungen() = paparazzi.snapshot {
        Frame(Tab.SETTINGS) {
            ScreenHeader("Einstellungen")
            val s = LocalAppStyle.current
            Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Column(Modifier.fillMaxWidth().glass(RoundedCornerShape(20.dp), strong = true).padding(16.dp)) {
                    Text("Aktives Profil", color = s.onSurfaceDim, fontSize = 12.sp)
                    Text("Demo", color = s.onSurface, fontSize = 19.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(4.dp))
                    Text("Inhalte: 07.10. 12:00  ·  EPG: 07.10. 12:00", color = s.onSurfaceDim, fontSize = 12.sp)
                    Spacer(Modifier.height(8.dp))
                    Text("Profile verwalten", color = s.accent, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                }
                for (section in SettingsSchema.sections.take(2)) {
                    Text(section.title, color = s.onSurface, fontSize = 17.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 6.dp, top = 8.dp))
                    Column(Modifier.fillMaxWidth().glass(RoundedCornerShape(20.dp))) {
                        section.items.forEachIndexed { i, def ->
                            if (i > 0) Box(Modifier.fillMaxWidth().height(1.dp).padding(horizontal = 16.dp).background(Color.White.copy(alpha = 0.08f)))
                            SettingRow(def, settings, false, 0f, "", { _, _ -> }, {})
                        }
                    }
                }
            }
        }
    }
}
