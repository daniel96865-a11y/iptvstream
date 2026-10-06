package de.dgstudios.iptvstream.core.util

import java.text.Normalizer

/** Zeit- und Namenslogik für XMLTV. Keine Android-Abhängigkeiten. */
object XmltvTime {
    /** Parst "20260101120000 +0200" (Sekunden und Offset optional) nach Epoch-Millisekunden. */
    fun parse(raw: String?): Long? {
        val t = raw?.trim() ?: return null
        if (t.length < 12) return null
        return try {
            val y = t.substring(0, 4).toInt()
            val mo = t.substring(4, 6).toInt()
            val d = t.substring(6, 8).toInt()
            val h = t.substring(8, 10).toInt()
            val mi = t.substring(10, 12).toInt()
            val hasSec = t.length >= 14 && t[12].isDigit() && t[13].isDigit()
            val sec = if (hasSec) t.substring(12, 14).toInt() else 0
            val rest = t.substring(if (hasSec) 14 else 12).trim()
            var offsetMin = 0
            if (rest.isNotEmpty() && (rest[0] == '+' || rest[0] == '-')) {
                val sign = if (rest[0] == '-') -1 else 1
                val digits = rest.substring(1).filter { it.isDigit() }
                if (digits.length >= 4) {
                    offsetMin = sign * (digits.substring(0, 2).toInt() * 60 + digits.substring(2, 4).toInt())
                }
            }
            val days = daysFromCivil(y, mo, d)
            ((days * 86_400L + h * 3_600L + mi * 60L + sec) - offsetMin * 60L) * 1000L
        } catch (e: NumberFormatException) {
            null
        }
    }

    private fun daysFromCivil(y0: Int, m: Int, d: Int): Long {
        val y = if (m <= 2) y0 - 1 else y0
        val era = (if (y >= 0) y else y - 399) / 400
        val yoe = y - era * 400
        val mp = if (m > 2) m - 3 else m + 9
        val doy = (153 * mp + 2) / 5 + d - 1
        val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
        return era.toLong() * 146_097L + doe - 719_468L
    }
}

object EpgMatcher {
    private val countries =
        "de|at|ch|pl|uk|us|fr|it|es|tr|nl|ru|pt|gr|dk|se|no|fi|cz|sk|hu|ro|bg|hr|rs|si|al|ex|ar|ir"
    private val prefixRegex = Regex("""^\s*(\[(?:$countries)]|\|(?:$countries)\||(?:$countries)\s*[:|]\s*)\s*""")
    private val suffixRegex = Regex("""\.(?:$countries)$""")
    private val noiseRegex = Regex("""\b(uhd|fhd|hd|sd|4k|8k|hevc|h265|h264|raw|50fps|60fps|1080p|1080i|720p|backup|vip|premium)\b""")
    private val diacritics = Regex("\\p{M}+")
    private val nonAlnum = Regex("[^a-z0-9]")

    /** Vereinheitlicht Sender- und EPG-Namen (HD/FHD/4K, Ländervorsätze, Sonderzeichen entfernt). */
    fun normalize(raw: String): String {
        var s = Normalizer.normalize(raw.lowercase(), Normalizer.Form.NFD).replace(diacritics, "")
        s = s.replace("&", "and").replace("+", "plus")
        s = prefixRegex.replace(s, "")
        s = suffixRegex.replace(s.trim(), "")
        s = noiseRegex.replace(s, " ")
        return nonAlnum.replace(s, "")
    }

    /**
     * Sucht zu einem Sender den passenden EPG-Schlüssel.
     * [byNorm] bildet normalisierte IDs/Namen auf vorhandene EPG-Schlüssel ab.
     */
    fun match(epgKey: String, channelName: String, byNorm: Map<String, String>): String? {
        val candidates = ArrayList<String>(3)
        if (epgKey.isNotBlank()) candidates.add(normalize(epgKey))
        candidates.add(normalize(channelName))
        for (c in candidates) {
            if (c.isNotEmpty()) byNorm[c]?.let { return it }
        }
        return null
    }
}
