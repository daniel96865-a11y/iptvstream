package de.dgstudios.iptvstream.tv

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.android.resources.Density
import com.android.resources.ScreenOrientation
import com.android.resources.UiMode
import de.dgstudios.iptvstream.core.browser.BrowserChrome
import de.dgstudios.iptvstream.core.settings.AppSettings
import de.dgstudios.iptvstream.core.ui.AppBackground
import de.dgstudios.iptvstream.core.ui.AppTheme
import org.junit.Rule
import org.junit.Test

/** TV-Ansicht: Tab-Leiste mit „Browser“ und die Browser-Startseite. `./gradlew :tv:recordPaparazziDebug` */
class TvBrowserScreenshots {
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

    @Test
    fun tabLeisteMitBrowser() {
        paparazzi.snapshot {
            AppTheme(AppSettings(), isTv = true) {
                AppBackground {
                    Box(Modifier.fillMaxSize().padding(horizontal = 48.dp, vertical = 27.dp)) {
                        // Wie in TvHome, mit Fokus auf „Browser“ (Leiste ist nach rechts gerollt).
                        val scroll = androidx.compose.foundation.rememberScrollState(Int.MAX_VALUE)
                        Row(
                            Modifier.padding(end = 110.dp).horizontalScroll(scroll),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            for (t in TvTab.entries) {
                                TabButton(t = t, selected = t == TvTab.LIVE, requester = androidx.compose.runtime.remember { androidx.compose.ui.focus.FocusRequester() }, onFocus = {})
                            }
                            BrowserButton {}
                        }
                    }
                }
            }
        }
    }

    @Test
    fun browserStartseite() {
        val ui = BrowserChrome(paparazzi.context, tv = true)
        ui.attachWeb(android.view.View(paparazzi.context))
        ui.startPage.bringToFront()
        ui.setStatus("Werbeblocker an · 92.764 Regeln · eingebauter Stand\nEasyList, EasyList Germany, Peter Lowe")
        ui.address.requestFocus()
        paparazzi.snapshot(ui.root)
    }

    @Test
    fun browserLeisteSchliessenFokussiert() {
        val ui = BrowserChrome(paparazzi.context, tv = true)
        ui.attachWeb(android.view.View(paparazzi.context).apply { setBackgroundColor(0xFFEEEEEE.toInt()) })
        ui.startPage.visibility = android.view.View.GONE
        ui.address.setText("http://192.168.1.20:8080/web/")
        ui.setBlocked(3)
        ui.close.isFocusableInTouchMode = true
        ui.close.requestFocus()
        paparazzi.snapshot(ui.root)
    }
}
