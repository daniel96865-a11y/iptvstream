package de.dgstudios.iptvstream.tv

/** Was die OK-Taste der Fernbedienung im Player macht. */
enum class OkAction { NONE, COMMIT_NUMBER, SHOW_INFO, TOGGLE_PLAY }

object TvOkKey {
    /**
     * Erstes OK blendet nur die Infoleiste ein (Sender, laufende Sendung, Steuerung) und pausiert nicht.
     * Ist die Leiste schon sichtbar, pausiert OK bzw. spielt weiter. Gehaltenes OK (Wiederholung) tut nichts.
     * Panels, Fehler-Knopf und „Nächste Folge“ haben eigenen Fokus und bekommen OK direkt.
     */
    fun action(numberPending: Boolean, repeatCount: Int, infoVisible: Boolean): OkAction = when {
        numberPending -> OkAction.COMMIT_NUMBER
        repeatCount > 0 -> OkAction.NONE
        infoVisible -> OkAction.TOGGLE_PLAY
        else -> OkAction.SHOW_INFO
    }
}
