package de.dgstudios.iptvstream.core.util

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Archivfenster und Abrufart eines Senders. `days == 0` heißt: kein Zurückblicken. */
data class ArchiveMeta(
    val days: Int = 0,
    val mode: String = "",
    val source: String = "",
)

/**
 * Catch-up-URLs.
 * Xtream: `/timeshift/{user}/{pass}/{Minuten}/{yyyy-MM-dd:HH-mm}/{streamId}.{ext}`.
 * M3U: `catchup-source` mit Platzhaltern, sonst Flussonic oder `utc`/`lutc` am Live-Link.
 */
object Catchup {
    private const val MAX_DAYS = 30
    private val xcLive = Regex("""^(https?://[^?]+)/live/([^/]+)/([^/]+)/([^/]+)\.([A-Za-z0-9]+)(?:\?.*)?$""")

    fun xtreamDays(flag: String?, duration: String?): Int {
        val dur = positiveInt(duration)
        if (dur != null) return dur.coerceAtMost(MAX_DAYS)
        val raw = flag?.trim()?.lowercase().orEmpty()
        val n = positiveInt(raw)
        return when {
            n != null && n > 1 -> n.coerceAtMost(MAX_DAYS)
            raw == "1" || raw == "true" -> 7
            else -> 0
        }
    }

    fun fromM3u(attrs: Map<String, String>): ArchiveMeta {
        val modeRaw = (attrs["catchup"] ?: attrs["catchup-type"]).orEmpty().trim().lowercase()
        val source = (attrs["catchup-source"] ?: "").trim()
        val daysAttr = positiveInt(attrs["catchup-days"])
        val shift = positiveInt(attrs["timeshift"])
        val shiftDays = when {
            shift == null -> null
            shift <= MAX_DAYS -> shift
            else -> ((shift + 23) / 24).coerceAtMost(MAX_DAYS)
        }
        val mode = when (modeRaw) {
            "fs", "flussonic", "flussonic-hls" -> "flussonic"
            "xc", "xcode", "xtream", "timeshift" -> "xc"
            "shift" -> "shift"
            "append", "default", "vod" -> "append"
            else -> modeRaw
        }
        val supported = (daysAttr ?: 0) > 0 || shiftDays != null || mode.isNotEmpty() || source.isNotEmpty()
        if (!supported) return ArchiveMeta()
        val days = (daysAttr ?: shiftDays ?: 7).coerceIn(1, MAX_DAYS)
        val resolved = mode.ifBlank { if (source.isNotEmpty()) "source" else "append" }
        return ArchiveMeta(days, resolved, source)
    }

    fun url(
        mode: String,
        source: String,
        liveUrl: String?,
        server: String?,
        username: String?,
        password: String?,
        streamId: String,
        ext: String,
        startMs: Long,
        stopMs: Long,
        nowMs: Long = System.currentTimeMillis(),
    ): String? {
        val startSec = startMs / 1000L
        val endSec = (stopMs / 1000L).coerceAtLeast(startSec + 60L)
        val nowSec = nowMs / 1000L
        if (mode == "xc" && !server.isNullOrBlank() && !username.isNullOrBlank() && !password.isNullOrBlank()) {
            return xtreamPath(server, username, password, streamId, ext, startMs, stopMs)
        }
        if (source.isNotBlank() && !liveUrl.isNullOrBlank()) {
            return applyTemplate(source, liveUrl, startSec, endSec, nowSec)
        }
        if (mode == "xc" && !liveUrl.isNullOrBlank()) {
            val parsed = xcLive.matchEntire(liveUrl.trim())
            if (parsed != null) {
                return xtreamPath(
                    parsed.groupValues[1],
                    parsed.groupValues[2],
                    parsed.groupValues[3],
                    parsed.groupValues[4],
                    parsed.groupValues[5],
                    startMs,
                    stopMs,
                )
            }
        }
        if (liveUrl.isNullOrBlank()) return null
        if (mode == "flussonic") return flussonic(liveUrl, startSec, endSec - startSec)
        return appendUtc(liveUrl, startSec, nowSec)
    }

