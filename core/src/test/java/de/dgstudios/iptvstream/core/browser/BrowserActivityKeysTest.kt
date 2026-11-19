package de.dgstudios.iptvstream.core.browser

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import android.view.inputmethod.InputMethodManager
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper

/** Fernbedienung im TV-Browser: Tasten aus allen Quellen bewegen den Zeiger, OK klickt bzw. öffnet die Tastatur. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w960dp-h540dp-land-television-xhdpi")
class BrowserActivityKeysTest {
    private fun launch(url: String? = "http://192.168.1.20:8080/"): BrowserActivity {
        BrowserSession.clear(RuntimeEnvironment.getApplication())
        BrowserHolder.discard()
        val i = Intent(RuntimeEnvironment.getApplication(), BrowserActivity::class.java).putExtra(BrowserActivity.EXTRA_TV, true)
        if (url != null) i.data = Uri.parse(url)
        val a = Robolectric.buildActivity(BrowserActivity::class.java, i).setup().get()
        ShadowLooper.idleMainLooper()
        return a
    }

    @After
    fun tearDown() = BrowserHolder.discard()

    private fun key(action: Int, code: Int, repeat: Int = 0, source: Int = InputDevice.SOURCE_DPAD): KeyEvent {
        val t = SystemClock.uptimeMillis()
        return KeyEvent(t, t, action, code, repeat, 0, -1, 0, 0, source)
    }

    private fun press(a: BrowserActivity, code: Int, source: Int = InputDevice.SOURCE_DPAD) {
        a.dispatchKeyEvent(key(KeyEvent.ACTION_DOWN, code, source = source))
        a.dispatchKeyEvent(key(KeyEvent.ACTION_UP, code, source = source))
        ShadowLooper.idleMainLooper()
    }

    @Test
    fun arrowsMoveCursorFromAnySource() {
        val a = launch()
        val (x0, y0) = a.cursorPosition()
        press(a, KeyEvent.KEYCODE_DPAD_RIGHT)
        val (x1, _) = a.cursorPosition()
        assertTrue("rechts: $x0 -> $x1", x1 > x0)
        // HDMI-CEC / Tastatur-Quelle
        press(a, KeyEvent.KEYCODE_DPAD_DOWN, source = InputDevice.SOURCE_KEYBOARD)
        val (_, y1) = a.cursorPosition()
        assertTrue("runter: $y0 -> $y1", y1 > y0)
        press(a, KeyEvent.KEYCODE_DPAD_LEFT, source = InputDevice.SOURCE_UNKNOWN)
        assertTrue(a.cursorPosition().first < x1)
    }

    @Test
    fun upOnlyEventsFromCecStillMove() {
        val a = launch()
        val x0 = a.cursorPosition().first
        a.dispatchKeyEvent(key(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_RIGHT, source = InputDevice.SOURCE_KEYBOARD))
        assertTrue(a.cursorPosition().first > x0)
        // und genau einmal pro normalem Druck (kein Doppelschritt beim Loslassen)
        val x1 = a.cursorPosition().first
        a.dispatchKeyEvent(key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT))
        val x2 = a.cursorPosition().first
        a.dispatchKeyEvent(key(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_RIGHT))
        assertTrue(x2 > x1)
        assertEquals(x2, a.cursorPosition().first, 0.01f)
    }

    @Test
    fun okVariantsClickAtCursor() {
        val a = launch()
        var n = a.lastTapForTest
        for (k in listOf(KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_BUTTON_A)) {
            press(a, k)
            assertEquals("Klick mit $k", ++n, a.lastTapForTest)
        }
        // nur „losgelassen“ (CEC)
        a.dispatchKeyEvent(key(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_CENTER))
        assertEquals(++n, a.lastTapForTest)
        // Halten klickt nicht mehrfach
        a.dispatchKeyEvent(key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_CENTER))
        a.dispatchKeyEvent(key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_CENTER, repeat = 1))
        a.dispatchKeyEvent(key(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_CENTER))
        assertEquals(++n, a.lastTapForTest)
    }

    @Test
    fun okOnAddressOpensKeyboard() {
        val a = launch(url = null) // Startseite, Fokus in der Adressleiste
        assertTrue(a.addressField().isFocused)
        val imm = a.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(a.addressField().windowToken, 0)
        press(a, KeyEvent.KEYCODE_DPAD_CENTER)
        assertTrue("Tastatur offen", shadowOf(imm).isSoftInputVisible)
    }

    @Test
    fun enterAndUpOnlyOnAddressOpenKeyboardToo() {
        for (variant in 0..1) {
            val a = launch(url = null)
            val imm = a.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.hideSoftInputFromWindow(a.addressField().windowToken, 0)
            if (variant == 0) press(a, KeyEvent.KEYCODE_ENTER, source = InputDevice.SOURCE_KEYBOARD)
            else a.dispatchKeyEvent(key(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_CENTER, source = InputDevice.SOURCE_KEYBOARD))
            ShadowLooper.idleMainLooper()
            assertTrue("Variante $variant", shadowOf(imm).isSoftInputVisible)
            BrowserHolder.discard()
        }
    }

    @Test
    fun upFromPageTopGoesToToolbarAndDownBack() {
        val a = launch()
        repeat(30) { press(a, KeyEvent.KEYCODE_DPAD_UP) }
        assertTrue(a.addressField().isFocused)
        val before = a.cursorPosition()
        press(a, KeyEvent.KEYCODE_DPAD_DOWN)
        // erst der nächste Druck bewegt den Zeiger wieder
        assertEquals(before, a.cursorPosition())
        press(a, KeyEvent.KEYCODE_DPAD_DOWN)
        assertTrue(a.cursorPosition().second > before.second)
    }

    @Test
    fun closeButtonReachableAndClickableWithUpOnlyOk() {
        val a = launch()
        repeat(30) { press(a, KeyEvent.KEYCODE_DPAD_UP) }
        a.closeButton().requestFocus()
        a.dispatchKeyEvent(key(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_CENTER, source = InputDevice.SOURCE_KEYBOARD))
        assertTrue(a.isFinishing)
    }
}
