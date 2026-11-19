package de.dgstudios.iptvstream.core.browser

import android.view.KeyEvent
import de.dgstudios.iptvstream.core.browser.TvRemote.Cmd
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TvRemoteTest {
    @Test
    fun allOkVariantsClick() {
        for (k in listOf(KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_BUTTON_SELECT)) {
            assertEquals(Cmd.CLICK, TvRemote.command(k))
        }
        assertEquals(Cmd.LEFT, TvRemote.command(KeyEvent.KEYCODE_DPAD_LEFT))
        assertEquals(Cmd.NONE, TvRemote.command(KeyEvent.KEYCODE_VOLUME_UP))
    }

    @Test
    fun normalPressActsOnDownOnly() {
        val t = TvRemote.Tracker()
        assertTrue(t.shouldAct(KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.ACTION_DOWN, 0, clickOnce = false))
        assertFalse(t.shouldAct(KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.ACTION_UP, 0, clickOnce = false))
    }

    @Test
    fun cecUpOnlyCountsAsPress() {
        val t = TvRemote.Tracker()
        assertTrue(t.shouldAct(KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.ACTION_UP, 0, clickOnce = false))
        assertTrue(t.shouldAct(KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.ACTION_UP, 0, clickOnce = true))
    }

    @Test
    fun holdingMovesButClicksOnce() {
        val t = TvRemote.Tracker()
        assertTrue(t.shouldAct(KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.ACTION_DOWN, 0, false))
        assertTrue(t.shouldAct(KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.ACTION_DOWN, 3, false))
        assertTrue(t.shouldAct(KeyEvent.KEYCODE_ENTER, KeyEvent.ACTION_DOWN, 0, true))
        assertFalse(t.shouldAct(KeyEvent.KEYCODE_ENTER, KeyEvent.ACTION_DOWN, 1, true))
        assertFalse(t.shouldAct(KeyEvent.KEYCODE_ENTER, KeyEvent.ACTION_UP, 0, true))
    }

    @Test
    fun downHandledElsewhereDoesNotRepeatOnUp() {
        val t = TvRemote.Tracker()
        t.track(KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.ACTION_DOWN)
        assertFalse(t.shouldAct(KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.ACTION_UP, 0, false))
    }

    @Test
    fun singlePressesAccelerateWhenQuick() {
        val a = TvRemote.Accel(400)
        assertEquals(0, a.level(1_000, 0))
        assertEquals(1, a.level(1_200, 0))
        assertEquals(2, a.level(1_400, 0))
        assertEquals(0, a.level(3_000, 0)) // Pause: wieder langsam
        assertEquals(5, a.level(3_100, 5)) // echte Wiederholung zählt auch
    }
}
