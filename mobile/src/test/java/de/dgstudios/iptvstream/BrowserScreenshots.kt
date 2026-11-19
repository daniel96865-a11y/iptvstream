package de.dgstudios.iptvstream

import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.core.graphics.Insets
import androidx.core.view.WindowInsetsCompat
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.android.resources.Density
import de.dgstudios.iptvstream.core.browser.BrowserChrome
import de.dgstudios.iptvstream.core.settings.AppSettings
import de.dgstudios.iptvstream.core.ui.AppBackground
import de.dgstudios.iptvstream.core.ui.AppTheme
import org.junit.Rule
import org.junit.Test

/**
 * Handy-Ansicht des Browsers (Beta) mit simulierter Status- und Navigationsleiste (Insets wie auf dem Gerät,
 * die Leisten selbst als halbtransparente Streifen darübergelegt). Größen: 360×800 dp, 411×891 dp, große Schrift.
 */
class BrowserScreenshots {
    @get:Rule
    val paparazzi = Paparazzi(
        deviceConfig = SMALL,
        showSystemUi = false,
        maxPercentDifference = 1.0,
    )

    private fun shot(config: DeviceConfig, url: String = "", imeDp: Int = 0) {
        paparazzi.unsafeUpdateConfig(deviceConfig = config)
        val ctx = paparazzi.context
        val d = ctx.resources.displayMetrics.density
        val status = (24 * d).toInt()
        val nav = (24 * d).toInt()
        val ui = BrowserChrome(ctx, tv = false)
        ui.attachWeb(View(ctx))
        ui.startPage.bringToFront()
        ui.setStatus("Werbeblocker an · 92.764 Regeln · eingebauter Stand\nEasyList, EasyList Germany, Peter Lowe")
        if (url.isNotEmpty()) ui.address.setText(url)
        ui.setBlocked(12)
        val insets = WindowInsetsCompat.Builder()
            .setInsets(WindowInsetsCompat.Type.statusBars(), Insets.of(0, status, 0, 0))
            .setInsets(WindowInsetsCompat.Type.navigationBars(), Insets.of(0, 0, 0, nav))
            .setInsets(WindowInsetsCompat.Type.ime(), Insets.of(0, 0, 0, (imeDp * d).toInt()))
            .build()
        ui.applyInsets(insets)

        val frame = FrameLayout(ctx)
        frame.addView(ui.root, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        frame.addView(
            TextView(ctx).apply {
                text = "  12:30                                   ▼ ▲ 🔋 87 %"
                setTextColor(Color.WHITE)
                textSize = 12f
                gravity = Gravity.CENTER_VERTICAL
                setBackgroundColor(0x66E53935)
            },
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, status, Gravity.TOP),
        )
        frame.addView(
            View(ctx).apply { setBackgroundColor(0x66E53935) },
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, nav, Gravity.BOTTOM),
        )
        if (imeDp > 0) {
            frame.addView(
                TextView(ctx).apply {
                    text = "Tastatur"
                    gravity = Gravity.CENTER
                    setTextColor(Color.WHITE)
                    setBackgroundColor(0xCC37474F.toInt())
                },
                FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (imeDp * d).toInt(), Gravity.BOTTOM),
            )
        }
        paparazzi.snapshot(frame)
    }

    @Test
    fun klein360x800() = shot(SMALL)

    @Test
    fun gross411x891MitAdresse() = shot(LARGE, url = "https://www.tagesschau.de/inland/innenpolitik/beispiel-artikel-100.html")

    @Test
    fun klein360x800GrosseSchrift() = shot(SMALL.copy(fontScale = 1.3f), url = "https://duckduckgo.com/?q=wetter+morgen")

    @Test
    fun klein360x800MitTastatur() = shot(SMALL, url = "wetter", imeDp = 280)

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

    companion object {
        /** 360×800 dp (z. B. 1080×2400 bei 3x). */
        val SMALL = DeviceConfig.PIXEL_5.copy(screenWidth = 1080, screenHeight = 2400, xdpi = 480, ydpi = 480, density = Density.XXHIGH, softButtons = false)
        /** 411×891 dp (bei 4x: 1644×3564). */
        val LARGE = DeviceConfig.PIXEL_5.copy(screenWidth = 1644, screenHeight = 3564, xdpi = 640, ydpi = 640, density = Density.XXXHIGH, softButtons = false)
    }
}
