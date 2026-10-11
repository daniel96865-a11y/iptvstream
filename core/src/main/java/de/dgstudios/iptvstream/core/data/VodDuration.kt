package de.dgstudios.iptvstream.core.data

import kotlin.math.abs
import kotlin.math.max

/**
 * Filmlängen aus Xtream-Feldern. Manche Anbieter schreiben Minuten in
 * `duration_secs` oder eine Uhrzeit ohne Sekunden (`01:33`). Beides soll
 * als echte Laufzeit ankommen, nicht als „1 Min.“.
 */
object VodDuration {
    fun minutes(durationSecs: String?, durationLabel: String?): Int {
        val label = durationLabel?.let(::parseMinutes) ?: 0
        val raw = durationSecs?.trim()?.toDoubleOrNull()?.toInt()
        if (raw == null || raw <= 0) return label
        val fromSeconds = raw / 60
        if (label > 0) {
            val slack = max(2, label / 10)
            if (abs(fromSeconds - label) <= slack) return fromSeconds.coerceAtLeast(1)
            if (abs(raw - label) <= slack) return label
            if (label >= 10 && fromSeconds <= 3) return label
            return if (fromSeconds > 0) fromSeconds else label
        }
        // Ohne Textfeld: ab 20 Minuten Rohwert sind es Sekunden, darunter oft schon Minuten.
        return if (raw >= 20 * 60) fromSeconds.coerceAtLeast(1) else raw
    }

    fun shown(catalogMin: Int, playedMs: Long): String? {
        val playedMin = if (playedMs >= 60_000L) (playedMs / 60_000L).toInt() else 0
        return formatRuntime(maxOf(catalogMin, playedMin)).ifBlank { null }
    }

    fun formatRuntime(minutes: Int): String {
        if (minutes <= 0) return ""
        val hours = minutes / 60
        val rest = minutes % 60
        return when {
            hours <= 0 -> "$rest Min."
            rest == 0 -> "$hours Std."
            else -> "$hours Std. $rest Min."
        }
    }

    fun parseMinutes(raw: String): Int {
        val text = raw.trim()
        if (text.isEmpty() || text == "0" || text.equals("null", true)) return 0
        val clock = text.split(":").map { it.trim() }
        if (clock.size >= 2 && clock.all { it.toIntOrNull() != null }) {
            val parts = clock.map { it.toInt() }
            return when (parts.size) {
                3 -> parts[0] * 60 + parts[1]
                2 -> if (parts[0] <= 4 && parts[1] < 60) parts[0] * 60 + parts[1] else parts[0]
                else -> parts[0]
            }
        }
        val hours = HOURS.find(text)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        val mins = MINUTES.find(text)?.groupValues?.get(1)?.toIntOrNull()
        if (hours > 0 || mins != null) return hours * 60 + (mins ?: 0)
        return text.toIntOrNull() ?: 0
    }

    private val HOURS = Regex("(\\d+)\\s*(?:h|std\\.?)", RegexOption.IGNORE_CASE)
    private val MINUTES = Regex("(\\d+)\\s*(?:m|min\\.?)", RegexOption.IGNORE_CASE)
}
