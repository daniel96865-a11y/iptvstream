package de.dgstudios.iptvstream.core.player

/**
 * Baut bei Live-Sendern Vorlauf auf, ohne das Umschalten zu verlangsamen.
 *
 * Ein Live-Strom kommt nur in Echtzeit an. Startet das Bild nach 1 s Vorlauf, bleiben es 1 s –
 * jede Netzschwankung, die länger dauert, leert den Puffer. Spielt der Player etwas langsamer als
 * Echtzeit, wächst der Vorlauf stetig: bei 93 % um gut 4 s pro Minute, bei 97 % um knapp 2 s.
 * Je leerer der Puffer, desto stärker wird gebremst (unter 3 s: 93 %). Nie schneller als normal.
 * Ist das Ziel erreicht, läuft alles wieder mit normalem Tempo. Die Tonhöhe bleibt dabei gleich.
 *
 * Nach jedem Hänger wächst das Ziel ([stepMs]) bis [capMs]: Ein wackliger Sender bekommt mehr Polster.
 * Reine Logik ohne Android-Klassen, damit sie testbar bleibt.
 */
class LiveCushion(
    private val baseTargetMs: Long,
    private val stepMs: Long,
    private val capMs: Long,
    private val lowMs: Long = LOW_MS,
) {
    /** Hänger auf dem laufenden Sender (Puffer leer gelaufen). */
    var rebuffers = 0
        private set

    val targetMs: Long get() = (baseTargetMs + rebuffers * stepMs).coerceAtMost(capMs.coerceAtLeast(baseTargetMs))

    fun onRebuffer() {
        rebuffers++
    }

    /** Neuer Sender: Ziel zurück auf den Grundwert. */
    fun reset() {
        rebuffers = 0
    }

    /**
     * Tempo für den aktuellen Vorlauf. [current] ist das zuletzt gesetzte Tempo; im Bereich zwischen
     * Ziel und Ziel minus Spanne bleibt es dabei, damit das Tempo nicht ständig wechselt.
     */
    fun speedFor(bufferedMs: Long, current: Float): Float {
        val target = targetMs
        val band = (target / 4).coerceAtMost(MAX_BAND_MS)
        return when {
            bufferedMs >= target -> NORMAL
            bufferedMs < lowMs -> STRONG
            current == STRONG && bufferedMs < lowMs + LOW_BAND_MS -> STRONG
            bufferedMs < target - band -> GENTLE
            current == NORMAL -> NORMAL
            else -> GENTLE
        }
    }

    companion object {
        const val NORMAL = 1f
        /** Kaum hörbar, für den normalen Aufbau. */
        const val GENTLE = 0.97f
        /** Unter [LOW_MS] Vorlauf: schneller aus der Gefahrenzone. */
        const val STRONG = 0.93f
        const val LOW_MS = 3_000L
        private const val LOW_BAND_MS = 1_000L
        private const val MAX_BAND_MS = 2_000L
    }
}
