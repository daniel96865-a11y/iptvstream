package de.dgstudios.iptvstream.core.player

import okhttp3.Call
import okhttp3.Request

/**
 * Merkt sich die zuletzt geöffneten Stream-Anfragen, damit sie vor einer neuen Verbindung hart
 * geschlossen werden können.
 *
 * Warum: Hängt ein Live-Strom (der Anbieter schickt nichts mehr), steckt der Lese-Thread des Players
 * in einem blockierenden Socket-Lesen. Ein Stopp des Players beendet das erst nach dem Lese-Zeitlimit
 * (15 s). Öffnet die Neuverbindung vorher eine zweite Verbindung, zählt der Anbieter zwei und lehnt ab
 * (z. B. HTTP 406). [closeAll] bricht die alten Anfragen sofort ab; der Socket wird damit geschlossen.
 */
class StreamConnections(private val client: Call.Factory) : Call.Factory {
    private val recent = ArrayDeque<Call>()

    override fun newCall(request: Request): Call {
        val call = client.newCall(request)
        synchronized(recent) {
            recent.addLast(call)
            // HLS öffnet laufend neue Anfragen (Playlist, Segmente). Abgeschlossene schaden beim Abbrechen
            // nicht, die Liste bleibt aber begrenzt.
            while (recent.size > MAX_TRACKED) recent.removeFirst()
        }
        return call
    }

    /** Bricht alle gemerkten Anfragen ab. Gibt die Zahl der noch nicht abgebrochenen zurück. */
    fun closeAll(): Int {
        val calls = synchronized(recent) {
            val copy = recent.toList()
            recent.clear()
            copy
        }
        var n = 0
        for (c in calls) {
            if (!c.isCanceled()) {
                c.cancel()
                n++
            }
        }
        return n
    }

    companion object {
        const val MAX_TRACKED = 32
    }
}
