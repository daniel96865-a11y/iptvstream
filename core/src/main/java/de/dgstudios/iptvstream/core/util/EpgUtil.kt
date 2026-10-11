package de.dgstudios.iptvstream.core.util

import java.text.Normalizer
import java.util.Calendar
import java.util.TimeZone

/** Zeit- und Namenslogik für XMLTV. Keine Android-Abhängigkeiten. */
object XmltvTime {
    /**
     * Zeitzone für XMLTV-Zeitstempel ohne Offset.
     * Europe/Berlin (CET/CEST), damit deutsche Programmlisten nicht 1–2 Stunden nach UTC verrutschen.
     * Explizite Offsets wie `+0100` bleiben unverändert.
     */
    private val defaultZone: TimeZone = TimeZone.getTimeZone("Europe/Berlin")

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
            if (rest.isNotEmpty() && (rest[0] == '+' || rest[0] == '-')) {
                val sign = if (rest[0] == '-') -1 else 1
                val digits = rest.substring(1).filter { it.isDigit() }
                val offsetMin = if (digits.length >= 4) {
                    sign * (digits.substring(0, 2).toInt() * 60 + digits.substring(2, 4).toInt())
                } else {
                    0
                }
                val days = daysFromCivil(y, mo, d)
                ((days * 86_400L + h * 3_600L + mi * 60L + sec) - offsetMin * 60L) * 1000L
            } else {
                berlinMillis(y, mo, d, h, mi, sec)
            }
        } catch (e: NumberFormatException) {
            null
        }
    }

    private fun berlinMillis(y: Int, mo: Int, d: Int, h: Int, mi: Int, sec: Int): Long {
        val cal = Calendar.getInstance(defaultZone)
        cal.clear()
        cal.set(Calendar.YEAR, y)
        cal.set(Calendar.MONTH, mo - 1)
        cal.set(Calendar.DAY_OF_MONTH, d)
        cal.set(Calendar.HOUR_OF_DAY, h)
        cal.set(Calendar.MINUTE, mi)
        cal.set(Calendar.SECOND, sec)
        cal.set(Calendar.MILLISECOND, 0)
        return cal.timeInMillis
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

    /** Ländercode aus Vorsatz (`[DE]`, `DE:`, `|DE|`) oder Endung (`.de`), sonst null. */
    fun countryOf(raw: String): String? {
        val s = raw.lowercase().trim()
        prefixRegex.find(s)?.let { m ->
            val code = m.value.filter { it.isLetter() }
            if (code.isNotEmpty()) return code
        }
        return suffixRegex.find(s)?.value?.removePrefix(".")
    }

    /**
     * Wählt aus mehreren Schlüsseln mit gleichem Namen den zum Land passenden.
     * Ist das Land des Senders bekannt, kommt nie ein Schlüssel eines anderen Landes in Frage.
     */
    fun choose(keys: List<String>?, country: String?): String? {
        if (keys.isNullOrEmpty()) return null
        if (country != null) {
            return keys.firstOrNull { countryOf(it) == country } ?: keys.firstOrNull { countryOf(it) == null }
        }
        return keys.firstOrNull { countryOf(it) == null } ?: keys.first()
    }

    /**
     * Sucht zu einem Sender den passenden EPG-Schlüssel.
     * [byNorm] bildet normalisierte IDs/Namen auf vorhandene EPG-Schlüssel ab.
     */
    fun match(epgKey: String, channelName: String, byNorm: Map<String, List<String>>): String? {
        val country = countryOf(channelName) ?: countryOf(epgKey)
        val candidates = ArrayList<String>(2)
        if (epgKey.isNotBlank()) candidates.add(normalize(epgKey))
        candidates.add(normalize(channelName))
        for (c in candidates) {
            if (c.isEmpty()) continue
            choose(byNorm[c], country)?.let { return it }
        }
        return null
    }
}

/** Kostenloser deutscher Programmführer als Ersatz bzw. Ergänzung zur EPG des Anbieters. */
object GermanGuide {
    const val URL = "https://epgshare01.online/epgshare01/epg_ripper_DE1.xml.gz"
    const val PREFIX = "ger:"

    const val OFF = "off"
    const val FALLBACK = "fallback"
    const val PREFER = "prefer"

    private val dach = setOf("de", "at", "ch")

    /** Abweichende Schreibweisen der Anbieter, jeweils normalisiert. */
    private val aliases = mapOf(
        "fixandfox" to "fixandfoxi",
        "rtl2" to "rtlzwei",
        "kabel1" to "kabeleins",
        "kabel1doku" to "kabeleinsdoku",
        "weltdoku" to "n24doku",
        "swr" to "swrsr",
        "swrfernsehen" to "swrsr",
        "swrbw" to "swrsr",
        "swrrp" to "swrsr",
        "sr" to "swrsr",
        "ard" to "daserste",
        "servustv" to "servustvosterreich",
        "servustvaustria" to "servustvosterreich",
        "nickjunior" to "nickjr",
    )

    fun isKey(key: String): Boolean = key.startsWith(PREFIX)

    fun nameKey(norm: String): String = PREFIX + norm

    fun isNameKey(norm: String): Boolean = norm.startsWith(PREFIX)

    fun forProgramme(channelId: String): String = PREFIX + channelId

    /** Ob ein Sender mit diesem Land vom deutschen Führer bedient werden darf. */
    fun eligible(country: String?, mode: String): Boolean = when (mode) {
        PREFER -> country != null && country in dach
        FALLBACK -> country == null || country in dach
        else -> false
    }

    /** [byNorm]: normalisierter Name (ohne Vorsatz) auf Schlüssel mit [PREFIX]. */
    fun match(epgKey: String, channelName: String, byNorm: Map<String, String>): String? {
        val candidates = ArrayList<String>(2)
        candidates.add(EpgMatcher.normalize(channelName))
        if (epgKey.isNotBlank() && !isKey(epgKey)) candidates.add(EpgMatcher.normalize(epgKey))
        for (c in candidates) {
            if (c.isEmpty()) continue
            byNorm[c]?.let { return it }
            aliases[c]?.let { a -> byNorm[a]?.let { return it } }
        }
        return null
    }
}
