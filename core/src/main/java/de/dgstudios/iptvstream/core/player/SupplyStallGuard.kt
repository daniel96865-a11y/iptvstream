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
    /** Weniger neue Bytes zählen nicht als Zulauf (ein paar Füllbytes halten die Verbindung nur am Leben). */
    private val minBytes: Long = MIN_BYTES,
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
     *
     * Ein Stillstand wird erst abgebrochen, wenn der Puffer unter [kickBelowMs] fällt. Bei viel Restpuffer
     * bleibt die Verbindung, der Stillstand wird aber weitergezählt (Anzeige). Sobald der Puffer knapp
     * ist und schon lange nichts mehr kam, wird sofort eingegriffen.
     */
    fun observe(
        nowMs: Long,
        active: Boolean,
        totalBytes: Long,
        bufferedMs: Long = 0L,
        kickBelowMs: Long = Long.MAX_VALUE,
    ): Boolean {
        if (!active) {
            lastBytes = -1L
            return false
        }
        if (lastBytes < 0L || totalBytes < lastBytes || totalBytes - lastBytes >= minBytes) {
            if (lastBytes >= 0L) kicks = 0
            lastBytes = totalBytes
            dataAt = nowMs
            return false
        }
        if (bufferedMs > kickBelowMs) return false
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
        const val MIN_BYTES = 4_096L
    }
}
