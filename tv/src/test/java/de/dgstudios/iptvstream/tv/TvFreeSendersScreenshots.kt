package de.dgstudios.iptvstream.tv

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.android.resources.Density
import com.android.resources.ScreenOrientation
import com.android.resources.UiMode
import de.dgstudios.iptvstream.core.settings.AppSettings
import de.dgstudios.iptvstream.core.ui.AppBackground
import de.dgstudios.iptvstream.core.ui.AppTheme
import de.dgstudios.iptvstream.core.ui.ProfileFormState
import org.junit.Rule
import org.junit.Test

/** TV-Formular „Freie Sender“ (Sprachauswahl). */
class TvFreeSendersScreenshots {
    @get:Rule
    val paparazzi = Paparazzi(
        deviceConfig = DeviceConfig(
            screenHeight = 1080, screenWidth = 1920, xdpi = 320, ydpi = 320,
            orientation = ScreenOrientation.LANDSCAPE, density = Density.XHIGH, uiMode = UiMode.TELEVISION, softButtons = false,
        ),
        showSystemUi = false,
        maxPercentDifference = 1.0,
    )

    @Test
    fun formularFreieSender() {
        paparazzi.snapshot {
            AppTheme(AppSettings(), isTv = true) {
                AppBackground {
                    Column(Modifier.fillMaxSize().padding(horizontal = 48.dp, vertical = 27.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        val form = ProfileFormState(null, startFree = true)
                        form.toggleLanguage("eng")
                        TvTitle("Neues Profil")
                        TvFreeSenders(form)
                    }
                }
            }
        }
    }
}
