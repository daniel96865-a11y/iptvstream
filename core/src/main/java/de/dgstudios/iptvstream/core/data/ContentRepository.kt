package de.dgstudios.iptvstream.core.data

import android.util.JsonReader
import androidx.room.withTransaction
import de.dgstudios.iptvstream.core.data.db.AppDatabase
import de.dgstudios.iptvstream.core.data.db.CatType
import de.dgstudios.iptvstream.core.data.db.CategoryEntity
import de.dgstudios.iptvstream.core.data.db.ChannelEntity
import de.dgstudios.iptvstream.core.data.db.EpgEntity
import de.dgstudios.iptvstream.core.data.db.FavoriteEntity
import de.dgstudios.iptvstream.core.data.db.MovieEntity
import de.dgstudios.iptvstream.core.data.db.ProfileEntity
import de.dgstudios.iptvstream.core.data.db.ProfileType
import de.dgstudios.iptvstream.core.data.db.ProgressEntity
import de.dgstudios.iptvstream.core.data.db.SeriesEntity
import de.dgstudios.iptvstream.core.data.remote.HttpService
import de.dgstudios.iptvstream.core.data.remote.Json
import de.dgstudios.iptvstream.core.data.remote.Net
import de.dgstudios.iptvstream.core.data.remote.XtreamApi
import de.dgstudios.iptvstream.core.util.Catchup
import de.dgstudios.iptvstream.core.util.M3uParser
import de.dgstudios.iptvstream.core.util.Urls
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.UUID

/** Virtuelle Kategorien, die es in allen Bereichen gibt. */
object Cat {
    const val ALL = "__all"
    const val FAV = "__fav"
    const val RECENT = "__recent"

    fun label(id: String, groupName: String? = null): String = when (id) {
        ALL -> "Alle"
        FAV -> "★ Favoriten"
        RECENT -> "Zuletzt"
        else -> groupName?.takeIf { it.isNotBlank() } ?: "Kategorie"
    }
}

data class SearchResults(
    val channels: List<ChannelEntity> = emptyList(),
    val movies: List<MovieEntity> = emptyList(),
    val series: List<SeriesEntity> = emptyList(),
) {
    val isEmpty: Boolean get() = channels.isEmpty() && movies.isEmpty() && series.isEmpty()
}

