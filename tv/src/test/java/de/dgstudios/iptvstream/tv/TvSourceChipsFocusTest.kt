package de.dgstudios.iptvstream.tv

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.remember
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.InputModeManager
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.unit.dp
import de.dgstudios.iptvstream.core.settings.AppSettings
import de.dgstudios.iptvstream.core.ui.AppTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Fernbedienung: „Meine Sender“ ↔ „Freie Sender“ mit Links/Rechts, Hoch/Runter verlassen die Leiste. */
@OptIn(ExperimentalTestApi::class, ExperimentalComposeUiApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w960dp-h540dp-television-xhdpi", application = android.app.Application::class)
class TvSourceChipsFocusTest {
    @get:Rule
    val rule = createComposeRule()

    @Test
    fun dpadReachesFreieSenderAndBack() {
        var ups = 0
        var downs = 0
        lateinit var focus: SourceBarFocus
        lateinit var input: InputModeManager
        rule.setContent {
            input = LocalInputModeManager.current
            AppTheme(AppSettings(), isTv = true) {
                focus = remember { SourceBarFocus() }
                Column {
                    Box(Modifier.size(40.dp).focusable())
                    TvSourceChips(
                        hasProvider = true, freeOn = false, focus = focus,
                        onMine = {}, onFree = {}, onUp = { ups++ }, onDown = { downs++ },
                    )
                    Box(Modifier.size(40.dp).focusable())
                }
            }
        }
        rule.runOnIdle { input.requestInputMode(InputMode.Keyboard); focus.mine.requestFocus() }
        rule.onNodeWithText("Meine Sender").assertIsFocused()

        rule.onRoot().performKeyInput { pressKey(Key.DirectionRight) }
        rule.onNodeWithText("Freie Sender").assertIsFocused()

        // Rechts am Ende bleibt der Fokus stehen (nicht ins Leere).
        rule.onRoot().performKeyInput { pressKey(Key.DirectionRight) }
        rule.onNodeWithText("Freie Sender").assertIsFocused()

        rule.onRoot().performKeyInput { pressKey(Key.DirectionLeft) }
        rule.onNodeWithText("Meine Sender").assertIsFocused()

        rule.onRoot().performKeyInput { pressKey(Key.DirectionUp) }
        rule.onRoot().performKeyInput { pressKey(Key.DirectionDown) }
        rule.runOnIdle {
            assertEquals(1, ups)
            assertEquals(1, downs)
        }
    }

    @Test
    fun onlyFreeChipWithoutProvider() {
        lateinit var focus: SourceBarFocus
        lateinit var input: InputModeManager
        rule.setContent {
            input = LocalInputModeManager.current
            AppTheme(AppSettings(), isTv = true) {
                focus = remember { SourceBarFocus() }
                TvSourceChips(
                    hasProvider = false, freeOn = true, focus = focus,
                    onMine = {}, onFree = {}, onUp = {}, onDown = {},
                )
            }
        }
        rule.runOnIdle { input.requestInputMode(InputMode.Keyboard); focus.free.requestFocus() }
        rule.onNodeWithText("Freie Sender").assertIsFocused()
        rule.onRoot().performKeyInput { pressKey(Key.DirectionLeft) }
        rule.onNodeWithText("Freie Sender").assertIsFocused()
    }
}
