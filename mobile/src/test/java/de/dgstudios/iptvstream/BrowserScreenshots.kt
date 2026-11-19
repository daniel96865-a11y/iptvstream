package de.dgstudios.iptvstream

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import de.dgstudios.iptvstream.core.browser.BrowserChrome
import de.dgstudios.iptvstream.core.settings.AppSettings
import de.dgstudios.iptvstream.core.ui.AppBackground
import de.dgstudios.iptvstream.core.ui.AppTheme
import org.junit.Rule
import org.junit.Test

/** Handy-Ansicht des Browsers (Beta) und der unteren Leiste mit „Browser“. */
class BrowserScreenshots {
    @get:Rule
    val paparazzi = Paparazzi(
        deviceConfig = DeviceConfig.PIXEL_5.copy(softButtons = false),
        showSystemUi = false,
        maxPercentDifference = 1.0,
    )

    @Test
    fun browserStartseite() {
        val ui = BrowserChrome(paparazzi.context, tv = false)
        ui.attachWeb(android.view.View(paparazzi.context))
        ui.startPage.bringToFront()
        ui.setStatus("Werbeblocker an · 92.764 Regeln · eingebauter Stand\nEasyList, EasyList Germany, Peter Lowe")
        ui.address.setText("https://duckduckgo.com/?q=wetter")
        ui.setBlocked(12)
        paparazzi.snapshot(ui.root)
    }

    @Test
    fun untereLeisteMitBrowser() {
        paparazzi.snapshot {
            AppTheme(AppSettings(), isTv = false) {
                AppBackground {
                    Box(Modifier.fillMaxSize()) {
                        Box(Modifier.align(Alignment.BottomCenter)) { BottomBar(Tab.LIVE) {} }
                    }
                }
            }
        }
    }
}
