package de.dgstudios.iptvstream.core.data

import de.dgstudios.iptvstream.core.util.M3uEntry
import de.dgstudios.iptvstream.core.util.StreamHeaders

/**
 * „Freie Sender“: frei empfangbare Sender aus dem GitHub-Projekt iptv-org
 * (https://github.com/iptv-org/iptv). Pro Sprache gibt es dort eine M3U-Liste.
 *
 * Das Profil speichert nur die Auswahl: url = Sprachcodes mit Komma („deu,ita“),
 * username = „nogeo“, wenn als geo-gesperrt gekennzeichnete Sender ausgeblendet werden.
 */
object FreeTv {
    const val BASE = "https://iptv-org.github.io/iptv/languages/"
    const val DEFAULT_LANGUAGES = "deu,ita"
    const val NO_GEO = "nogeo"
    const val PROFILE_NAME = "Freie Sender"

    fun listUrl(code: String): String = "$BASE$code.m3u"

    /** Öffentliche Programmführer (epgshare01), passend zu den Sprachen. DE läuft über [GermanGuide]. */
    private val EPG_BY_LANG = mapOf(
        "deu" to null, // GermanGuide
        "ita" to "https://epgshare01.online/epgshare01/epg_ripper_IT1.xml.gz",
        "fra" to "https://epgshare01.online/epgshare01/epg_ripper_FR1.xml.gz",
        "spa" to "https://epgshare01.online/epgshare01/epg_ripper_ES1.xml.gz",
        "eng" to "https://epgshare01.online/epgshare01/epg_ripper_UK1.xml.gz",
        "tur" to "https://epgshare01.online/epgshare01/epg_ripper_TR1.xml.gz",
        "por" to "https://epgshare01.online/epgshare01/epg_ripper_PT1.xml.gz",
        "nld" to "https://epgshare01.online/epgshare01/epg_ripper_NL1.xml.gz",
        "pol" to "https://epgshare01.online/epgshare01/epg_ripper_PL1.xml.gz",
        "ron" to "https://epgshare01.online/epgshare01/epg_ripper_RO1.xml.gz",
        "ell" to "https://epgshare01.online/epgshare01/epg_ripper_GR1.xml.gz",
        "hun" to "https://epgshare01.online/epgshare01/epg_ripper_HU1.xml.gz",
        "ces" to "https://epgshare01.online/epgshare01/epg_ripper_CZ1.xml.gz",
        "slk" to "https://epgshare01.online/epgshare01/epg_ripper_SK1.xml.gz",
        "hrv" to "https://epgshare01.online/epgshare01/epg_ripper_HR1.xml.gz",
        "srp" to "https://epgshare01.online/epgshare01/epg_ripper_RS1.xml.gz",
        "bul" to "https://epgshare01.online/epgshare01/epg_ripper_BG1.xml.gz",
        "ukr" to "https://epgshare01.online/epgshare01/epg_ripper_UA1.xml.gz",
        "ara" to "https://epgshare01.online/epgshare01/epg_ripper_AE1.xml.gz",
        "heb" to "https://epgshare01.online/epgshare01/epg_ripper_IL1.xml.gz",
        "swe" to "https://epgshare01.online/epgshare01/epg_ripper_SE1.xml.gz",
        "dan" to "https://epgshare01.online/epgshare01/epg_ripper_DK1.xml.gz",
        "nor" to "https://epgshare01.online/epgshare01/epg_ripper_NO1.xml.gz",
        "nob" to "https://epgshare01.online/epgshare01/epg_ripper_NO1.xml.gz",
        "fin" to "https://epgshare01.online/epgshare01/epg_ripper_FI1.xml.gz",
        "jpn" to "https://epgshare01.online/epgshare01/epg_ripper_JP1.xml.gz",
        "kor" to "https://epgshare01.online/epgshare01/epg_ripper_KR1.xml.gz",
        "zho" to "https://epgshare01.online/epgshare01/epg_ripper_HK1.xml.gz",
        "hin" to "https://epgshare01.online/epgshare01/epg_ripper_IN1.xml.gz",
    )

    data class EpgPlan(val providerUrls: List<String>, val wantGermanGuide: Boolean)

    /** Welche Programmführer für die gewählten Sprachen geladen werden. */
    fun epgPlan(languages: Collection<String>): EpgPlan {
        val langs = languages.map { it.lowercase() }.toSet()
        val urls = LinkedHashSet<String>()
        var german = false
        for (code in languages) {
            when (val u = EPG_BY_LANG[code.lowercase()]) {
                null -> if (code.lowercase() == "deu") german = true
                else -> urls += u
            }
        }
        return EpgPlan(providerUrls = urls.toList(), wantGermanGuide = german)
    }


