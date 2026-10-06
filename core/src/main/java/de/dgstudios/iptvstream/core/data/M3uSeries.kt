package de.dgstudios.iptvstream.core.data

import de.dgstudios.iptvstream.core.data.db.AppDatabase
import de.dgstudios.iptvstream.core.data.db.CatType
import de.dgstudios.iptvstream.core.data.db.CategoryEntity
import de.dgstudios.iptvstream.core.data.db.SeriesEntity
import de.dgstudios.iptvstream.core.util.M3uEntry
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * Gruppiert M3U-Zeilen mit `/series/` in die vorhandene Serienliste.
 * Folgen liegen in einer JSON-Datei neben der Room-Datenbank, damit kein Schemawechsel nötig ist.
 */
internal class M3uSeriesCollector {
    private val groups = LinkedHashMap<String, Int>()
    private val series = LinkedHashMap<String, Acc>()
    private var order = 0

    fun add(entry: M3uEntry) {
        val group = entry.group.ifBlank { "Ohne Kategorie" }
        groups.getOrPut(group) { groups.size }
        val marker = seasonEpisode(entry.name)
        val seriesName = if (marker != null) {
            tidy(entry.name.substring(0, marker.start)).ifBlank { tidy(group).ifBlank { entry.name.trim() } }
        } else {
            tidy(group).ifBlank { tidy(entry.name).ifBlank { "Serie" } }
        }
        val key = group.lowercase() + "\u0000" + seriesName.lowercase()
        val acc = series.getOrPut(key) {
            Acc(
                seriesId = stableId("m3u-series\n$group\n$seriesName"),
                name = seriesName,
                category = group,
                poster = entry.logo,
            )
        }
        if (acc.poster.isNullOrBlank() && !entry.logo.isNullOrBlank()) acc.poster = entry.logo
        order++
        val episodeTitle = if (marker != null) {
            val after = tidy(entry.name.substring(marker.end))
            if (after.isBlank() || quality.matches(after)) "Folge ${marker.episode}" else after
        } else {
            tidy(entry.name).ifBlank { "Folge" }
        }
        acc.episodes.add(
            Ep(
                id = stableId(entry.url),
                season = marker?.season ?: 0,
                number = marker?.episode ?: 0,
                title = episodeTitle,
                ext = fileExt(entry.url),
                image = entry.logo,
                directUrl = entry.url,
                order = order,
            ),
        )
    }

    fun finish() {
        for (acc in series.values) {
            val maxBySeason = HashMap<Int, Int>()
            for (ep in acc.episodes) {
                if (ep.season > 0 && ep.number > 0) {
                    maxBySeason[ep.season] = maxOf(maxBySeason[ep.season] ?: 0, ep.number)
                }
            }
            for (ep in acc.episodes) {
                if (ep.season <= 0) ep.season = 1
                if (ep.number <= 0) {
                    val n = (maxBySeason[ep.season] ?: 0) + 1
                    ep.number = n
                    maxBySeason[ep.season] = n
                }
            }
        }
    }

    fun categories(profileId: Long, token: Long): List<CategoryEntity> =
        groups.entries.map { CategoryEntity(profileId, CatType.SERIES, it.key, it.key, it.value, token) }

    fun entities(profileId: Long, token: Long): List<SeriesEntity> =
        series.values.map {
            SeriesEntity(
                profileId = profileId,
                seriesId = it.seriesId,
                name = it.name,
                poster = it.poster,
                categoryId = it.category,
                rating = 0.0,
                year = null,
                genre = null,
                plot = null,
                added = 0L,
                sync = token,
            )
        }

    fun details(): Map<String, SeriesDetail> {
        val out = LinkedHashMap<String, SeriesDetail>()
        for (acc in series.values) {
            val bySeason = acc.episodes.groupBy { it.season }
            val seasons = bySeason.keys.sorted().map { num ->
                val eps = bySeason.getValue(num)
                    .sortedWith(compareBy<Ep>({ it.number }, { it.order }))
                    .map { ep ->
                        EpisodeInfo(
                            id = ep.id,
                            season = ep.season,
                            number = ep.number,
                            title = ep.title,
                            ext = ep.ext,
                            plot = null,
                            durationSec = 0,
                            image = ep.image,
                            directUrl = ep.directUrl,
                        )
                    }
                Season(num, eps)
            }
            out[acc.seriesId] = SeriesDetail(
                plot = null,
                genre = null,
                year = null,
                rating = 0.0,
                cast = null,
                backdrop = acc.poster,
                seasons = seasons,
            )
        }
        return out
    }

    private class Acc(
        val seriesId: String,
        val name: String,
        val category: String,
        var poster: String?,
        val episodes: ArrayList<Ep> = ArrayList(),
    )

    private class Ep(
        val id: String,
        var season: Int,
        var number: Int,
        val title: String,
        val ext: String,
        val image: String?,
        val directUrl: String,
        val order: Int,
    )

    private data class Marker(val season: Int, val episode: Int, val start: Int, val end: Int)

