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

    /**
     * Live: so viel Vorlauf muss da sein, bevor das Bild startet: (Senderwechsel, nach einem Hänger).
     * Beim Umschalten zählt Tempo: Start nach 0,3–0,8 s Vorlauf (wie 1.0.79-zap1). Ein Live-TS-Strom kommt aber nur in
     * Echtzeit an – der Vorlauf wächst danach nicht von selbst. Deshalb baut [LiveCushion] ihn durch
     * kaum merklich langsamere Wiedergabe auf. Nach einem Hänger startet der Sender erst mit dem
     * zweiten Wert wieder ([LiveAwareLoadControl]).
     */
    fun liveStart(setting: String): Pair<Int, Int> = when (setting) {
        "small" -> ZAP_SMALL_MS to 2_500
        "large" -> ZAP_LARGE_MS to 6_000
        else -> ZAP_MEDIUM_MS to 4_000
    }

    const val ZAP_SMALL_MS = 300
    const val ZAP_MEDIUM_MS = 500
    const val ZAP_LARGE_MS = 800

    /**
     * Live: so weit darf der Puffer höchstens wachsen. Unabhängig von „Klein“ mindestens 30 s, damit
     * ein Anbieter, der beim Verbinden einen Schwung Daten schickt, voll aufgenommen wird. Den Speicher
     * begrenzt weiterhin die Byte-Grenze ([capBytes]).
     */
    fun liveMaxMs(setting: String): Int = when (setting) {
        "small" -> 30_000
        "large" -> LARGE_MAX_MS
        else -> 45_000
    }

    /** Ziel-Vorlauf für [LiveCushion]: Grundwert, Zuschlag je Hänger, Obergrenze. */
    fun cushion(setting: String): Triple<Long, Long, Long> = when (setting) {
        "small" -> Triple(6_000L, 4_000L, 15_000L)
        "large" -> Triple(15_000L, 5_000L, 40_000L)
        else -> Triple(10_000L, 5_000L, 25_000L)
    }
}