    data class Config(val languages: List<String>, val hideGeo: Boolean)

    fun encodeLanguages(codes: Collection<String>): String =
        codes.map { it.trim().lowercase() }.filter { it.matches(CODE) }.distinct().joinToString(",")

    fun decode(url: String, options: String): Config = Config(
        languages = url.split(',').map { it.trim().lowercase() }.filter { it.matches(CODE) }.distinct(),
        hideGeo = options.split(',').any { it.trim() == NO_GEO },
    )

    private val CODE = Regex("[a-z]{3}")

    private val CATEGORY_DE = mapOf(
        "general" to "Allgemein", "news" to "Nachrichten", "sports" to "Sport", "entertainment" to "Unterhaltung",
        "movies" to "Filme", "series" to "Serien", "documentary" to "Doku", "kids" to "Kinder", "music" to "Musik",
        "comedy" to "Comedy", "religious" to "Religion", "shop" to "Shopping", "lifestyle" to "Lifestyle",
        "legislative" to "Parlament", "animation" to "Animation", "culture" to "Kultur", "education" to "Bildung",
        "outdoor" to "Outdoor", "family" to "Familie", "cooking" to "Kochen", "weather" to "Wetter",
        "travel" to "Reisen", "business" to "Wirtschaft", "auto" to "Auto", "classic" to "Klassiker",
        "science" to "Wissenschaft", "relax" to "Entspannung", "interactive" to "Interaktiv",
        "public" to "Öffentlich", "undefined" to "Sonstige",
    )

    /** Reihenfolge der Kategorien innerhalb einer Sprache: Wichtiges oben, „Sonstige“ unten. */
    private val CATEGORY_ORDER = listOf(
        "general", "news", "sports", "entertainment", "movies", "series", "documentary", "kids", "music",
        "comedy", "animation", "family", "culture", "education", "lifestyle", "cooking", "travel", "outdoor",
        "science", "business", "classic", "weather", "religious", "legislative", "public", "shop", "auto",
        "relax", "interactive", "undefined",
    )

    /** „Movies;Series“ wird zu „Filme / Serien“. Unbekannte Namen bleiben unverändert. */
    fun categoryLabel(raw: String): String {
        val parts = raw.split(';').map { it.trim() }.filter { it.isNotEmpty() }
        if (parts.isEmpty()) return CATEGORY_DE.getValue("undefined")
        return parts.joinToString(" / ") { CATEGORY_DE[it.lowercase()] ?: it }
    }

    private fun categoryRank(raw: String): Int {
        val first = raw.split(';').first().trim().lowercase()
        val i = CATEGORY_ORDER.indexOf(first)
        return if (i >= 0) i else CATEGORY_ORDER.size - 1
    }

    /** Die Kennzeichnung steht im Sendernamen, z. B. „Rai Scuola (576p) [Geo-blocked]“. */
    fun isGeoBlocked(name: String): Boolean = name.contains("geo-blocked", ignoreCase = true)

    data class Channel(val entry: M3uEntry, val group: String, val playUrl: String)

    /**
     * Führt die Listen mehrerer Sprachen zusammen. Reihenfolge: Sprachen wie ausgewählt,
     * darin Kategorien nach [CATEGORY_ORDER], darin die Reihenfolge der Liste.
     * Ein Sender, der in mehreren Sprachlisten steht, erscheint nur einmal (bei der ersten Sprache).
     */
    fun merge(lists: List<Pair<String, List<M3uEntry>>>, hideGeo: Boolean): List<Channel> {
        val multi = lists.size > 1
        val seen = HashSet<String>()
        val out = ArrayList<Triple<Int, Int, Channel>>()
        lists.forEachIndexed { langIndex, (code, entries) ->
            val langLabel = FreeLanguages.find(code)?.label ?: code
            for (e in entries) {
                if (hideGeo && isGeoBlocked(e.name)) continue
                val key = e.url.substringBefore('#')
                if (!seen.add(key)) continue
                val cat = categoryLabel(e.group)
                val group = if (multi) "$langLabel · $cat" else cat
                out += Triple(langIndex, categoryRank(e.group), Channel(e, group, StreamHeaders.attach(e.url, e.userAgent, e.referrer)))
            }
        }
        // Stabil sortieren: gleiche (Sprache, Rang) behalten die Reihenfolge der Liste,
        // Kombinationen wie „Filme / Serien“ stehen beim Rang ihrer ersten Kategorie.
        return out.sortedWith(compareBy({ it.first }, { it.second })).map { it.third }
    }
}
