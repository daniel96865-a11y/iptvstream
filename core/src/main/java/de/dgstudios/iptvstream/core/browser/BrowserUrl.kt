package de.dgstudios.iptvstream.core.browser

import java.net.URLEncoder

/** Eingabe in der Adressleiste: Adresse öffnen, sonst neutral bei DuckDuckGo suchen. */
object BrowserUrl {
    const val SEARCH = "https://duckduckgo.com/?q="

    private val scheme = Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://")
    private val host = Regex("^(?:[a-zA-Z0-9-]+\\.)+[a-zA-Z]{2,}(?::\\d{2,5})?(?:[/?#].*)?$")
    private val ip = Regex("^\\d{1,3}(?:\\.\\d{1,3}){3}(?::\\d{2,5})?(?:[/?#].*)?$")
    private val local = Regex("^localhost(?::\\d{2,5})?(?:[/?#].*)?$", RegexOption.IGNORE_CASE)

    fun resolve(raw: String): String? {
        val s = raw.trim()
        if (s.isEmpty()) return null
        if (scheme.containsMatchIn(s)) {
            val l = s.lowercase()
            return if (l.startsWith("http://") || l.startsWith("https://")) s else null
        }
        if (!s.contains(' ')) {
            if (local.matches(s) || ip.matches(s)) return "http://$s"
            if (host.matches(s)) return "https://$s"
        }
        return SEARCH + URLEncoder.encode(s, "UTF-8")
    }

    /** Nur http(s) wird im Browser geladen. */
    fun isWeb(url: String?): Boolean {
        val l = url?.lowercase() ?: return false
        return l.startsWith("http://") || l.startsWith("https://")
    }
}
