package de.dgstudios.iptvstream.core.data

/** Ein Bereich der Xtream-Liste. M3U ist ein einzelner Abruf und gehört nicht hierher. */
internal enum class SyncSection {
    LIVE,
    MOVIE,
    SERIES,
}

/** null bei [sections] heißt: alle Bereiche. */
internal data class EpgRequest(
    val profileId: Long,
    val force: Boolean,
    val maxAgeMs: Long,
)

internal data class SyncRequest(
    val profileId: Long,
    val sections: Set<SyncSection>?,
)

internal data class SectionFailure(
    val sections: Set<SyncSection>,
    val cause: String?,
)

/**
 * Welche Bereiche nach einem unvollständigen Abruf noch fehlen, und der Text dazu.
 * Die gemerkte Zeichenkette liegt in den Einstellungen, damit ein Neustart dieselben Bereiche nachzieht.
 */
internal object SyncPlan {
    val ALL: Set<SyncSection> = setOf(SyncSection.LIVE, SyncSection.MOVIE, SyncSection.SERIES)

    fun merge(queued: SyncRequest, incoming: SyncRequest): SyncRequest {
        val a = queued.sections
        val b = incoming.sections
        val sections = if (a == null || b == null) null else a + b
        return queued.copy(sections = sections)
    }

    fun parse(raw: String?): Set<SyncSection> {
        if (raw.isNullOrBlank()) return emptySet()
        return raw.split(',').mapNotNull { token ->
            when (token.trim()) {
                "live" -> SyncSection.LIVE
                "movie" -> SyncSection.MOVIE
                "series" -> SyncSection.SERIES
                else -> null
            }
        }.toSet()
    }

    fun format(sections: Set<SyncSection>): String =
        listOf(SyncSection.LIVE, SyncSection.MOVIE, SyncSection.SERIES)
            .filter { it in sections }
            .joinToString(",") { keyOf(it) }

    fun message(failed: Set<SyncSection>, cause: String?): String {
        val ordered = listOf(SyncSection.LIVE, SyncSection.MOVIE, SyncSection.SERIES)
        val bad = ordered.filter { it in failed }
        val good = ordered.filter { it !in failed }
        val why = cause?.takeIf { it.isNotBlank() }?.let { " ($it)" }.orEmpty()
        val badText = join(bad.map(::label))
        val verb = if (bad.size == 1 && bad[0] == SyncSection.LIVE) "konnte" else "konnten"
        val fail = "$badText $verb nicht geladen werden$why und werden erneut geladen."
        if (good.isEmpty()) return fail
        val goodText = join(good.map(::label))
        val sind = if (good.size == 1 && good[0] == SyncSection.LIVE) "ist" else "sind"
        return "$goodText $sind aktuell. $fail"
    }

    private fun keyOf(section: SyncSection): String = when (section) {
        SyncSection.LIVE -> "live"
        SyncSection.MOVIE -> "movie"
        SyncSection.SERIES -> "series"
    }

    private fun label(section: SyncSection): String = when (section) {
        SyncSection.LIVE -> "Live-TV"
        SyncSection.MOVIE -> "Filme"
        SyncSection.SERIES -> "Serien"
    }

    private fun join(names: List<String>): String = when (names.size) {
        0 -> ""
        1 -> names[0]
        2 -> "${names[0]} und ${names[1]}"
        else -> names.dropLast(1).joinToString(", ") + " und " + names.last()
    }
}

/** Negative Profil-ID. Die Zeilen bleiben unsichtbar, bis der Import vollständig ist. */
internal object EpgStaging {
    fun profileId(realId: Long): Long = -realId
}

internal object EpgRefreshInterval {
    const val STALE_MS = 6 * 3_600_000L
    const val WATCH_MS = 15 * 60 * 1000L
}
