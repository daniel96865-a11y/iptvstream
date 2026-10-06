package de.dgstudios.iptvstream.core.util

import java.io.BufferedReader

data class M3uEntry(
    val name: String,
    val url: String,
    val logo: String?,
    val group: String,
    val tvgId: String,
    val number: Int?,
    val archiveDays: Int = 0,
    val catchupMode: String = "",
    val catchupSource: String = "",
)

/**
 * Streaming-Parser für M3U/M3U8-Playlists. Liest zeilenweise, damit auch sehr große
 * Listen wenig Speicher brauchen. Die EPG-URL aus dem Header steht nach dem ersten
 * Lesen in [epgUrl].
 */
class M3uParser {
    var epgUrl: String? = null
        private set

    private val attrRegex = Regex("""([\w-]+)\s*=\s*"([^"]*)"""")

    fun entries(reader: BufferedReader): Sequence<M3uEntry> = sequence {
        var attrs: Map<String, String> = emptyMap()
        var name: String? = null
        var groupOverride: String? = null
        while (true) {
            val line = reader.readLine() ?: break
            val t = stripLine(line)
            if (t.isEmpty()) continue
            if (t.startsWith("#EXTM3U", ignoreCase = true)) {
                val a = parseAttrs(t)
                epgUrl = a["url-tvg"] ?: a["x-tvg-url"] ?: a["tvg-url"]
                continue
            }
            if (t.startsWith("#EXTINF", ignoreCase = true)) {
                val body = t.substringAfter(':', "")
                var inQuote = false
                var comma = -1
                for (i in body.indices) {
                    val c = body[i]
                    if (c == '"') inQuote = !inQuote
                    else if (c == ',' && !inQuote) {
                        comma = i
                        break
                    }
                }
                val attrPart = if (comma >= 0) body.substring(0, comma) else body
                attrs = parseAttrs(attrPart)
                name = if (comma >= 0) body.substring(comma + 1).trim() else ""
                continue
            }
            if (t.startsWith("#EXTGRP:", ignoreCase = true)) {
                groupOverride = t.substringAfter(':').trim()
                continue
            }
            if (t.startsWith("#")) continue

            // URL-Zeile
            val displayName = (name?.takeIf { it.isNotBlank() } ?: attrs["tvg-name"] ?: t).trim()
            val group = (attrs["group-title"]?.takeIf { it.isNotBlank() } ?: groupOverride ?: "Ohne Kategorie").trim()
            val archive = Catchup.fromM3u(attrs)
            yield(
                M3uEntry(
                    name = displayName,
                    url = t,
                    logo = attrs["tvg-logo"]?.takeIf { it.isNotBlank() },
                    group = group,
                    tvgId = attrs["tvg-id"].orEmpty(),
                    number = attrs["tvg-chno"]?.trim()?.toIntOrNull(),
                    archiveDays = archive.days,
                    catchupMode = archive.mode,
                    catchupSource = archive.source,
                ),
            )
            attrs = emptyMap()
            name = null
            groupOverride = null
        }
    }

    companion object {
        /** Wie der Parser: BOM und führende Leerzeilen zählen nicht als Inhalt. */
        fun looksLikePlaylist(reader: BufferedReader): Boolean {
            while (true) {
                val line = reader.readLine() ?: return false
                val t = stripLine(line)
                if (t.isEmpty()) continue
                return t.startsWith("#EXTM3U", ignoreCase = true) || t.startsWith("#EXTINF", ignoreCase = true)
            }
        }

        fun stripLine(line: String): String = line.removePrefix("\uFEFF").trim()
    }

    private fun parseAttrs(s: String): Map<String, String> {
        val m = HashMap<String, String>()
        for (match in attrRegex.findAll(s)) {
            m[match.groupValues[1].lowercase()] = match.groupValues[2]
        }
        return m
    }
}
