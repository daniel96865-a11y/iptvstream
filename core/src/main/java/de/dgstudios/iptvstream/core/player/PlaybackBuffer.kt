package de.dgstudios.iptvstream.core.player

/** Pufferstufen aus den Player-Einstellungen. Zeiten in Millisekunden. */
object PlaybackBuffer {
    const val SMALL_MAX_MS = 15_000
    const val MEDIUM_MAX_MS = 40_000
    const val LARGE_MAX_MS = 90_000

    /**
     * min, max, Zielgröße in Bytes.
     * Die Byte-Grenze liegt über der Zeitgrenze, sonst stoppt ein datenreicher Stream
     * (zum Beispiel 4K) lange vor den 90 Sekunden.
     */
    fun spec(setting: String): Triple<Int, Int, Int> = when (setting) {
        "small" -> Triple(5_000, SMALL_MAX_MS, 80 * 1024 * 1024)
        "large" -> Triple(15_000, LARGE_MAX_MS, 384 * 1024 * 1024)
        else -> Triple(8_000, MEDIUM_MAX_MS, 192 * 1024 * 1024)
    }

    /**
     * Anteil an der vollen Breite. Groß füllt die Zeile, klein bleibt kürzer,
     * aber breit genug für eine Zeile Text.
     */
    /**
     * Der Puffer liegt im Java-Heap der App. Auf Geräten mit kleinem Heap (ältere Fire TVs)
     * darf er nur einen Teil davon belegen, sonst droht Speichermangel mitten im Sender.
     */
    fun capBytes(wanted: Int, memoryClassMb: Int): Int {
        if (memoryClassMb <= 0) return wanted
        val limit = (memoryClassMb.toLong() * 1024 * 1024 * 3 / 10).coerceAtLeast(MIN_BYTES.toLong())
        return minOf(wanted.toLong(), limit).toInt()
    }

    private const val MIN_BYTES = 24 * 1024 * 1024

    fun trackFraction(maxMs: Long): Float = when {
        maxMs <= 0L -> 0f
        maxMs <= SMALL_MAX_MS -> 0.5f
        maxMs <= MEDIUM_MAX_MS -> 0.75f
        else -> 1f
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
