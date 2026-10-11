package de.dgstudios.iptvstream.core.data

import de.dgstudios.iptvstream.core.data.db.EpgEntity

/**
 * Baut „Jetzt/Danach“ aus bereits geladenen Zeilen und sagt, wie lange das Ergebnis gilt.
 * Solange die laufende Sendung nicht zu Ende ist, muss die Liste nicht neu aus der Datenbank.
 */
object EpgGuide {
    const val EMPTY_TTL_MS = 60_000L

    fun fromRows(list: List<EpgEntity>, now: Long): NowNext {
        val cur = list.firstOrNull()?.takeIf { it.start <= now }
        val next = if (cur != null) list.getOrNull(1) else list.firstOrNull()
        return NowNext(cur, next)
    }

    /** [rows] sind nach Start sortiert und enthalten nur Sendungen mit Ende nach [now]. */
    fun group(rows: List<EpgEntity>, keys: Collection<String>, now: Long): Map<String, NowNext> {
        val byKey = rows.groupBy { it.channelKey }
        val out = HashMap<String, NowNext>(keys.size)
        for (key in keys) {
            out[key] = fromRows(byKey[key].orEmpty(), now)
        }
        return out
    }

    fun freshUntil(value: NowNext, now: Long): Long {
        val stop = value.now?.stop
        if (stop != null && stop > now) return stop
        val start = value.next?.start
        if (start != null && start > now) return start
        return now + EMPTY_TTL_MS
    }
}
