package de.dgstudios.iptvstream.core.browser

import java.net.URLEncoder

/**
 * Eingabe in der Adressleiste: Adresse öffnen, sonst neutral bei DuckDuckGo suchen.
 * - Mit Schema (http:// oder https://): genau so öffnen, nie umschreiben.
 * - IP-Adresse, localhost, Name mit Port („nas:5000“), .local/.lan/.box/.home …: direkt http (Heimnetz).
 * - Sonst Domain ohne Schema: erst https, schlägt das fehl, automatisch http ([Target.fallback]).
 */
object BrowserUrl {
    const val SEARCH = "https://duckduckgo.com/?q="

    /** Was geladen wird; [fallback] = http-Adresse, falls https nicht klappt (nur bei Eingabe ohne Schema). */
    data class Target(val url: String, val fallback: String? = null)

    private val schemeSlashes = Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://")
    private const val REST = "(?:[/?#].*)?"
    private val host = Regex("^(?:[a-zA-Z0-9-]+\\.)+[a-zA-Z]{2,63}\\.?(?::\\d{1,5})?$REST$")
    private val ipv4 = Regex("^\\d{1,3}(?:\\.\\d{1,3}){3}(?::\\d{1,5})?$REST$")
    private val ipv6 = Regex("^\\[[0-9a-fA-F:.]+](?::\\d{1,5})?$REST$")
    private val local = Regex("^localhost(?::\\d{1,5})?$REST$", RegexOption.IGNORE_CASE)
    private val nameWithPort = Regex("^[a-zA-Z0-9-]+:\\d{1,5}$REST$")
    private val lanSuffix = Regex("^[a-zA-Z0-9.-]+\\.(?:local|lan|home|box|internal|intranet|localdomain|fritz\\.box)(?::\\d{1,5})?$REST$", RegexOption.IGNORE_CASE)

    fun resolve(raw: String): String? = target(raw)?.url

    fun target(raw: String): Target? {
        val s = raw.trim()
        if (s.isEmpty()) return null
        if (schemeSlashes.containsMatchIn(s)) {
            val l = s.lowercase()
            return if (l.startsWith("http://") || l.startsWith("https://")) Target(s) else null
        }
        if (!s.contains(' ')) {
            if (ipv4.matches(s) || ipv6.matches(s) || local.matches(s) || nameWithPort.matches(s) || lanSuffix.matches(s)) {
                return Target("http://$s")
            }
            if (host.matches(s)) return Target("https://$s", fallback = "http://$s")
        }
        // Alles andere (auch „javascript:…“ oder „mailto:…“) wird gesucht, nie ausgeführt.
        return Target(SEARCH + URLEncoder.encode(s, "UTF-8"))
    }

    /** Nur http(s) wird im Browser geladen. */
    fun isWeb(url: String?): Boolean {
        val l = url?.lowercase() ?: return false
        return l.startsWith("http://") || l.startsWith("https://")
    }

    /** Gleiche Adresse (ohne abschließenden Schrägstrich, ohne Groß/Klein beim Schema). */
    fun same(a: String?, b: String?): Boolean {
        if (a == null || b == null) return false
        fun norm(u: String) = u.trim().trimEnd('/').let { it.substringBefore("://").lowercase() + "://" + it.substringAfter("://") }
        return norm(a) == norm(b)
    }
}
