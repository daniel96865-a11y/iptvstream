package de.dgstudios.iptvstream.core.browser

/**
 * Zurück-Taste im Browser: kurz = eine Seite zurück, lang gehalten = Browser schließen.
 * Reine Logik, damit sie ohne Gerät testbar ist.
 */
object BackKey {
    const val LONG_MS = 600L

    enum class Action { NONE, BACK, CLOSE }

    /**
     * @param down Taste gedrückt (sonst losgelassen)
     * @param repeatCount Wiederholungen beim Halten
     * @param heldMs wie lange die Taste schon gehalten wird
     * @param longFlag System meldet langes Drücken (KeyEvent.isLongPress)
     * @param closed bei diesem Drücken wurde schon geschlossen
     * @param canceled das Loslassen wurde vom System abgebrochen
     */
    fun decide(down: Boolean, repeatCount: Int, heldMs: Long, longFlag: Boolean, closed: Boolean, canceled: Boolean = false): Action = when {
        down && repeatCount == 0 -> Action.NONE
        down -> if (!closed && (longFlag || heldMs >= LONG_MS)) Action.CLOSE else Action.NONE
        closed || canceled -> Action.NONE
        else -> Action.BACK
    }
}
