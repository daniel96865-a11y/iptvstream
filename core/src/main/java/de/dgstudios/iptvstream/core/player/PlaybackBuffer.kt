package de.dgstudios.iptvstream.core.player

/** Pufferstufen aus den Player-Einstellungen. Zeiten in Millisekunden. */
object PlaybackBuffer {
    const val SMALL_MAX_MS = 15_000
    const val MEDIUM_MAX_MS = 40_000
    const val LARGE_MAX_MS = 90_000

    /** min, max, Zielgröße in Bytes. Entspricht der bisherigen LoadControl. */
    fun spec(setting: String): Triple<Int, Int, Int> = when (setting) {
        "small" -> Triple(5_000, SMALL_MAX_MS, 40 * 1024 * 1024)
        "large" -> Triple(30_000, LARGE_MAX_MS, 96 * 1024 * 1024)
        else -> Triple(15_000, MEDIUM_MAX_MS, 64 * 1024 * 1024)
    }

    /** Anteil an der vollen Breite. Groß füllt die Zeile, klein bleibt kürzer. */
    fun trackFraction(maxMs: Long): Float {
        if (maxMs <= 0L) return 0f
        return (maxMs.toFloat() / LARGE_MAX_MS).coerceIn(0f, 1f)
    }

    fun fill(bufferedMs: Long, maxMs: Long): Float {
        if (maxMs <= 0L) return 0f
        return (bufferedMs.coerceAtLeast(0L).toFloat() / maxMs).coerceIn(0f, 1f)
    }

    fun label(bufferedMs: Long, maxMs: Long): String {
        val cap = seconds(maxMs)
        val have = seconds(bufferedMs).coerceAtMost(cap)
        return "$have / $cap s"
    }

    private fun seconds(ms: Long): Long = (ms.coerceAtLeast(0L) + 500L) / 1000L
}
