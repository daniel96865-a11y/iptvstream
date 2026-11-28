package de.dgstudios.iptvstream.core.player

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Gemeinsamer Stand für Live-TS-Anschlüsse: merkt je Spur (PID) die letzte PTS der laufenden
 * Verbindung und sagt der nächsten, bis wohin schon Gesehenes verworfen werden soll.
 *
 * Viele Anbieter schicken bei einer neuen Verbindung ein paar Sekunden Vorlauf, der zeitlich
 * vor dem Ende der alten Verbindung liegt. Ohne Überspringen würde der Player zurückspringen,
 * und der Vorlauf würde fälschlich im Puffer mitzählen.
 */
class LiveTsJoin {
    private val lastPtsByPid = ConcurrentHashMap<Int, Long>()
    private val skipUntilByPid = ConcurrentHashMap<Int, Long>()
    private val outcome = AtomicInteger(NONE)
    private val deliveredPts = CopyOnWriteArrayList<Pair<Int, Long>>()
    @Volatile private var sameStream = false
    private val gapPts = AtomicLong(0)

    /** Höchste letzte PTS über alle Spuren, oder [UNSET]. */
    fun lastPts(): Long = lastPtsByPid.values.maxOrNull() ?: UNSET

    fun lastPts(pid: Int): Long = lastPtsByPid[pid] ?: UNSET

    /**
     * Neue Verbindung: je Spur bis zur letzten PTS der alten überspringen, falls eine bekannt ist.
     * [sameStream] = derselbe Extraktor und Decoder bleiben (nahtlose Neuöffnung): dann nicht bis
     * zum nächsten Schlüsselbild warten, sonst entsteht eine Lücke von bis zu einer GOP-Länge.
     */
    fun arm(sameStream: Boolean = false) {
        this.sameStream = sameStream
        gapPts.set(0)
        skipUntilByPid.clear()
        if (lastPtsByPid.isEmpty()) {
            outcome.set(NONE)
        } else {
            skipUntilByPid.putAll(lastPtsByPid)
            outcome.set(ARMED)
        }
    }

    /** Anschluss im laufenden Strom (kein neuer Decoder). */
    fun sameStream(): Boolean = sameStream

    /** Ziel-PTS je Spur für diese Öffnung (einmalig). Leer, wenn nichts übersprungen werden soll. */
    fun takeSkipUntil(): Map<Int, Long> {
        val snap = HashMap(skipUntilByPid)
        skipUntilByPid.clear()
        return snap
    }

    /**
     * Nur tatsächlich an den Player abgegebene PTS merken – verworfener Vorlauf oder
     * zurückgehaltene Pakete bis zum Schlüsselbild dürfen das nächste Ziel nicht nach vorn schieben.
     */
    fun onDelivered(pid: Int, pts: Long) {
        val prev = lastPtsByPid[pid]
        if (prev == null || ptsAhead(pts, prev) >= 0) lastPtsByPid[pid] = pts
        deliveredPts.add(pid to pts)
    }

    /** An den Player abgegebene Medien-PTS in Lesereihenfolge (für Tests: kein Rücksprung). */
    fun delivered(): List<Pair<Int, Long>> = deliveredPts

    fun markJoined(gapPts: Long = 0L) {
        this.gapPts.set(gapPts.coerceAtLeast(0L))
        outcome.set(JOINED)
    }

    /** Wie weit der Anschluss hinter dem Ziel liegt (Warten aufs Schlüsselbild), in Millisekunden. */
    fun keyframeGapMs(): Long = ptsMs(gapPts.get())


    fun markMismatch() { outcome.set(MISMATCH) }

    /** Die neue Verbindung beginnt hinter oder genau am Ende der alten (Überlappung verworfen oder Lücke). */
    fun joined(): Boolean = outcome.get() == JOINED

    /** Zeitstempel passen nicht zusammen: an der Live-Kante weiterspielen, nicht am Anfang der neuen Verbindung. */
    fun mismatch(): Boolean = outcome.get() == MISMATCH

    /**
     * Es sollte übersprungen werden, ist aber nicht gelungen (MISMATCH oder noch ARMED).
     * Auf Live-TS darf der Player deshalb nicht seeken: Media3 behandelt das oft als Sprung auf 0
     * und verwirft den Puffer. Stattdessen das neue Stück einfach abspielen (eigene Zeitachse)
     * bzw. bei nahtlosem Anschluss im selben Extraktor den Strom beenden und ein neues Stück anhängen.
     */
    fun shouldSeekToLiveEdge(): Boolean {
        val o = outcome.get()
        return o == MISMATCH || o == ARMED
    }

    /**
     * Test: tut so, als läge die letzte geladene PTS aller Spuren weit neben der nächsten Datei
     * (Anbieter-Neustart / andere Zeitachse).
     */
    fun shiftLastPts(delta: Long) {
        lastPtsByPid.replaceAll { _, v -> (v + delta) and PTS_MASK }
    }

    fun reset() {
        lastPtsByPid.clear()
        skipUntilByPid.clear()
        deliveredPts.clear()
        outcome.set(NONE)
        sameStream = false
        gapPts.set(0)
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
