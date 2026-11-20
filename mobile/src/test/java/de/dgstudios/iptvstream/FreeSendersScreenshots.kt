package de.dgstudios.iptvstream

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.cash.paparazzi.Paparazzi
import de.dgstudios.iptvstream.core.settings.AppSettings
import de.dgstudios.iptvstream.core.ui.AppBackground
import de.dgstudios.iptvstream.core.ui.AppTheme
import de.dgstudios.iptvstream.core.ui.ProfileFormState
import org.junit.Rule
import org.junit.Test

/** Formular „Freie Sender“ und Sortier-Menü am Handy. */
class FreeSendersScreenshots {
    @get:Rule
    val paparazzi = Paparazzi(
        deviceConfig = BrowserScreenshots.SMALL,
        showSystemUi = false,
        maxPercentDifference = 1.0,
    )

    @Test
    fun formularFreieSender() {
        paparazzi.snapshot {
            AppTheme(AppSettings(), isTv = false) {
                AppBackground {
                    Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        val form = ProfileFormState(null, startFree = true)
                        form.toggleLanguage("eng")
                        FreeSendersSection(form)
                    }
                }
            }
        }
    }

    @Test
    fun menueLangesDruecken() {
        paparazzi.snapshot {
            AppTheme(AppSettings(), isTv = false) {
                AppBackground {
                    SortActionMenu(
                        title = "Das Erste HD",
                        onDismiss = {},
                        onMove = {},
                        onToStart = {},
                        onToEnd = {},
                        onHide = {},
                        extra = listOf(
                            "Zu Favoriten hinzufügen" to {},
                            "Als Startsender festlegen" to {},
                        ),
                    )
                }
            }
        }
    }

    @Test
    fun verschiebenModus() {
        paparazzi.snapshot {
            AppTheme(AppSettings(), isTv = false) {
                AppBackground {
                    Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        MoveHintBar(
                            name = "Das Erste HD",
                            onUp = {},
                            onDown = {},
                            onDone = {},
                            onAlpha = {},
                            onReset = {},
                        )
                        HiddenLine("2 ausgeblendete Sender · zum Einblenden tippen") {}
                    }
                }
            }
        }
    }
}
