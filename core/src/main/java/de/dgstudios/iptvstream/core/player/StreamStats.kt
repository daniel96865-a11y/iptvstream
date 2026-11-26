package de.dgstudios.iptvstream.core.player

import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Stream-Infos für die Einblendung im Player. Alles stammt aus dem laufenden Stream bzw. wird
 * gemessen; fehlende Werte bleiben null.
 */
data class StreamInfo(
    val resolution: String? = null,
    val fps: Float? = null,
    /** Vom Stream gemeldete Video-Bitrate (kbit/s), bei MPEG-TS meist unbekannt. */
    val videoKbps: Int? = null,
    /** Gemessene Datenrate der Verbindung (kbit/s), über die letzten Sekunden. */
    val networkKbps: Int? = null,
    val videoDecoder: String? = null,
    val audioDecoder: String? = null,
    /** Zeit vom Senderwechsel bis zum ersten Bild (ms), für den laufenden Sender. */
    val firstFrameMs: Long? = null,
    /** Median der letzten Umschaltzeiten in dieser Sitzung. */
    val medianFirstFrameMs: Long? = null,
    val droppedFrames: Int = 0,
    val bufferedMs: Long = 0,
    /** Live: Ziel-Vorlauf, auf den der Player gerade hinarbeitet. */
    val bufferTargetMs: Long? = null,
    /** Live: Höchstpuffer. */
    val bufferMaxMs: Long? = null,
    /** Wie oft der Puffer auf diesem Sender leer gelaufen ist. */
    val rebuffers: Int = 0,
    /** Neuverbindungen auf diesem Sender. */
    val reconnects: Int = 0,
    /** Wiedergabetempo (1 = normal; darunter wird Vorlauf aufgebaut). */
    val speed: Float = 1f,
) {
    /** Zeilen für die Einblendung, kurz und ohne leere Werte. */
    fun lines(): List<String> = buildList {
        val video = listOfNotNull(resolution, fps?.let { StreamStats.fpsLabel(it) }).joinToString(" · ")
        if (video.isNotEmpty()) add("Bild: $video")
        val rates = listOfNotNull(
            videoKbps?.let { "Video ${StreamStats.kbpsLabel(it)}" },
            networkKbps?.let { "Netz ${StreamStats.kbpsLabel(it)}" },
        ).joinToString(" · ")
        if (rates.isNotEmpty()) add("Bitrate: $rates")
        videoDecoder?.let { add("Video-Decoder: $it") }
        audioDecoder?.let { add("Ton-Decoder: $it") }
        val zap = listOfNotNull(
            firstFrameMs?.let { "${it} ms" },
            medianFirstFrameMs?.let { "Median ${it} ms" },
        ).joinToString(" · ")
        if (zap.isNotEmpty()) add("Umschaltzeit: $zap")
        add(bufferLine())
        add("Puffer leer: ${rebuffers}× · Neu verbunden: ${reconnects}× · verworfene Bilder: $droppedFrames")
        if (speed < 1f) add("Tempo ${(speed * 100 + 0.5f).toInt()} % – Vorlauf wird aufgebaut")
    }

    private fun bufferLine(): String {
        val parts = mutableListOf("Puffer: ${StreamStats.secondsLabel(bufferedMs)}")
        bufferTargetMs?.let { parts += "Ziel ${StreamStats.secondsLabel(it)}" }
        bufferMaxMs?.let { parts += "max. ${StreamStats.secondsLabel(it)}" }
        return parts.joinToString(" · ")
    }
}

object StreamStats {
    /** Übliche Bildraten. Gemessene Werte werden auf die nächste davon gerundet. */
    private val COMMON_FPS = floatArrayOf(23.976f, 24f, 25f, 29.97f, 30f, 48f, 50f, 59.94f, 60f)

    /** Rundet eine gemessene Bildrate auf eine übliche; null, wenn nichts passt (±6 %). */
    fun normalizeFps(measured: Float): Float? {
        if (measured.isNaN() || measured <= 1f) return null
        val best = COMMON_FPS.minByOrNull { abs(it - measured) } ?: return null
        return if (abs(best - measured) / best <= 0.06f) best else null
    }

    fun fpsLabel(fps: Float): String =
        if (abs(fps - fps.roundToInt()) < 0.01f) "${fps.roundToInt()} fps" else String.format(Locale.GERMANY, "%.2f fps", fps)

    fun kbpsLabel(kbps: Int): String =
        if (kbps >= 1000) String.format(Locale.GERMANY, "%.1f Mbit/s", kbps / 1000f) else "$kbps kbit/s"

    /** "7,4 s" (eine Nachkommastelle, deutsches Komma). */
    fun secondsLabel(ms: Long): String =
        String.format(Locale.GERMANY, "%.1f s", ms.coerceAtLeast(0L) / 1000.0)

    /** Datenrate aus übertragenen Bytes und Zeit. */
    fun kbps(bytes: Long, millis: Long): Int? {
        if (millis <= 0 || bytes < 0) return null
        return (bytes * 8 / millis).toInt()
    }

    /** Median, null bei leerer Liste. */
    fun median(values: List<Long>): Long? {
        if (values.isEmpty()) return null
        val s = values.sorted()
        val m = s.size / 2
        return if (s.size % 2 == 1) s[m] else (s[m - 1] + s[m]) / 2
    }

    /**
     * Bildschirmmodus für „Bildwiederholrate immer anpassen“: unter den Modi mit gleicher Auflösung
     * den, dessen Rate ein ganzzahliges Vielfaches der Bildrate ist (50 Hz für 25 fps, 59,94 für 29,97 …).
     * Bevorzugt die genaueste, bei Gleichstand die niedrigste Rate. Null, wenn keiner passt.
     */
    fun pickRefreshRate(fps: Float, available: List<Float>): Float? {
        if (fps <= 1f) return null
        fun error(rate: Float): Float {
            val ratio = rate / fps
            val n = ratio.roundToInt()
            return if (n >= 1) abs(ratio - n) / n else Float.MAX_VALUE
        }
        return available
            .filter { error(it) < 0.01f }
            .sortedWith(compareBy<Float>({ error(it) }, { it }))
            .firstOrNull()
    }
}
