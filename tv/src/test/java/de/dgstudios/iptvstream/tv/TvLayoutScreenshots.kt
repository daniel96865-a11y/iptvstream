package de.dgstudios.iptvstream.tv

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
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
import de.dgstudios.iptvstream.core.ui.ClockOverlay
import org.junit.Rule
import org.junit.Test

/**
 * Startseite Live TV in 1920×1080 (960×540 dp) mit sicherem Bereich gegen Overscan.
 * Die rote Linie zeigt die 5-%-Grenze der Android-TV-Richtlinie; alles muss innerhalb liegen.
 */
class TvLayoutScreenshots {
    @get:Rule
    val paparazzi = Paparazzi(
        deviceConfig = DeviceConfig(
            screenHeight = 1080, screenWidth = 1920, xdpi = 320, ydpi = 320,
            orientation = ScreenOrientation.LANDSCAPE, density = Density.XHIGH, uiMode = UiMode.TELEVISION, softButtons = false,
        ),
        showSystemUi = false,
        maxPercentDifference = 1.0,
    )

    @Composable
    private fun Home(margin: String) {
        val settings = AppSettings(mapOf("tv_margin" to margin))
        AppTheme(settings, isTv = true) {
            CompositionLocalProvider(LocalTvSafe provides tvSafeFor(margin)) {
                AppBackground {
                    // 5-%-Linie (Richtlinie) als Hilfe im Vorschaubild.
                    Box(Modifier.fillMaxSize().padding(horizontal = 48.dp, vertical = 27.dp).border(1.dp, Color.Red.copy(alpha = 0.7f)))
                    Box(Modifier.fillMaxSize().tvSafePadding()) {
                        Column(Modifier.fillMaxSize()) {
                            val req = remember { TvTab.entries.map { FocusRequester() } }
                            TvTabBar(
                                reserveEnd = 76.dp,
                                modifier = Modifier.horizontalScroll(rememberScrollState()),
                                tab = TvTab.LIVE,
                                requesters = req,
                                onTab = {},
                            )
                            Spacer(Modifier.height(10.dp))
                            TvSourceChips(
                                hasProvider = true, freeOn = false, focus = remember { SourceBarFocus() },
                                onMine = {}, onFree = {}, onUp = {}, onDown = {},
                            )
                            Row(Modifier.fillMaxSize()) {
                                Column(Modifier.width(240.dp).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    for (c in listOf("Alle Sender", "Favoriten", "Zuletzt gesehen", "Deutschland", "Österreich")) Row56(c)
                                }
                                Spacer(Modifier.width(16.dp))
                                Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    for ((i, c) in listOf("Das Erste HD", "ZDF HD", "WDR HD Köln", "arte HD", "3sat HD").withIndex()) {
                                        Row(
                                            Modifier.fillMaxWidth().height(78.dp).background(Color(0x22FFFFFF), RoundedCornerShape(14.dp)).padding(horizontal = 14.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                        ) {
                                            Text("${i + 1}", color = Color(0xB3FFFFFF), fontSize = 18.sp, modifier = Modifier.width(62.dp))
                                            Column(Modifier.weight(1f)) {
                                                Text(c, color = Color.White, fontSize = 20.sp)
                                                Text("20:15 Tagesschau · bis 20:30", color = Color(0xB3FFFFFF), fontSize = 15.sp)
                                            }
                                            Text("rechter Rand", color = Color(0x99FFFFFF), fontSize = 14.sp)
                                        }
                                    }
                                }
                            }
                        }
                    }
                    ClockOverlay(settings, Modifier.padding(horizontal = tvSafe.horizontal - 12.dp, vertical = tvSafe.vertical - 12.dp))
                }
            }
        }
    }

    @Composable
    private fun Row56(text: String) {
        Box(
            Modifier.fillMaxWidth().height(56.dp).background(Color(0x22FFFFFF), RoundedCornerShape(14.dp)).padding(horizontal = 14.dp),
            contentAlignment = Alignment.CenterStart,
        ) { Text(text, color = Color.White, fontSize = 18.sp) }
    }

    @Test
    fun startseiteNormal() {
        paparazzi.snapshot { Home("normal") }
    }

    @Test
    fun startseiteRandGroesser() {
        paparazzi.snapshot { Home("more") }
    }

    @Test
    fun startseiteRandMaximal() {
        paparazzi.snapshot { Home("max") }
    }
}
