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