class ContentRepository(
    private val db: AppDatabase,
    private val http: HttpService,
    private val xtream: XtreamApi,
    private val epg: EpgManager,
    private val scope: CoroutineScope,
) {
    private val _sync = MutableStateFlow(SyncState())
    val syncState: StateFlow<SyncState> = _sync
    private val syncMutex = Mutex()

    private val seriesCache = object : LinkedHashMap<String, SeriesDetail>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, SeriesDetail>?): Boolean = size > 24
    }

    // ------------------------------------------------------------------ Profile

    fun profiles(): Flow<List<ProfileEntity>> = db.profiles().observeAll()

    suspend fun profile(id: Long): ProfileEntity? = db.profiles().get(id)

    suspend fun allProfiles(): List<ProfileEntity> = db.profiles().all()

    /** Prüft bei Xtream die Anmeldung. Gibt eine Fehlermeldung zurück oder null bei Erfolg. */
    suspend fun testProfile(p: ProfileEntity): String? {
        return try {
            if (p.type == ProfileType.XTREAM) {
                val r = xtream.authenticate(p)
                if (r.ok) null else r.message
            } else {
                withContext(Dispatchers.IO) {
                    http.get(p.url).use { body ->
                        BufferedReader(InputStreamReader(body.byteStream(), Charsets.UTF_8)).use { reader ->
                            if (M3uParser.looksLikePlaylist(reader)) null
                            else "Die Adresse liefert keine gültige M3U-Playlist."
                        }
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Net.friendly(e)
        }
    }

    /** Legt ein Profil an (id == 0) oder aktualisiert es. Gibt die ID zurück. */
    suspend fun saveProfile(p: ProfileEntity): Long {
        val clean = p.copy(
            name = p.name.trim().ifEmpty { "Meine Liste" },
            url = if (p.type == ProfileType.XTREAM) Urls.normalizeServer(p.url) else p.url.trim(),
            username = p.username.trim(),
            password = p.password,
            epgUrl = p.epgUrl.trim(),
        )
        return if (clean.id == 0L) {
            db.profiles().insert(clean)
        } else {
            db.profiles().update(clean)
            clean.id
        }
    }

    suspend fun deleteProfile(id: Long) {
        db.withTransaction {
            db.content().clearCategories(id)
            db.content().clearChannels(id)
            db.content().clearMovies(id)
            db.content().clearSeries(id)
            db.epg().clear(id)
            db.epg().clearNames(id)
            db.user().clearFavorites(id)
            db.user().clearProgress(id)
            db.profiles().delete(id)
        }
        withContext(Dispatchers.IO) { M3uSeriesStore.removeProfile(db, id) }
    }

    // ------------------------------------------------------------------ Listen

    fun categories(profileId: Long, type: String): Flow<List<CategoryEntity>> =
        db.content().categories(profileId, type)

    fun channels(profileId: Long, categoryId: String): Flow<List<ChannelEntity>> = when (categoryId) {
        Cat.ALL -> db.content().allChannels(profileId)
        Cat.FAV -> db.content().favoriteChannels(profileId)
        Cat.RECENT -> db.content().recentChannels(profileId)
        else -> db.content().channelsIn(profileId, categoryId)
    }

    fun movies(profileId: Long, categoryId: String): Flow<List<MovieEntity>> = when (categoryId) {
        Cat.ALL -> db.content().allMovies(profileId)
        Cat.FAV -> db.content().favoriteMovies(profileId)
        Cat.RECENT -> db.content().recentMovies(profileId)
        else -> db.content().moviesIn(profileId, categoryId)
    }

    fun series(profileId: Long, categoryId: String): Flow<List<SeriesEntity>> = when (categoryId) {
        Cat.ALL -> db.content().allSeries(profileId)
        Cat.FAV -> db.content().favoriteSeries(profileId)
        Cat.RECENT -> db.content().recentSeries(profileId)
        else -> db.content().seriesIn(profileId, categoryId)
    }

    suspend fun channel(profileId: Long, id: String): ChannelEntity? = db.content().channel(profileId, id)
    suspend fun channelByNumber(profileId: Long, n: Int): ChannelEntity? = db.content().channelByNumber(profileId, n)
    suspend fun firstChannel(profileId: Long): ChannelEntity? = db.content().firstChannel(profileId)
    suspend fun movie(profileId: Long, id: String): MovieEntity? = db.content().movie(profileId, id)
    suspend fun seriesById(profileId: Long, id: String): SeriesEntity? = db.content().series(profileId, id)

    suspend fun search(profileId: Long, query: String): SearchResults {
        val q = query.trim()
        if (q.length < 2) return SearchResults()
        val c = db.content()
        return SearchResults(
            channels = c.searchChannels(profileId, q, 60),
            movies = c.searchMovies(profileId, q, 60),
            series = c.searchSeries(profileId, q, 60),
        )
    }

    // ------------------------------------------------------------------ Favoriten

    fun favoriteIds(profileId: Long, type: String): Flow<List<String>> = db.user().favoriteIds(profileId, type)

    suspend fun toggleFavorite(profileId: Long, type: String, id: String) {
        val u = db.user()
        if (u.favoriteCount(profileId, type, id) > 0) {
            u.removeFavorite(profileId, type, id)
        } else {
            u.addFavorite(FavoriteEntity(profileId, type, id, System.currentTimeMillis()))
        }
    }

    // ------------------------------------------------------------------ EPG

    suspend fun nowNext(profileId: Long, epgKey: String?): NowNext =
        epg.nowNext(profileId, epgKey, System.currentTimeMillis())

    suspend fun schedule(profileId: Long, epgKey: String?, hours: Int = 24) =
        epg.schedule(profileId, epgKey, System.currentTimeMillis() - 3_600_000L, System.currentTimeMillis() + hours * 3_600_000L)

    /**
     * Vergangene Sendungen im Archivfenster, neueste zuerst. Leer, wenn der Sender kein Archiv hat.
     * Xtream: zusätzlich die Archivliste des Servers (get_simple_data_table), weil das XMLTV
     * oft nur wenige Stunden Vergangenheit enthält.
     */
    suspend fun archiveProgrammes(profileId: Long, channel: ChannelEntity): List<EpgEntity> {
        if (channel.archiveDays <= 0) return emptyList()
        val now = System.currentTimeMillis()
        val from = now - channel.archiveDays * 24L * 3_600_000L
        val local = if (channel.epgKey.isBlank()) emptyList() else epg.schedule(profileId, channel.epgKey, from, now)
        val p = db.profiles().get(profileId)
        val remote = if (p != null && p.type == ProfileType.XTREAM && channel.directUrl == null) {
            try {
                xtreamArchive(p, channel, from, now)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                emptyList()
            }
        } else {
            emptyList()
        }
        val merged = LinkedHashMap<Long, EpgEntity>()
        for (e in remote) merged[e.start] = e
        for (e in local) if (e.start !in merged) merged[e.start] = e
        return merged.values
            .filter { it.start < now && it.start >= from - 3_600_000L }
            .sortedByDescending { it.start }
    }

    private suspend fun xtreamArchive(p: ProfileEntity, c: ChannelEntity, from: Long, now: Long): List<EpgEntity> {
        val url = Urls.api(p.url, p.username, p.password, "get_simple_data_table", mapOf("stream_id" to c.streamId))
        val out = ArrayList<EpgEntity>()
        streamJson(url) { r ->
            if (r.peek() != android.util.JsonToken.BEGIN_OBJECT) {
                r.skipValue()
                return@streamJson
            }
            r.beginObject()
            while (r.hasNext()) {
                if (r.nextName() != "epg_listings" || r.peek() != android.util.JsonToken.BEGIN_ARRAY) {
                    r.skipValue()
                    continue
                }
                Json.forEachRecord(r) { rec ->
                    val start = rec["start_timestamp"]?.toLongOrNull()?.times(1000L) ?: return@forEachRecord
                    val stop = rec["stop_timestamp"]?.toLongOrNull()?.times(1000L) ?: return@forEachRecord
                    val flag = rec["has_archive"]
                    if (flag != null && flag != "1" && !flag.equals("true", true)) return@forEachRecord
                    if (start >= now || stop < from) return@forEachRecord
                    val title = b64(rec["title"]).ifBlank { return@forEachRecord }
                    out.add(EpgEntity(p.id, c.epgKey.ifBlank { "xc:" + c.streamId }, start, stop, title, b64(rec["description"]).ifBlank { null }))
                }
            }
            r.endObject()
        }
        return out
    }

    private fun b64(raw: String?): String {
        if (raw.isNullOrBlank()) return ""
        return try {
            String(android.util.Base64.decode(raw, android.util.Base64.DEFAULT), Charsets.UTF_8).trim()
        } catch (_: IllegalArgumentException) {
            raw.trim()
        }
    }

    // ------------------------------------------------------------------ Abspielen

    fun liveItem(p: ProfileEntity, c: ChannelEntity, format: String): PlayItem {
        val url = c.directUrl ?: Urls.join(p.url, "live", p.username, p.password, "${c.streamId}.$format")
        return PlayItem(
            kind = PlayKind.LIVE,
            id = c.streamId,
            title = c.name,
            url = url,
            image = c.logo,
            number = c.num,
            epgKey = c.epgKey,
        )
    }

    /** Archiv-URL einer vergangenen Sendung. Null, wenn sich keine URL bauen lässt. */
    fun catchupItem(p: ProfileEntity, c: ChannelEntity, programme: EpgEntity, format: String): PlayItem? {
        if (c.archiveDays <= 0) return null
        val xtream = p.type == ProfileType.XTREAM
        val url = Catchup.url(
            mode = if (xtream) "xc" else c.catchupMode,
            source = if (xtream) "" else c.catchupSource,
            liveUrl = c.directUrl,
            server = if (xtream) p.url else null,
            username = if (xtream) p.username else null,
            password = if (xtream) p.password else null,
            streamId = c.streamId,
            ext = format.ifBlank { "ts" },
            startMs = programme.start,
            stopMs = programme.stop,
        ) ?: return null
        return PlayItem(
            kind = PlayKind.LIVE,
            id = c.streamId,
            title = c.name,
            subtitle = programme.title,
            url = url,
            image = c.logo,
            number = c.num,
            epgKey = c.epgKey,
            catchup = true,
        )
    }

    fun movieItem(p: ProfileEntity, m: MovieEntity): PlayItem {
        val ext = m.ext.lowercase()
        val url = m.directUrl ?: Urls.join(p.url, "movie", p.username, p.password, "${m.streamId}.$ext")
        return PlayItem(
            kind = PlayKind.MOVIE,
            id = m.streamId,
            title = m.name,
            url = url,
            image = m.poster,
            ext = ext,
        )
    }

    fun episodeItem(p: ProfileEntity, s: SeriesEntity, e: EpisodeInfo): PlayItem {
        val ext = e.ext.lowercase()
        val url = e.directUrl?.takeIf { it.isNotBlank() }
            ?: Urls.join(p.url, "series", p.username, p.password, "${e.id}.$ext")
        return PlayItem(
            kind = PlayKind.EPISODE,
            id = e.id,
            title = s.name,
            subtitle = "S%02d E%02d · %s".format(e.season, e.number, e.title),
            url = url,
            image = e.image ?: s.poster,
            parentId = s.seriesId,
            season = e.season,
            episode = e.number,
            ext = ext,
        )
    }

    suspend fun movieDetail(profileId: Long, id: String): MovieDetail? {
        val p = db.profiles().get(profileId) ?: return null
        if (p.type != ProfileType.XTREAM) return null
        return try { xtream.movieDetail(p, id) } catch (e: CancellationException) { throw e } catch (e: Exception) { null }
    }

    suspend fun seriesDetail(profileId: Long, id: String): SeriesDetail? {
        val key = "$profileId:$id"
        synchronized(seriesCache) { seriesCache[key] }?.let { return it }
        val p = db.profiles().get(profileId) ?: return null
        val d = if (p.type == ProfileType.XTREAM) {
            try { xtream.seriesDetail(p, id) } catch (e: CancellationException) { throw e } catch (e: Exception) { null }
        } else {
            withContext(Dispatchers.IO) { M3uSeriesStore.load(db, profileId, id) }
        }
        if (d != null) synchronized(seriesCache) { seriesCache[key] = d }
        return d
    }

    // ------------------------------------------------------------------ Fortschritt

    fun observeProgress(profileId: Long, type: String, id: String): Flow<ProgressEntity?> =
        db.user().observeProgress(profileId, type, id)

    fun observeSeriesProgress(profileId: Long, seriesId: String): Flow<List<ProgressEntity>> =
        db.user().observeSeriesProgress(profileId, seriesId)

    fun continueWatching(profileId: Long): Flow<List<ProgressEntity>> = db.user().continueWatching(profileId)

    suspend fun progress(profileId: Long, kind: PlayKind, id: String): ProgressEntity? =
        db.user().progress(profileId, kind.name, id)

    suspend fun lastLive(profileId: Long): ProgressEntity? = db.user().lastLive(profileId)

    suspend fun saveProgress(profileId: Long, item: PlayItem, positionMs: Long, durationMs: Long) {
        // Fast fertig: eigener Wert, nicht 0. 0 bleibt „nur geöffnet / noch nicht angefangen“.
        val finished = durationMs > 0 && positionMs > durationMs * 95 / 100
        val pos = when {
            item.kind == PlayKind.LIVE -> 0L
            finished -> WatchPos.COMPLETED_MS
            else -> positionMs.coerceAtLeast(0)
        }
        db.user().putProgress(
            ProgressEntity(
                profileId = profileId,
                type = item.kind.name,
                itemId = item.id,
                title = item.subtitle?.let { "${item.title} – $it" } ?: item.title,
                poster = item.image,
                positionMs = pos,
                durationMs = durationMs.coerceAtLeast(0),
                updatedAt = System.currentTimeMillis(),
                parentId = item.parentId,
                season = item.season,
                episode = item.episode,
                ext = item.ext,
            ),
        )
    }

    // ------------------------------------------------------------------ Synchronisierung

    /** Beim App-Start: erstes Laden oder Auffrischen im Hintergrund, vorhandene Daten bleiben sichtbar. */
    fun startupRefresh(profileId: Long, epgAuto: Boolean) {
        scope.launch {
            val p = db.profiles().get(profileId) ?: return@launch
            val age = System.currentTimeMillis() - p.lastSync
            if (p.lastSync == 0L || age > 12 * 3_600_000L) sync(profileId)
            if (epgAuto) epg.launchRefreshIfStale(profileId, 6 * 3_600_000L)
        }
    }

    fun launchSync(profileId: Long) {
        scope.launch { sync(profileId) }
    }

    suspend fun sync(profileId: Long) {
        if (!syncMutex.tryLock()) return
        try {
            val p = db.profiles().get(profileId) ?: return
            _sync.value = SyncState(true, "Anmeldung …", 0.01f)
            val report: (String, Float) -> Unit = { stage, f -> _sync.value = SyncState(true, stage, f) }
            if (p.type == ProfileType.XTREAM) {
                val auth = xtream.authenticate(p)
                if (!auth.ok) {
                    _sync.value = SyncState(error = auth.message)
                    return
                }
                syncXtream(p, report)
            } else {
                syncM3u(p, report)
            }
            db.profiles().setSync(p.id, System.currentTimeMillis())
            epg.rematch(p.id)
            _sync.value = SyncState(false, "Fertig", 1f)
        } catch (e: CancellationException) {
            _sync.value = SyncState()
            throw e
        } catch (e: Exception) {
            _sync.value = SyncState(error = Net.friendly(e))
        } finally {
            syncMutex.unlock()
        }
    }

    private suspend fun <T> streamJson(url: String, block: suspend (JsonReader) -> T): T =
        withContext(Dispatchers.IO) {
            http.get(url).use { body ->
                JsonReader(InputStreamReader(body.byteStream(), Charsets.UTF_8).buffered(64 * 1024)).use { r ->
                    r.isLenient = true
                    block(r)
                }
            }
        }

    private suspend fun syncCategories(p: ProfileEntity, action: String, type: String, token: Long) {
        val list = ArrayList<CategoryEntity>()
        streamJson(Urls.api(p.url, p.username, p.password, action)) { r ->
            Json.forEachRecord(r) { rec ->
                val id = rec["category_id"] ?: return@forEachRecord
                list.add(CategoryEntity(p.id, type, id, rec["category_name"].orEmpty().ifBlank { "Kategorie $id" }, list.size, token))
            }
        }
        db.content().upsertCategories(list)
        db.content().pruneCategories(p.id, type, token)
    }

    private suspend fun syncXtream(p: ProfileEntity, report: (String, Float) -> Unit) {
        val token = System.currentTimeMillis()
        val c = db.content()
        var okSections = 0
        var firstError: Exception? = null

        // ---- Live ----
        try {
            report("Live-Kategorien", 0.03f)
            syncCategories(p, "get_live_categories", CatType.LIVE, token)
            report("Live-Sender", 0.08f)
            val batch = ArrayList<ChannelEntity>(BATCH)
            var count = 0
            streamJson(Urls.api(p.url, p.username, p.password, "get_live_streams")) { r ->
                Json.forEachRecord(r) { rec ->
                    val id = rec["stream_id"] ?: return@forEachRecord
                    count++
                    val archiveDays = Catchup.xtreamDays(rec["tv_archive"], rec["tv_archive_duration"])
                    batch.add(
                        ChannelEntity(
                            profileId = p.id,
                            streamId = id,
                            num = rec["num"]?.toIntOrNull() ?: count,
                            name = rec["name"].orEmpty().ifBlank { "Sender $id" }.trim(),
                            logo = rec["stream_icon"]?.takeIf { it.isNotBlank() },
                            categoryId = rec["category_id"] ?: "0",
                            epgKey = rec["epg_channel_id"].orEmpty().trim().lowercase(),
                            directUrl = rec["direct_source"]?.takeIf { it.isNotBlank() },
                            sync = token,
                            archiveDays = archiveDays,
                            catchupMode = if (archiveDays > 0) "xc" else "",
                        ),
                    )
                    if (batch.size >= BATCH) {
                        c.upsertChannels(ArrayList(batch))
                        batch.clear()
                        report("Live-Sender: $count", 0.08f + 0.22f * (count / 20000f).coerceAtMost(1f))
                    }
                }
            }
            if (batch.isNotEmpty()) c.upsertChannels(ArrayList(batch))
            c.pruneChannels(p.id, token)
            okSections++
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            firstError = firstError ?: e
        }

        // ---- Filme ----
        try {
            report("Film-Kategorien", 0.35f)
            syncCategories(p, "get_vod_categories", CatType.MOVIE, token)
            report("Filme", 0.4f)
            val batch = ArrayList<MovieEntity>(BATCH)
            var count = 0
            streamJson(Urls.api(p.url, p.username, p.password, "get_vod_streams")) { r ->
                Json.forEachRecord(r) { rec ->
                    val id = rec["stream_id"] ?: return@forEachRecord
                    count++
                    batch.add(
                        MovieEntity(
                            profileId = p.id,
                            streamId = id,
                            name = rec["name"].orEmpty().ifBlank { "Film $id" }.trim(),
                            poster = rec["stream_icon"]?.takeIf { it.isNotBlank() },
                            categoryId = rec["category_id"] ?: "0",
                            rating = rec["rating"]?.toDoubleOrNull() ?: 0.0,
                            year = rec["year"]?.takeIf { it.isNotBlank() },
                            added = rec["added"]?.toLongOrNull() ?: 0L,
                            ext = (rec["container_extension"]?.takeIf { it.isNotBlank() } ?: "mp4").lowercase(),
                            directUrl = rec["direct_source"]?.takeIf { it.isNotBlank() },
                            sync = token,
                        ),
                    )
                    if (batch.size >= BATCH) {
                        c.upsertMovies(ArrayList(batch))
                        batch.clear()
                        report("Filme: $count", 0.4f + 0.25f * (count / 30000f).coerceAtMost(1f))
                    }
                }
            }
            if (batch.isNotEmpty()) c.upsertMovies(ArrayList(batch))
            c.pruneMovies(p.id, token)
            okSections++
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            firstError = firstError ?: e
        }

        // ---- Serien ----
        try {
            report("Serien-Kategorien", 0.7f)
            syncCategories(p, "get_series_categories", CatType.SERIES, token)
            report("Serien", 0.75f)
            val batch = ArrayList<SeriesEntity>(BATCH)
            var count = 0
            streamJson(Urls.api(p.url, p.username, p.password, "get_series")) { r ->
                Json.forEachRecord(r) { rec ->
                    val id = rec["series_id"] ?: return@forEachRecord
                    count++
                    batch.add(
                        SeriesEntity(
                            profileId = p.id,
                            seriesId = id,
                            name = rec["name"].orEmpty().ifBlank { "Serie $id" }.trim(),
                            poster = rec["cover"]?.takeIf { it.isNotBlank() },
                            categoryId = rec["category_id"] ?: "0",
                            rating = rec["rating"]?.toDoubleOrNull() ?: 0.0,
                            year = (rec["releaseDate"] ?: rec["release_date"])?.take(4)?.takeIf { it.isNotBlank() },
                            genre = rec["genre"]?.takeIf { it.isNotBlank() },
                            plot = rec["plot"]?.takeIf { it.isNotBlank() },
                            added = rec["last_modified"]?.toLongOrNull() ?: 0L,
                            sync = token,
                        ),
                    )
                    if (batch.size >= BATCH) {
                        c.upsertSeries(ArrayList(batch))
                        batch.clear()
                        report("Serien: $count", 0.75f + 0.2f * (count / 10000f).coerceAtMost(1f))
                    }
                }
            }
            if (batch.isNotEmpty()) c.upsertSeries(ArrayList(batch))
            c.pruneSeries(p.id, token)
            okSections++
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            firstError = firstError ?: e
        }

        if (okSections == 0 && firstError != null) throw firstError
    }

    private suspend fun syncM3u(p: ProfileEntity, report: (String, Float) -> Unit) {
        val token = System.currentTimeMillis()
        val c = db.content()
        report("Playlist wird geladen", 0.05f)

        val liveGroups = LinkedHashMap<String, Int>()
        val movieGroups = LinkedHashMap<String, Int>()
        val channels = ArrayList<ChannelEntity>(BATCH)
        val movies = ArrayList<MovieEntity>(BATCH)
        var counter = 0
        var number = 0
        var foundEpg: String? = null
        val m3uSeries = M3uSeriesCollector()

        withContext(Dispatchers.IO) {
            http.get(p.url).use { body ->
                BufferedReader(InputStreamReader(body.byteStream(), Charsets.UTF_8), 64 * 1024).use { br ->
                    val parser = M3uParser()
                    for (e in parser.entries(br)) {
                        counter++
                        val lower = e.url.lowercase()
                        val id = UUID.nameUUIDFromBytes(e.url.toByteArray()).toString().replace("-", "")
                        if ("/series/" in lower) {
                            m3uSeries.add(e)
                        } else if ("/movie/" in lower) {
                            movieGroups.getOrPut(e.group) { movieGroups.size }
                            movies.add(
                                MovieEntity(
                                    profileId = p.id,
                                    streamId = id,
                                    name = e.name,
                                    poster = e.logo,
                                    categoryId = e.group,
                                    rating = 0.0,
                                    year = null,
                                    added = 0L,
                                    ext = e.url.substringBefore('?').substringAfterLast('.', "mp4").take(5).lowercase(),
                                    directUrl = e.url,
                                    sync = token,
                                ),
                            )
                        } else {
                            liveGroups.getOrPut(e.group) { liveGroups.size }
                            number++
                            channels.add(
                                ChannelEntity(
                                    profileId = p.id,
                                    streamId = id,
                                    num = e.number ?: number,
                                    name = e.name,
                                    logo = e.logo,
                                    categoryId = e.group,
                                    epgKey = e.tvgId.trim().lowercase(),
                                    directUrl = e.url,
                                    sync = token,
                                    archiveDays = e.archiveDays,
                                    catchupMode = e.catchupMode,
                                    catchupSource = e.catchupSource,
                                ),
                            )
                        }
                        if (channels.size >= BATCH) {
                            c.upsertChannels(ArrayList(channels))
                            channels.clear()
                        }
                        if (movies.size >= BATCH) {
                            c.upsertMovies(ArrayList(movies))
                            movies.clear()
                        }
                        if (counter % 2000 == 0) report("Einträge: $counter", 0.1f + 0.7f * (counter / 30000f).coerceAtMost(1f))
                    }
                    foundEpg = parser.epgUrl
                }
            }
        }
        if (channels.isNotEmpty()) c.upsertChannels(ArrayList(channels))
        if (movies.isNotEmpty()) c.upsertMovies(ArrayList(movies))
        c.pruneChannels(p.id, token)
        c.pruneMovies(p.id, token)

        c.upsertCategories(liveGroups.entries.map { CategoryEntity(p.id, CatType.LIVE, it.key, it.key, it.value, token) })
        c.pruneCategories(p.id, CatType.LIVE, token)
        c.upsertCategories(movieGroups.entries.map { CategoryEntity(p.id, CatType.MOVIE, it.key, it.key, it.value, token) })
        c.pruneCategories(p.id, CatType.MOVIE, token)
        m3uSeries.finish()
        for (chunk in m3uSeries.entities(p.id, token).chunked(BATCH)) {
            c.upsertSeries(chunk)
        }
        c.pruneSeries(p.id, token)
        val seriesCats = m3uSeries.categories(p.id, token)
        if (seriesCats.isNotEmpty()) c.upsertCategories(seriesCats)
        c.pruneCategories(p.id, CatType.SERIES, token)
        withContext(Dispatchers.IO) { M3uSeriesStore.replace(db, p.id, m3uSeries.details()) }
        synchronized(seriesCache) {
            val prefix = "${p.id}:"
            seriesCache.keys.filter { it.startsWith(prefix) }.forEach { seriesCache.remove(it) }
        }

        val epgFromHeader = foundEpg
        if (!epgFromHeader.isNullOrBlank() && p.epgUrl.isBlank()) {
            db.profiles().update(p.copy(epgUrl = epgFromHeader))
        }
    }

    private companion object {
        const val BATCH = 500
    }
}
