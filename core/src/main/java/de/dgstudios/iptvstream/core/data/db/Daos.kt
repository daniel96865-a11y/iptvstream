package de.dgstudios.iptvstream.core.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface ProfileDao {
    @Query("SELECT * FROM profiles ORDER BY id")
    fun observeAll(): Flow<List<ProfileEntity>>

    @Query("SELECT * FROM profiles WHERE id = :id")
    suspend fun get(id: Long): ProfileEntity?

    @Query("SELECT * FROM profiles ORDER BY id")
    suspend fun all(): List<ProfileEntity>

    @Insert
    suspend fun insert(p: ProfileEntity): Long

    @Update
    suspend fun update(p: ProfileEntity)

    @Query("DELETE FROM profiles WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("UPDATE profiles SET lastSync = :t WHERE id = :id")
    suspend fun setSync(id: Long, t: Long)

    @Query("UPDATE profiles SET lastEpgSync = :t WHERE id = :id")
    suspend fun setEpgSync(id: Long, t: Long)
}

@Dao
interface ContentDao {
    // ---- Kategorien ----
    @Query("SELECT * FROM categories WHERE profileId = :p AND type = :t ORDER BY sort")
    fun categories(p: Long, t: String): Flow<List<CategoryEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertCategories(list: List<CategoryEntity>)

    @Query("DELETE FROM categories WHERE profileId = :p AND type = :t AND sync < :s")
    suspend fun pruneCategories(p: Long, t: String, s: Long)

    // ---- Live ----
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertChannels(list: List<ChannelEntity>)

    @Query("DELETE FROM channels WHERE profileId = :p AND sync < :s")
    suspend fun pruneChannels(p: Long, s: Long)

    @Query("SELECT * FROM channels WHERE profileId = :p ORDER BY num, name")
    fun allChannels(p: Long): Flow<List<ChannelEntity>>

    @Query("SELECT * FROM channels WHERE profileId = :p AND categoryId = :c ORDER BY num, name")
    fun channelsIn(p: Long, c: String): Flow<List<ChannelEntity>>

    @Query(
        "SELECT c.* FROM channels c INNER JOIN favorites f " +
            "ON f.profileId = c.profileId AND f.itemId = c.streamId AND f.type = 'LIVE' " +
            "WHERE c.profileId = :p ORDER BY c.num, c.name"
    )
    fun favoriteChannels(p: Long): Flow<List<ChannelEntity>>

    @Query(
        "SELECT c.* FROM channels c INNER JOIN progress g " +
            "ON g.profileId = c.profileId AND g.itemId = c.streamId AND g.type = 'LIVE' " +
            "WHERE c.profileId = :p ORDER BY g.updatedAt DESC LIMIT 50"
    )
    fun recentChannels(p: Long): Flow<List<ChannelEntity>>

    @Query("SELECT * FROM channels WHERE profileId = :p AND num = :n ORDER BY name LIMIT 1")
    suspend fun channelByNumber(p: Long, n: Int): ChannelEntity?

    @Query("SELECT * FROM channels WHERE profileId = :p AND streamId = :id LIMIT 1")
    suspend fun channel(p: Long, id: String): ChannelEntity?

    @Query("SELECT * FROM channels WHERE profileId = :p ORDER BY num, name LIMIT 1")
    suspend fun firstChannel(p: Long): ChannelEntity?

    @Query("SELECT * FROM channels WHERE profileId = :p AND name LIKE '%' || :q || '%' ORDER BY num LIMIT :limit")
    suspend fun searchChannels(p: Long, q: String, limit: Int): List<ChannelEntity>

    @Query("SELECT streamId, name, epgKey FROM channels WHERE profileId = :p")
    suspend fun channelEpgInfo(p: Long): List<ChannelEpgInfo>

    @Query("UPDATE channels SET epgKey = :key WHERE profileId = :p AND streamId = :id")
    suspend fun setEpgKey(p: Long, id: String, key: String)

    // ---- Filme ----
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertMovies(list: List<MovieEntity>)

    @Query("DELETE FROM movies WHERE profileId = :p AND sync < :s")
    suspend fun pruneMovies(p: Long, s: Long)

    @Query("SELECT * FROM movies WHERE profileId = :p ORDER BY added DESC, name")
    fun allMovies(p: Long): Flow<List<MovieEntity>>

    @Query("SELECT * FROM movies WHERE profileId = :p AND categoryId = :c ORDER BY added DESC, name")
    fun moviesIn(p: Long, c: String): Flow<List<MovieEntity>>

    @Query(
        "SELECT m.* FROM movies m INNER JOIN favorites f " +
            "ON f.profileId = m.profileId AND f.itemId = m.streamId AND f.type = 'MOVIE' " +
            "WHERE m.profileId = :p ORDER BY f.addedAt DESC"
    )
    fun favoriteMovies(p: Long): Flow<List<MovieEntity>>

    @Query(
        "SELECT m.* FROM movies m INNER JOIN progress g " +
            "ON g.profileId = m.profileId AND g.itemId = m.streamId AND g.type = 'MOVIE' " +
            "WHERE m.profileId = :p ORDER BY g.updatedAt DESC LIMIT 50"
    )
    fun recentMovies(p: Long): Flow<List<MovieEntity>>

    @Query("SELECT * FROM movies WHERE profileId = :p AND streamId = :id LIMIT 1")
    suspend fun movie(p: Long, id: String): MovieEntity?

    @Query("SELECT * FROM movies WHERE profileId = :p AND name LIKE '%' || :q || '%' ORDER BY name LIMIT :limit")
    suspend fun searchMovies(p: Long, q: String, limit: Int): List<MovieEntity>

    // ---- Serien ----
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSeries(list: List<SeriesEntity>)

    @Query("DELETE FROM series WHERE profileId = :p AND sync < :s")
    suspend fun pruneSeries(p: Long, s: Long)

    @Query("SELECT * FROM series WHERE profileId = :p ORDER BY added DESC, name")
    fun allSeries(p: Long): Flow<List<SeriesEntity>>

    @Query("SELECT * FROM series WHERE profileId = :p AND categoryId = :c ORDER BY added DESC, name")
    fun seriesIn(p: Long, c: String): Flow<List<SeriesEntity>>

    @Query(
        "SELECT s.* FROM series s INNER JOIN favorites f " +
            "ON f.profileId = s.profileId AND f.itemId = s.seriesId AND f.type = 'SERIES' " +
            "WHERE s.profileId = :p ORDER BY f.addedAt DESC"
    )
    fun favoriteSeries(p: Long): Flow<List<SeriesEntity>>

    @Query(
        "SELECT s.* FROM series s INNER JOIN " +
            "(SELECT parentId AS pid, MAX(updatedAt) AS u FROM progress " +
            "WHERE profileId = :p AND type = 'EPISODE' GROUP BY parentId) g " +
            "ON g.pid = s.seriesId WHERE s.profileId = :p ORDER BY g.u DESC LIMIT 50"
    )
    fun recentSeries(p: Long): Flow<List<SeriesEntity>>

    @Query("SELECT * FROM series WHERE profileId = :p AND seriesId = :id LIMIT 1")
    suspend fun series(p: Long, id: String): SeriesEntity?

    @Query("SELECT * FROM series WHERE profileId = :p AND name LIKE '%' || :q || '%' ORDER BY name LIMIT :limit")
    suspend fun searchSeries(p: Long, q: String, limit: Int): List<SeriesEntity>

    // ---- Aufräumen ----
    @Query("DELETE FROM categories WHERE profileId = :p")
    suspend fun clearCategories(p: Long)

    @Query("DELETE FROM channels WHERE profileId = :p")
    suspend fun clearChannels(p: Long)

    @Query("DELETE FROM movies WHERE profileId = :p")
    suspend fun clearMovies(p: Long)

    @Query("DELETE FROM series WHERE profileId = :p")
    suspend fun clearSeries(p: Long)
}

@Dao
interface UserDao {
    // ---- Favoriten ----
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun addFavorite(f: FavoriteEntity)

    @Query("DELETE FROM favorites WHERE profileId = :p AND type = :t AND itemId = :id")
    suspend fun removeFavorite(p: Long, t: String, id: String)

    @Query("SELECT COUNT(*) FROM favorites WHERE profileId = :p AND type = :t AND itemId = :id")
    suspend fun favoriteCount(p: Long, t: String, id: String): Int

    @Query("SELECT itemId FROM favorites WHERE profileId = :p AND type = :t")
    fun favoriteIds(p: Long, t: String): Flow<List<String>>

    @Query("DELETE FROM favorites WHERE profileId = :p")
    suspend fun clearFavorites(p: Long)

    // ---- Fortschritt / Zuletzt gesehen ----
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putProgress(p: ProgressEntity)

    @Query("SELECT * FROM progress WHERE profileId = :p AND type = :t AND itemId = :id LIMIT 1")
    suspend fun progress(p: Long, t: String, id: String): ProgressEntity?

    @Query("SELECT * FROM progress WHERE profileId = :p AND type = :t AND itemId = :id LIMIT 1")
    fun observeProgress(p: Long, t: String, id: String): Flow<ProgressEntity?>

    @Query("SELECT * FROM progress WHERE profileId = :p AND parentId = :sid AND type = 'EPISODE'")
    fun observeSeriesProgress(p: Long, sid: String): Flow<List<ProgressEntity>>

    @Query(
        "SELECT * FROM progress WHERE profileId = :p AND type IN ('MOVIE','EPISODE') " +
            "AND positionMs > 5000 ORDER BY updatedAt DESC LIMIT 30"
    )
    fun continueWatching(p: Long): Flow<List<ProgressEntity>>

    @Query("SELECT * FROM progress WHERE profileId = :p AND type = 'LIVE' ORDER BY updatedAt DESC LIMIT 1")
    suspend fun lastLive(p: Long): ProgressEntity?

    @Query("DELETE FROM progress WHERE profileId = :p")
    suspend fun clearProgress(p: Long)
}

@Dao
interface EpgDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(list: List<EpgEntity>)

    @Query("DELETE FROM epg WHERE profileId = :p AND sync < :s")
    suspend fun pruneBySync(p: Long, s: Long)

    @Query("DELETE FROM epg WHERE profileId = :p")
    suspend fun clear(p: Long)

    @Query("SELECT * FROM epg WHERE profileId = :p AND channelKey = :k AND stop > :now ORDER BY start LIMIT :n")
    suspend fun upcoming(p: Long, k: String, now: Long, n: Int): List<EpgEntity>

    @Query("SELECT * FROM epg WHERE profileId = :p AND channelKey = :k AND stop > :from AND start < :to ORDER BY start")
    suspend fun range(p: Long, k: String, from: Long, to: Long): List<EpgEntity>

    @Query("SELECT DISTINCT channelKey FROM epg WHERE profileId = :p")
    suspend fun keysWithData(p: Long): List<String>

    @Query("SELECT COUNT(*) FROM epg WHERE profileId = :p")
    suspend fun count(p: Long): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertNames(list: List<EpgNameEntity>)

    @Query("SELECT * FROM epg_names WHERE profileId = :p")
    suspend fun names(p: Long): List<EpgNameEntity>

    @Query("DELETE FROM epg_names WHERE profileId = :p")
    suspend fun clearNames(p: Long)
}
