package de.dgstudios.iptvstream.core

/**
 * Anzeigename der Server-Anmeldung (Benutzer/Passwort über die Xtream-kompatible API).
 * Im Play-Build neutral „Server-Login“, weil „Xtream Codes“ ein bekannter Markenname eines
 * eingestellten Dienstes ist und bei der Google-Prüfung als Piraterie-Hinweis gelesen werden kann.
 * Technisch ändert sich nichts.
 */
object LoginLabels {
    private val play = BuildConfig.DISTRIBUTION == "play"
    val SERVER: String = if (play) "Server-Login" else "Xtream Codes"
    /** Kurzform in Hinweisen wie „nur Xtream“. */
    val SERVER_SHORT: String = if (play) "Server-Login" else "Xtream"
}
