package de.dgstudios.iptvstream.core.browser

import android.view.KeyEvent

/**
 * Fernbedienung im TV-Browser, robust für alle Wege, auf denen Tasten ankommen:
 * Stick-Fernbedienung (Bluetooth, mit Wiederholung), TV-Fernbedienung über HDMI-CEC
 * (oft ohne Wiederholung, teils nur „losgelassen“), Handy-Fernbedienungs-Apps (ADB/Remote-Protokoll).
 * Keine Filterung nach InputDevice-Quelle. Reine Logik, ohne Gerät testbar.
 */
object TvRemote {
    enum class Cmd { NONE, UP, DOWN, LEFT, RIGHT, CLICK }

    fun command(keyCode: Int): Cmd = when (keyCode) {
        KeyEvent.KEYCODE_DPAD_UP -> Cmd.UP
        KeyEvent.KEYCODE_DPAD_DOWN -> Cmd.DOWN
        KeyEvent.KEYCODE_DPAD_LEFT -> Cmd.LEFT
        KeyEvent.KEYCODE_DPAD_RIGHT -> Cmd.RIGHT
        KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER,
        KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_BUTTON_SELECT -> Cmd.CLICK
        else -> Cmd.NONE
    }

    fun isOk(keyCode: Int) = command(keyCode) == Cmd.CLICK

    /**
     * Merkt sich gedrückte Tasten. Ein „Losgelassen“ ohne vorheriges „Gedrückt“ (manche CEC-Fernbedienungen)
     * zählt als ganzer Tastendruck. Wird bei jedem Tastenereignis aufgerufen, auch wenn ein anderer Teil es nutzt.
     */
    class Tracker {
        private val down = HashSet<Int>()

        /** true = dieses Ereignis soll die Aktion auslösen. */
        fun shouldAct(keyCode: Int, action: Int, repeatCount: Int, clickOnce: Boolean): Boolean = when (action) {
            KeyEvent.ACTION_DOWN -> {
                down.add(keyCode)
                !clickOnce || repeatCount == 0
            }
            KeyEvent.ACTION_UP -> !down.remove(keyCode)
            else -> false
        }

        /** Gedrückt registrieren, ohne zu handeln (Taste wurde anderswo verarbeitet). */
        fun track(keyCode: Int, action: Int) {
            if (action == KeyEvent.ACTION_DOWN) down.add(keyCode) else if (action == KeyEvent.ACTION_UP) down.remove(keyCode)
        }

        fun reset() = down.clear()
    }

    /**
     * Beschleunigung auch ohne Tasten-Wiederholung: schnelle Einzeldrücke kurz hintereinander
     * zählen hoch, nach einer Pause beginnt es wieder langsam.
     */
    class Accel(private val windowMs: Long = 400) {
        private var last = 0L
        private var streak = 0

        fun level(nowMs: Long, repeatCount: Int): Int {
            streak = if (last != 0L && nowMs - last <= windowMs) minOf(streak + 1, 12) else 0
            last = nowMs
            return maxOf(repeatCount, streak)
        }
    }
}
