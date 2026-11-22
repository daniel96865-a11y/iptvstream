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
import de.dgstudios.iptvstream.core.data.FreeTv
import de.dgstudios.iptvstream.core.settings.AppSettings
import de.dgstudios.iptvstream.core.ui.AppBackground
import de.dgstudios.iptvstream.core.ui.AppTheme
import de.dgstudios.iptvstream.core.ui.ProfileFormState
import org.junit.Rule
import org.junit.Test

/** TV-Formular „Freie Sender“ und Sortier-Menü. */
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
                        // Fernseher: nur Deutsch vorgewählt.
                        val form = ProfileFormState(null, startFree = true, freeDefault = FreeTv.TV_DEFAULT_LANGUAGES)
                        TvTitle("Neues Profil")
                        TvFreeSenders(form)
                    }
                }
            }
        }
    }

    @Test
    fun menueLangesDruecken() {
        paparazzi.snapshot {
            AppTheme(AppSettings(), isTv = true) {
                AppBackground {
                    TvActionDialog(
                        title = "Das Erste HD",
                        actions = listOf(
                            TvAction("Verschieben") {},
                            TvAction("Ganz nach oben") {},
                            TvAction("Ganz nach unten") {},
                            TvAction("Ausblenden") {},
                            TvAction("Zu Favoriten hinzufügen") {},
                            TvAction("Als Startsender festlegen") {},
                            TvAction("Schließen") {},
                        ),
                        onDismiss = {},
                    )
                }
            }
        }
    }

    @Test
    fun verschiebenModus() {
        paparazzi.snapshot {
            AppTheme(AppSettings(), isTv = true) {
                AppBackground {
                    Column(Modifier.fillMaxSize().padding(horizontal = 48.dp, vertical = 27.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        TvMoveHintBar(
                            name = "Das Erste HD",
                            onUp = {},
                            onDown = {},
                            onDone = {},
                            onAlpha = {},
                            onReset = {},
                        )
                    }
                }
            }
        }
    }
}
