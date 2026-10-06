package de.dgstudios.iptvstream.core.data.remote

import de.dgstudios.iptvstream.core.data.EpisodeInfo
import de.dgstudios.iptvstream.core.data.MovieDetail
import de.dgstudios.iptvstream.core.data.Season
import de.dgstudios.iptvstream.core.data.SeriesDetail
import de.dgstudios.iptvstream.core.data.db.ProfileEntity
import de.dgstudios.iptvstream.core.util.Urls
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.util.TreeMap

data class AuthResult(
    val ok: Boolean,
    val message: String,
    val expDate: Long? = null,
    val maxConnections: Int? = null,
)

private fun JSONObject.str(name: String): String? {
    if (!has(name) || isNull(name)) return null
    val s = optString(name, "")
    return s.takeIf { it.isNotBlank() && it != "null" }
}

class XtreamApi(private val http: HttpService) {

    suspend fun authenticate(p: ProfileEntity): AuthResult = withContext(Dispatchers.IO) {
        val text = try {
            http.get(Urls.api(p.url, p.username, p.password)).use { it.string() }
        } catch (e: Exception) {
            return@withContext AuthResult(false, Net.friendly(e))
        }
        val json = try {
            JSONObject(text)
        } catch (e: JSONException) {
            return@withContext AuthResult(
                false,
                "Der Server hat keine gültige Xtream-Antwort geliefert. Ist die URL richtig?",
            )
        }
        val user = json.optJSONObject("user_info")
            ?: return@withContext AuthResult(false, "Anmeldung fehlgeschlagen. Benutzername oder Passwort falsch?")
        val auth = user.optString("auth", "0")
        val status = user.optString("status", "")
        if (auth != "1" && auth != "true") {
            return@withContext AuthResult(false, "Anmeldung fehlgeschlagen. Benutzername oder Passwort falsch?")
        }
        if (status.equals("Banned", true) || status.equals("Disabled", true)) {
            return@withContext AuthResult(false, "Das Konto ist gesperrt ($status).")
        }
        val exp = user.optString("exp_date", "").toLongOrNull()?.times(1000)
        if (status.equals("Expired", true)) {
            return@withContext AuthResult(false, "Das Abo ist abgelaufen.", exp)
        }
        AuthResult(
            true,
            "Anmeldung erfolgreich",
            exp,
            user.optString("max_connections", "").toIntOrNull(),
        )
    }

    suspend fun movieDetail(p: ProfileEntity, vodId: String): MovieDetail? = withContext(Dispatchers.IO) {
        val text = http.get(Urls.api(p.url, p.username, p.password, "get_vod_info", mapOf("vod_id" to vodId)))
            .use { it.string() }
        val root = try { JSONObject(text) } catch (e: JSONException) { return@withContext null }
        val info = root.optJSONObject("info") ?: return@withContext null
        val secs = info.str("duration_secs")?.toIntOrNull()
        val minutes = secs?.div(60) ?: info.str("duration")?.let { parseDurationMinutes(it) } ?: 0
        val year = info.str("releasedate")?.take(4) ?: info.str("releaseDate")?.take(4) ?: info.str("year")
        val backdrop = info.optJSONArray("backdrop_path")?.optString(0)?.takeIf { it.isNotBlank() }
        MovieDetail(
            plot = info.str("plot") ?: info.str("description"),
            genre = info.str("genre"),
            year = year,
            durationMin = minutes,
            rating = info.str("rating")?.toDoubleOrNull() ?: 0.0,
            cast = info.str("cast") ?: info.str("actors"),
            director = info.str("director"),
            backdrop = backdrop,
        )
    }

    suspend fun seriesDetail(p: ProfileEntity, seriesId: String): SeriesDetail? = withContext(Dispatchers.IO) {
        val text = http.get(
            Urls.api(p.url, p.username, p.password, "get_series_info", mapOf("series_id" to seriesId)),
        ).use { it.string() }
        val root = try { JSONObject(text) } catch (e: JSONException) { return@withContext null }
        val info = root.optJSONObject("info")
        val bySeason = TreeMap<Int, MutableList<EpisodeInfo>>()

        fun addEpisode(o: JSONObject, seasonHint: Int) {
            val id = o.str("id") ?: return
            val ei = o.optJSONObject("info")
            val season = o.str("season")?.toIntOrNull() ?: seasonHint
            val number = o.str("episode_num")?.toIntOrNull() ?: 0
            val dur = ei?.str("duration_secs")?.toIntOrNull() ?: 0
            bySeason.getOrPut(season) { ArrayList() }.add(
                EpisodeInfo(
                    id = id,
                    season = season,
                    number = number,
                    title = o.str("title") ?: "Episode $number",
                    ext = o.str("container_extension") ?: "mp4",
                    plot = ei?.str("plot"),
                    durationSec = dur,
                    image = ei?.str("movie_image"),
                ),
            )
        }

        when (val eps = root.opt("episodes")) {
            is JSONObject -> {
                val keys = eps.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    val arr = eps.optJSONArray(key) ?: continue
                    for (i in 0 until arr.length()) {
                        arr.optJSONObject(i)?.let { addEpisode(it, key.toIntOrNull() ?: 0) }
                    }
                }
            }
            is JSONArray -> {
                for (i in 0 until eps.length()) {
                    when (val e = eps.opt(i)) {
                        is JSONObject -> addEpisode(e, 1)
                        is JSONArray -> for (j in 0 until e.length()) {
                            e.optJSONObject(j)?.let { addEpisode(it, i + 1) }
                        }
                    }
                }
            }
        }

        val seasons = bySeason.map { (num, list) ->
            Season(num, list.sortedWith(compareBy<EpisodeInfo>({ it.number }, { it.title })))
        }
        SeriesDetail(
            plot = info?.str("plot"),
            genre = info?.str("genre"),
            year = info?.str("releaseDate")?.take(4) ?: info?.str("release_date")?.take(4),
            rating = info?.str("rating")?.toDoubleOrNull() ?: 0.0,
            cast = info?.str("cast"),
            backdrop = info?.optJSONArray("backdrop_path")?.optString(0)?.takeIf { it.isNotBlank() },
            seasons = seasons,
        )
    }

    private fun parseDurationMinutes(s: String): Int {
        val parts = s.split(":").mapNotNull { it.trim().toIntOrNull() }
        return when (parts.size) {
            3 -> parts[0] * 60 + parts[1]
            2 -> parts[0]
            1 -> parts[0]
            else -> 0
        }
    }
}