    private companion object {
        val seRegex = Regex("""(?i)S(\d{1,3})\s*[\.\-_]?\s*E(\d{1,4})""")
        val xRegex = Regex("""(?i)(?<!\d)(\d{1,2})x(\d{1,3})(?!\d)""")
        val quality = Regex("""(?i)^(uhd|fhd|hd|sd|4k|8k|hevc|h265|h264|1080p|720p|2160p)$""")

        fun seasonEpisode(name: String): Marker? {
            val se = seRegex.find(name)
            if (se != null) return marker(se.groupValues[1], se.groupValues[2], se.range.first, se.range.last + 1)
            val x = xRegex.find(name) ?: return null
            return marker(x.groupValues[1], x.groupValues[2], x.range.first, x.range.last + 1)
        }

        fun marker(seasonRaw: String, episodeRaw: String, start: Int, end: Int): Marker? {
            val season = seasonRaw.toIntOrNull() ?: return null
            val episode = episodeRaw.toIntOrNull() ?: return null
            if (season !in 1..200 || episode !in 1..9999) return null
            return Marker(season, episode, start, end)
        }

        fun tidy(raw: String): String {
            var s = raw.trim().trim('.', '-', '_', '|', '/')
            if (s.isNotEmpty() && !s.contains(' ') && (s.contains('.') || s.contains('_'))) {
                s = s.replace('.', ' ').replace('_', ' ')
            }
            return s.replace(Regex("""\s+"""), " ").trim(' ', '-', '|', '.', '_')
        }

        fun fileExt(url: String): String {
            val path = url.substringBefore('?').substringBefore('#')
            val ext = path.substringAfterLast('.', "").lowercase().filter { it.isLetterOrDigit() }.take(8)
            return ext.ifBlank { "mp4" }
        }

        fun stableId(raw: String): String =
            UUID.nameUUIDFromBytes(raw.toByteArray(Charsets.UTF_8)).toString().replace("-", "")
    }
}

/** Folgen von M3U-Serien, profilweise, neben `iptv.db`. */
internal object M3uSeriesStore {
    private val lock = Any()

    fun load(db: AppDatabase, profileId: Long, seriesId: String): SeriesDetail? = synchronized(lock) {
        val root = read(file(db)) ?: return null
        val profile = root.optJSONObject(profileId.toString()) ?: return null
        val obj = profile.optJSONObject(seriesId) ?: return null
        decodeDetail(obj)
    }

    fun replace(db: AppDatabase, profileId: Long, details: Map<String, SeriesDetail>) = synchronized(lock) {
        val target = file(db)
        val root = read(target) ?: JSONObject()
        val profile = JSONObject()
        for ((id, detail) in details) profile.put(id, encodeDetail(detail))
        root.put(profileId.toString(), profile)
        target.parentFile?.mkdirs()
        target.writeText(root.toString(), Charsets.UTF_8)
    }

    fun removeProfile(db: AppDatabase, profileId: Long) = synchronized(lock) {
        val target = file(db)
        if (!target.isFile) return
        val root = read(target) ?: return
        root.remove(profileId.toString())
        target.writeText(root.toString(), Charsets.UTF_8)
    }

    private fun file(db: AppDatabase): File {
        val path = db.openHelper.writableDatabase.path ?: error("Datenbankpfad fehlt")
        return File(path).resolveSibling("m3u-series.json")
    }

    private fun read(target: File): JSONObject? {
        if (!target.isFile) return null
        return try {
            JSONObject(target.readText(Charsets.UTF_8))
        } catch (e: Exception) {
            null
        }
    }

    private fun encodeDetail(d: SeriesDetail): JSONObject = JSONObject().apply {
        putNullable("plot", d.plot)
        putNullable("genre", d.genre)
        putNullable("year", d.year)
        put("rating", d.rating)
        putNullable("cast", d.cast)
        putNullable("backdrop", d.backdrop)
        val seasons = JSONArray()
        for (season in d.seasons) {
            val episodes = JSONArray()
            for (ep in season.episodes) {
                episodes.put(
                    JSONObject().apply {
                        put("id", ep.id)
                        put("season", ep.season)
                        put("number", ep.number)
                        put("title", ep.title)
                        put("ext", ep.ext)
                        putNullable("plot", ep.plot)
                        put("durationSec", ep.durationSec)
                        putNullable("image", ep.image)
                        putNullable("directUrl", ep.directUrl)
                    },
                )
            }
            seasons.put(JSONObject().apply {
                put("number", season.number)
                put("episodes", episodes)
            })
        }
        put("seasons", seasons)
    }

    private fun decodeDetail(o: JSONObject): SeriesDetail {
        val seasonsJson = o.optJSONArray("seasons") ?: JSONArray()
        val seasons = ArrayList<Season>(seasonsJson.length())
        for (i in 0 until seasonsJson.length()) {
            val s = seasonsJson.optJSONObject(i) ?: continue
            val epsJson = s.optJSONArray("episodes") ?: JSONArray()
            val eps = ArrayList<EpisodeInfo>(epsJson.length())
            for (j in 0 until epsJson.length()) {
                val e = epsJson.optJSONObject(j) ?: continue
                val id = text(e, "id") ?: continue
                eps.add(
                    EpisodeInfo(
                        id = id,
                        season = e.optInt("season", s.optInt("number", 1)),
                        number = e.optInt("number", 0),
                        title = text(e, "title") ?: "Folge",
                        ext = (text(e, "ext") ?: "mp4").lowercase(),
                        plot = text(e, "plot"),
                        durationSec = e.optInt("durationSec", 0),
                        image = text(e, "image"),
                        directUrl = text(e, "directUrl"),
                    ),
                )
            }
            seasons.add(Season(s.optInt("number", 1), eps))
        }
        return SeriesDetail(
            plot = text(o, "plot"),
            genre = text(o, "genre"),
            year = text(o, "year"),
            rating = o.optDouble("rating", 0.0),
            cast = text(o, "cast"),
            backdrop = text(o, "backdrop"),
            seasons = seasons,
        )
    }

    private fun text(o: JSONObject, name: String): String? {
        if (!o.has(name) || o.isNull(name)) return null
        return o.optString(name).takeIf { it.isNotBlank() && it != "null" }
    }

    private fun JSONObject.putNullable(name: String, value: String?) {
        if (value.isNullOrBlank()) remove(name) else put(name, value)
    }
}
