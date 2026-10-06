package de.dgstudios.iptvstream.core.util

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** URL-Hilfen für Xtream-Codes. Keine Android-Abhängigkeiten. */
object Urls {
    private val trailingNames = setOf("get.php", "player_api.php", "panel_api.php", "xmltv.php", "c", "index.php")

    /** Macht aus Eingaben wie "host:8080/get.php?..." eine saubere Basis-URL ohne Slash am Ende. */
    fun normalizeServer(raw: String): String {
        var s = raw.trim()
        if (s.isEmpty()) return s
        if (!s.startsWith("http://", true) && !s.startsWith("https://", true)) s = "http://$s"
        val u = s.toHttpUrlOrNull() ?: return s.trimEnd('/')
        val segs = u.pathSegments.filter { it.isNotEmpty() }.toMutableList()
        while (segs.isNotEmpty() && segs[segs.lastIndex].lowercase() in trailingNames) {
            segs.removeAt(segs.lastIndex)
        }
        val b = HttpUrl.Builder().scheme(u.scheme).host(u.host).port(u.port)
        for (seg in segs) b.addPathSegment(seg)
        return b.build().toString().trimEnd('/')
    }

    /** Hängt Pfadsegmente (korrekt kodiert) an die Basis-URL an. */
    fun join(base: String, vararg segments: String): String {
        val u = base.toHttpUrlOrNull()
            ?: return base.trimEnd('/') + "/" + segments.joinToString("/")
        val b = u.newBuilder()
        for (seg in segments) b.addPathSegment(seg)
        return b.build().toString()
    }

    /** player_api.php-URL mit Zugangsdaten, optionaler Action und Zusatzparametern. */
    fun api(
        base: String,
        user: String,
        pass: String,
        action: String? = null,
        extra: Map<String, String> = emptyMap(),
    ): String {
        val root = base.toHttpUrlOrNull() ?: throw IllegalArgumentException("Ungültige Server-URL")
        val b = root.newBuilder().addPathSegment("player_api.php")
            .addQueryParameter("username", user)
            .addQueryParameter("password", pass)
        if (action != null) b.addQueryParameter("action", action)
        for ((k, v) in extra) b.addQueryParameter(k, v)
        return b.build().toString()
    }

    fun xmltv(base: String, user: String, pass: String): String {
        val root = base.toHttpUrlOrNull() ?: throw IllegalArgumentException("Ungültige Server-URL")
        return root.newBuilder().addPathSegment("xmltv.php")
            .addQueryParameter("username", user)
            .addQueryParameter("password", pass)
            .build().toString()
    }

    fun isHls(url: String): Boolean {
        val l = url.lowercase()
        return l.substringBefore('?').endsWith(".m3u8") || l.contains(".m3u8?")
    }
}
