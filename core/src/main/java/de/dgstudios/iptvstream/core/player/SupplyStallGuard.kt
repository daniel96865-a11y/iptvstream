package de.dgstudios.iptvstream.core.player

/**
 * Erkennt einen Daten-Stillstand: Der Player will nachladen, aber es kommen keine Bytes mehr an,
 * obwohl die Verbindung offen bleibt (der Anbieter hält sie, schickt aber nichts).
 *
 * Meldet nach [thresholdMs] ohne Bytes einen Eingriff. Bleibt auch die neue Verbindung stumm, kommt der
 * nächste Eingriff erst nach 2×, 3× … der Schwelle, höchstens [maxKicks]-mal ohne Daten dazwischen.
 * Danach greifen wieder die normalen Wächter (keine Endlosschleife). Sobald Bytes fließen, ist alles zurückgesetzt.
 */
class SupplyStallGuard(
    private val thresholdMs: Long = THRESHOLD_MS,
    private val maxKicks: Int = MAX_KICKS,
) {
    private var lastBytes = -1L
    private var dataAt = 0L
    private var kickAt = 0L

    /** Eingriffe seit den letzten angekommenen Daten. */
    var kicks = 0
        private set

    fun reset() {
        lastBytes = -1L
        kicks = 0
    }

    /** Wie lange schon keine Bytes mehr ankommen (0, solange nicht beobachtet wird). */
    fun stalledMs(nowMs: Long): Long = if (lastBytes < 0L) 0L else (nowMs - dataAt).coerceAtLeast(0L)

    /**
     * [active]: Der Player lädt gerade (will Daten). Während er wegen vollem Puffer pausiert, ist
     * Stille normal und zählt nicht. True = jetzt die Verbindung ersetzen.
     */
    fun observe(nowMs: Long, active: Boolean, totalBytes: Long): Boolean {
        if (!active) {
            lastBytes = -1L
            return false
        }
        if (lastBytes < 0L || totalBytes != lastBytes) {
            if (lastBytes >= 0L) kicks = 0
            lastBytes = totalBytes
            dataAt = nowMs
            return false
        }
        if (kicks >= maxKicks) return false
        val since = if (kicks == 0) dataAt else kickAt
        if (nowMs - since < thresholdMs * (kicks + 1)) return false
        kicks++
        kickAt = nowMs
        return true
    }

    companion object {
        const val THRESHOLD_MS = 3_000L
        const val MAX_KICKS = 3
    }
}
