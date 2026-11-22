package de.dgstudios.iptvstream

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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

/** Formular „Freie Sender“ und Sortieren am Handy. */
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
                        onToStart = {},
                        onToEnd = {},
                        onHide = {},
                        extra = listOf(
                            "Zu Favoriten hinzufügen" to {},
                            "Als Startsender festlegen" to {},
                            "Alphabetisch" to {},
                            "Zurücksetzen" to {},
                        ),
                    )
                }
            }
        }
    }

    @Test
    fun ziehenAngehoben() {
        paparazzi.snapshot {
            AppTheme(AppSettings(), isTv = false) {
                AppBackground {
                    Column(Modifier.fillMaxSize().padding(top = 24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            GlassChip("Alle", selected = false, onClick = {})
                            GlassChip("Nachrichten", selected = false, lifted = true, onClick = {})
                            GlassChip("Sport", selected = false, onClick = {})
                        }
                        SortDragPreview(
                            names = listOf("ZDF HD", "Das Erste HD", "RTL"),
                            liftedIndex = 1,
                        )
                    }
                }
            }
        }
    }
}
