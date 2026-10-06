package de.dgstudios.iptvstream.core.data

enum class PlayKind { LIVE, MOVIE, EPISODE }

/** Ein abspielbares Element (Sender, Film oder Episode). */
data class PlayItem(
    val kind: PlayKind,
    val id: String,
    val title: String,
    val url: String,
    val subtitle: String? = null,
    val image: String? = null,
    val parentId: String? = null,
    val season: Int = 0,
    val episode: Int = 0,
    val ext: String? = null,
    val number: Int = 0,
    val epgKey: String? = null,
)

sealed interface PlayRequest {
    val profileId: Long
}

/** Live-TV: Kontext ist die Kategorie, in der geschaltet wird. */
data class LiveRequest(
    override val profileId: Long,
    val categoryId: String,
    val channelId: String,
) : PlayRequest

/** Filme/Serien: feste Liste (bei Serien alle Episoden in Reihenfolge) mit Startindex. */
data class VodRequest(
    override val profileId: Long,
    val items: List<PlayItem>,
    val index: Int,
    val fromStart: Boolean = false,
) : PlayRequest

class PlaybackSession {
    @Volatile
    var request: PlayRequest? = null
}

data class EpisodeInfo(
    val id: String,
    val season: Int,
    val number: Int,
    val title: String,
    val ext: String,
    val plot: String?,
    val durationSec: Int,
    val image: String?,
)

data class Season(val number: Int, val episodes: List<EpisodeInfo>)

data class SeriesDetail(
    val plot: String?,
    val genre: String?,
    val year: String?,
    val rating: Double,
    val cast: String?,
    val backdrop: String?,
    val seasons: List<Season>,
) {
    val allEpisodes: List<EpisodeInfo> get() = seasons.flatMap { it.episodes }
}

data class MovieDetail(
    val plot: String?,
    val genre: String?,
    val year: String?,
    val durationMin: Int,
    val rating: Double,
    val cast: String?,
    val director: String?,
    val backdrop: String?,
)

/** Aktuelle und nächste Sendung eines Senders. */
data class NowNext(
    val now: de.dgstudios.iptvstream.core.data.db.EpgEntity?,
    val next: de.dgstudios.iptvstream.core.data.db.EpgEntity?,
) {
    fun progress(at: Long): Float {
        val n = now ?: return 0f
        val total = (n.stop - n.start).coerceAtLeast(1)
        return ((at - n.start).toFloat() / total).coerceIn(0f, 1f)
    }
}

data class SyncState(
    val running: Boolean = false,
    val stage: String = "",
    val fraction: Float = 0f,
    val error: String? = null,
)

data class EpgState(
    val running: Boolean = false,
    val fraction: Float = 0f,
    val message: String = "",
    val error: String? = null,
)
