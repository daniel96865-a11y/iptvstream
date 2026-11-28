package de.dgstudios.iptvstream.core.player

import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Gemeinsamer Stand für Live-TS-Anschlüsse: merkt die letzte PTS der laufenden Verbindung
 * und sagt der nächsten, bis wohin schon Gesehenes verworfen werden soll.
 *
 * Viele Anbieter schicken bei einer neuen Verbindung ein paar Sekunden Vorlauf, der zeitlich
 * vor dem Ende der alten Verbindung liegt. Ohne Überspringen würde der Player zurückspringen.
 */
class LiveTsJoin {
    private val lastPts = AtomicLong(UNSET)
    private val skipUntil = AtomicLong(UNSET)
    private val outcome = AtomicInteger(NONE)

    /** Letzte gesehene Medien-PTS (90 kHz) oder [UNSET]. */
    fun lastPts(): Long = lastPts.get()

    /** Neue Verbindung: bis zur letzten PTS der alten überspringen, falls eine bekannt ist. */
    fun arm() {
        val last = lastPts.get()
        if (last == UNSET) {
            skipUntil.set(UNSET)
            outcome.set(NONE)
        } else {
            skipUntil.set(last)
            outcome.set(ARMED)
        }
    }

    /**
     * Ziel-PTS für diese Öffnung (einmalig). [UNSET], wenn nichts übersprungen werden soll.
     */
    fun takeSkipUntil(): Long {
        val v = skipUntil.getAndSet(UNSET)
        return v
    }

    fun onPts(pts: Long) {
        lastPts.set(pts)
    }

    fun markJoined() { outcome.set(JOINED) }

    fun markMismatch() { outcome.set(MISMATCH) }

    /** Die neue Verbindung beginnt hinter oder genau am Ende der alten (Überlappung verworfen oder Lücke). */
    fun joined(): Boolean = outcome.get() == JOINED

    /** Zeitstempel passen nicht zusammen: an der Live-Kante weiterspielen, nicht am Anfang der neuen Verbindung. */
    fun mismatch(): Boolean = outcome.get() == MISMATCH

    /** Es sollte übersprungen werden, ist aber nicht gelungen. */
    fun shouldSeekToLiveEdge(): Boolean {
        val o = outcome.get()
        return o == MISMATCH || o == ARMED
    }

    fun reset() {
        lastPts.set(UNSET)
        skipUntil.set(UNSET)
        outcome.set(NONE)
    }

    companion object {
        const val UNSET = Long.MIN_VALUE
        const val NONE = 0
        const val ARMED = 1
        const val JOINED = 2
        const val MISMATCH = 3

        /** 33-Bit-PTS, 90 kHz. */
        const val PTS_MASK = 0x1FFFFFFFFL
        const val PTS_HZ = 90_000L

        /** Größte Überlappung, die noch als Vorlauf gilt (nicht als andere Sendung / neuer Zeitstempel). */
        const val MAX_OVERLAP_PTS = 20L * PTS_HZ

        /** Nach dem Ziel noch so lange auf ein Schlüsselbild warten. */
        const val KEYFRAME_WAIT_PTS = 2_500L * PTS_HZ / 1000L

        /**
         * Wie weit [a] vor [b] liegt (90 kHz), mit Umschlag alle ~26,5 h.
         * Positiv = a ist später.
         */
        fun ptsAhead(a: Long, b: Long): Long {
            var d = (a - b) and PTS_MASK
            if (d and (1L shl 32) != 0L) d -= 1L shl 33
            return d
        }

        fun ptsMs(pts: Long): Long = (pts * 1_000L) / PTS_HZ
    }
}
