package de.dgstudios.iptvstream.core.player

import de.dgstudios.iptvstream.core.player.LiveTsJoin.Companion.ptsAhead
import de.dgstudios.iptvstream.core.player.LiveTsJoin.Companion.ptsMs

/**
 * Findet Sprünge in der Ton-PTS (Lücken oder Rücksprünge) über einen Anschluss hinweg.
 *
 * Der Abstand zwischen zwei Ton-PES kann je nach Codec schon 30–400 ms betragen
 * (AC-3-Rahmen vs. große MPEG-Audio-Pakete). Eine Ton-Lücke gilt deshalb erst,
 * wenn sie um mehr als [MAX_OK_MS] über dem typischen Abstand der Spur liegt,
 * oder wenn die PTS zurückspringt.
 */
object AudioGapDetector {
    const val MAX_OK_MS = 200L

    /**
     * Größte Lücke bzw. größter Rücksprung in Millisekunden über die gelieferte Ton-PTS,
     * oder 0 wenn nichts da ist. Enthält auch den normalen Rahmenabstand.
     */
    fun largestGapMs(delivered: List<Pair<Int, Long>>, audioPids: Set<Int>): Long {
        var worst = 0L
        forEachDelta(delivered, audioPids) { gap, _ -> if (gap > worst) worst = gap }
        return worst
    }

    /**
     * Typischer (Median) Abstand der Ton-PTS, ohne die größten Ausreißer.
     * 0, wenn zu wenig Werte vorliegen.
     */
    fun typicalIntervalMs(delivered: List<Pair<Int, Long>>, audioPids: Set<Int>): Long {
        val deltas = ArrayList<Long>()
        forEachDelta(delivered, audioPids) { gap, rewind -> if (!rewind) deltas += gap }
        if (deltas.isEmpty()) return 0L
        deltas.sort()
        val drop = (deltas.size / 10).coerceAtLeast(1).coerceAtMost(deltas.size - 1)
        val kept = if (deltas.size == 1) deltas else deltas.subList(0, deltas.size - drop)
        val mid = kept.size / 2
        return if (kept.size % 2 == 0 && kept.size >= 2) {
            (kept[mid - 1] + kept[mid]) / 2
        } else {
            kept[mid]
        }
    }

    fun hasGap(delivered: List<Pair<Int, Long>>, audioPids: Set<Int>, maxOkMs: Long = MAX_OK_MS): Boolean {
        val typical = typicalIntervalMs(delivered, audioPids)
        val limit = typical + maxOkMs
        var found = false
        forEachDelta(delivered, audioPids) { gap, rewind ->
            if (rewind && gap > maxOkMs) found = true
            if (!rewind && gap > limit) found = true
        }
        return found
    }

    /** Typische MPEG-Audio- bzw. AC-3-PID bleibt unbekannt: alle nicht-Video-PIDs (Video oft 0x100). */
    fun audioPidsFrom(delivered: List<Pair<Int, Long>>, videoPids: Set<Int>): Set<Int> =
        delivered.map { it.first }.toSet() - videoPids

    private inline fun forEachDelta(
        delivered: List<Pair<Int, Long>>,
        audioPids: Set<Int>,
        block: (gap: Long, rewind: Boolean) -> Unit,
    ) {
        if (audioPids.isEmpty()) return
        val last = HashMap<Int, Long>()
        for ((pid, pts) in delivered) {
            if (pid !in audioPids) continue
            val prev = last[pid]
            if (prev != null) {
                val aheadMs = ptsMs(ptsAhead(pts, prev))
                if (aheadMs < 0) block(-aheadMs, true) else block(aheadMs, false)
            }
            last[pid] = pts
        }
    }
}