    private fun xtreamPath(
        server: String,
        user: String,
        pass: String,
        streamId: String,
        ext: String,
        startMs: Long,
        stopMs: Long,
    ): String {
        val minutes = (((stopMs - startMs).coerceAtLeast(60_000L) + 59_999L) / 60_000L).toInt().coerceAtLeast(1)
        val stamp = SimpleDateFormat("yyyy-MM-dd:HH-mm", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("Europe/Berlin")
        }.format(Date(startMs))
        val extension = ext.ifBlank { "ts" }.trim().trimStart('.').ifBlank { "ts" }
        return Urls.join(server, "timeshift", user, pass, minutes.toString(), stamp, "$streamId.$extension")
    }

    private fun applyTemplate(template: String, liveUrl: String, startSec: Long, endSec: Long, nowSec: Long): String {
        val duration = (endSec - startSec).coerceAtLeast(1L)
        val offset = (nowSec - startSec).coerceAtLeast(0L)
        var s = template
        val values = linkedMapOf(
            "\${start}" to startSec,
            "\${end}" to endSec,
            "\${duration}" to duration,
            "\${timestamp}" to nowSec,
            "\${now}" to nowSec,
            "\${offset}" to offset,
            "{utcend}" to endSec,
            "{utc}" to startSec,
            "{start}" to startSec,
            "{end}" to endSec,
            "{duration}" to duration,
            "{lutc}" to nowSec,
            "{timestamp}" to nowSec,
            "{now}" to nowSec,
            "{offset}" to offset,
        )
        for ((key, value) in values) s = s.replace(key, value.toString())
        return resolve(s, liveUrl)
    }

    private fun resolve(template: String, liveUrl: String): String {
        val t = template.trim()
        if (t.startsWith("http://", true) || t.startsWith("https://", true)) return t
        if (t.startsWith("?")) {
            val base = liveUrl.substringBefore('?')
            val query = t.removePrefix("?")
            return if ('?' in liveUrl) "$liveUrl&$query" else "$base?$query"
        }
        if (t.startsWith("&")) {
            val query = t.removePrefix("&")
            return if ('?' in liveUrl) "$liveUrl&$query" else "${liveUrl.substringBefore('?')}?$query"
        }
        if (t.startsWith("/")) {
            val root = liveUrl.toHttpUrlOrNull()
            if (root != null) {
                val path = t.substringBefore('?')
                val query = t.substringAfter('?', "")
                val b = root.newBuilder().encodedPath(path).query(null)
                if (query.isNotEmpty()) {
                    for (part in query.split('&')) {
                        if (part.isEmpty()) continue
                        b.addQueryParameter(part.substringBefore('='), part.substringAfter('=', ""))
                    }
                }
                return b.build().toString()
            }
        }
        val dir = liveUrl.substringBefore('?').substringBeforeLast('/', "")
        return if (dir.isEmpty()) t else "$dir/$t"
    }

    private fun flussonic(url: String, startSec: Long, durationSec: Long): String {
        val query = url.substringAfter('?', "")
        val path = url.substringBefore('?')
        val suffix = if (query.isEmpty()) "" else "?$query"
        val archive = "archive-$startSec-$durationSec.m3u8"
        val rewritten = when {
            path.endsWith("/index.m3u8", true) -> path.dropLast("index.m3u8".length) + archive
            path.endsWith("/mono.m3u8", true) -> path.dropLast("mono.m3u8".length) + archive
            else -> return appendUtc(url, startSec, startSec + durationSec)
        }
        return rewritten + suffix
    }

    private fun appendUtc(url: String, startSec: Long, nowSec: Long): String {
        val sep = if ('?' in url) '&' else '?'
        return "$url${sep}utc=$startSec&lutc=$nowSec"
    }

    private fun positiveInt(raw: String?): Int? {
        val t = raw?.trim()?.takeIf { it.isNotEmpty() && !it.equals("null", true) } ?: return null
        val n = t.toIntOrNull() ?: t.substringBefore('.').toIntOrNull() ?: return null
        return n.takeIf { it > 0 }
    }
}
