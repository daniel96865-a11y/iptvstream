package de.dgstudios.iptvstream.core.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

object ProfileType {
    const val XTREAM = "XTREAM"
    const val M3U = "M3U"
}

object CatType {
    const val LIVE = "LIVE"
    const val MOVIE = "MOVIE"
    const val SERIES = "SERIES"
}

@Entity(tableName = "profiles")
data class ProfileEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val type: String,
    val url: String,
    val username: String = "",
    val password: String = "",
    val epgUrl: String = "",
    val lastSync: Long = 0,
    val lastEpgSync: Long = 0,
)

@Entity(tableName = "categories", primaryKeys = ["profileId", "type", "id"])
data class CategoryEntity(
    val profileId: Long,
    val type: String,
    val id: String,
    val name: String,
    val sort: Int,
    val sync: Long = 0,
)

@Entity(
    tableName = "channels",
    primaryKeys = ["profileId", "streamId"],
    indices = [Index("profileId", "categoryId"), Index("profileId", "num"), Index("profileId", "sync")],
)
data class ChannelEntity(
    val profileId: Long,
    val streamId: String,
    val num: Int,
    val name: String,
    val logo: String?,
    val categoryId: String,
    val epgKey: String,
    val directUrl: String?,
    val sync: Long = 0,
)

@Entity(
    tableName = "movies",
    primaryKeys = ["profileId", "streamId"],
    indices = [Index("profileId", "categoryId"), Index("profileId", "sync")],
)
data class MovieEntity(
    val profileId: Long,
    val streamId: String,
    val name: String,
    val poster: String?,
    val categoryId: String,
    val rating: Double,
    val year: String?,
    val added: Long,
    val ext: String,
    val directUrl: String?,
    val sync: Long = 0,
)

@Entity(
    tableName = "series",
    primaryKeys = ["profileId", "seriesId"],
    indices = [Index("profileId", "categoryId"), Index("profileId", "sync")],
)
data class SeriesEntity(
    val profileId: Long,
    val seriesId: String,
    val name: String,
    val poster: String?,
    val categoryId: String,
    val rating: Double,
    val year: String?,
    val genre: String?,
    val plot: String?,
    val added: Long,
    val sync: Long = 0,
)

@Entity(
    tableName = "epg",
    primaryKeys = ["profileId", "channelKey", "start"],
    indices = [Index("profileId", "channelKey", "stop"), Index("profileId", "sync")],
)
data class EpgEntity(
    val profileId: Long,
    val channelKey: String,
    val start: Long,
    val stop: Long,
    val title: String,
    val description: String?,
    val sync: Long = 0,
)

@Entity(tableName = "epg_names", primaryKeys = ["profileId", "normName"])
data class EpgNameEntity(
    val profileId: Long,
    val normName: String,
    val channelKey: String,
)

@Entity(tableName = "favorites", primaryKeys = ["profileId", "type", "itemId"])
data class FavoriteEntity(
    val profileId: Long,
    val type: String,
    val itemId: String,
    val addedAt: Long,
)

@Entity(tableName = "progress", primaryKeys = ["profileId", "type", "itemId"])
data class ProgressEntity(
    val profileId: Long,
    /** LIVE, MOVIE oder EPISODE */
    val type: String,
    val itemId: String,
    val title: String,
    val poster: String?,
    val positionMs: Long,
    val durationMs: Long,
    val updatedAt: Long,
    val parentId: String?,
    val season: Int,
    val episode: Int,
    val ext: String?,
)

/** Leichtgewichtige Projektion für das EPG-Matching. */
data class ChannelEpgInfo(
    val streamId: String,
    val name: String,
    val epgKey: String,
)
