package de.dgstudios.iptvstream.core.vm

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import de.dgstudios.iptvstream.core.container
import de.dgstudios.iptvstream.core.data.EpisodeInfo
import de.dgstudios.iptvstream.core.data.MovieDetail
import de.dgstudios.iptvstream.core.data.PlayKind
import de.dgstudios.iptvstream.core.data.SeriesDetail
import de.dgstudios.iptvstream.core.data.VodRequest
import de.dgstudios.iptvstream.core.data.WatchPos
import de.dgstudios.iptvstream.core.data.db.MovieEntity
import de.dgstudios.iptvstream.core.data.db.ProfileEntity
import de.dgstudios.iptvstream.core.data.db.ProgressEntity
import de.dgstudios.iptvstream.core.data.db.SeriesEntity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class MovieDetailViewModel(app: Application, handle: SavedStateHandle) : AndroidViewModel(app) {
    private val c = app.container
    private val id: String = handle.get<String>("id").orEmpty()
    private var profile: ProfileEntity? = null

    val movie = MutableStateFlow<MovieEntity?>(null)
    val detail = MutableStateFlow<MovieDetail?>(null)
    val notFound = MutableStateFlow(false)

    private val profileId = c.active.map { it.profile?.id }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val progress: StateFlow<ProgressEntity?> = profileId.filterNotNull()
        .flatMapLatest { c.repo.observeProgress(it, PlayKind.MOVIE.name, id) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val favorite: StateFlow<Boolean> = profileId.filterNotNull()
        .flatMapLatest { c.repo.favoriteIds(it, "MOVIE") }
        .map { it.contains(id) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    init {
        viewModelScope.launch {
            val p = c.active.first { it.loaded }.profile
            if (p == null) {
                notFound.value = true
                return@launch
            }
            profile = p
            val m = c.repo.movie(p.id, id)
            if (m == null) {
                notFound.value = true
                return@launch
            }
            movie.value = m
            detail.value = c.repo.movieDetail(p.id, id)
        }
    }

    fun toggleFavorite() {
        val p = profile ?: return
        viewModelScope.launch { c.repo.toggleFavorite(p.id, "MOVIE", id) }
    }

    fun play(fromStart: Boolean) {
        val p = profile ?: return
        val m = movie.value ?: return
        c.session.request = VodRequest(p.id, listOf(c.repo.movieItem(p, m)), 0, fromStart)
    }
}

class SeriesDetailViewModel(app: Application, handle: SavedStateHandle) : AndroidViewModel(app) {
    private val c = app.container
    private val id: String = handle.get<String>("id").orEmpty()
    private var profile: ProfileEntity? = null

    val series = MutableStateFlow<SeriesEntity?>(null)
    val detail = MutableStateFlow<SeriesDetail?>(null)
    val loading = MutableStateFlow(true)
    val notFound = MutableStateFlow(false)
    val selectedSeason = MutableStateFlow<Int?>(null)

    private val profileId = c.active.map { it.profile?.id }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val progress: StateFlow<List<ProgressEntity>> = profileId.filterNotNull()
        .flatMapLatest { c.repo.observeSeriesProgress(it, id) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val favorite: StateFlow<Boolean> = profileId.filterNotNull()
        .flatMapLatest { c.repo.favoriteIds(it, "SERIES") }
        .map { it.contains(id) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    init {
        viewModelScope.launch {
            val p = c.active.first { it.loaded }.profile
            if (p == null) {
                notFound.value = true
                loading.value = false
                return@launch
            }
            profile = p
            val s = c.repo.seriesById(p.id, id)
            if (s == null) {
                notFound.value = true
                loading.value = false
                return@launch
            }
            series.value = s
            val d = c.repo.seriesDetail(p.id, id)
            detail.value = d
            selectedSeason.value = d?.seasons?.firstOrNull()?.number
            loading.value = false
        }
    }

    fun selectSeason(n: Int) {
        selectedSeason.value = n
    }

    fun toggleFavorite() {
        val p = profile ?: return
        viewModelScope.launch { c.repo.toggleFavorite(p.id, "SERIES", id) }
    }

    /**
     * Die Episode, mit der es weitergeht: die zuletzt angesehene (falls nicht fertig),
     * sonst die nächste danach, sonst die allererste.
     */
    fun continueEpisode(d: SeriesDetail, progress: List<ProgressEntity>): EpisodeInfo? {
        val all = d.allEpisodes
        if (all.isEmpty()) return null
        val last = progress.maxByOrNull { it.updatedAt } ?: return all.first()
        val idx = all.indexOfFirst { it.id == last.itemId }
        if (idx < 0) return all.first()
        return if (last.positionMs == WatchPos.COMPLETED_MS) all.getOrNull(idx + 1) ?: all[idx] else all[idx]
    }

    fun play(episode: EpisodeInfo, fromStart: Boolean = false) {
        val p = profile ?: return
        val s = series.value ?: return
        val d = detail.value ?: return
        val all = d.allEpisodes
        val items = all.map { c.repo.episodeItem(p, s, it) }
        val index = all.indexOfFirst { it.id == episode.id }.coerceAtLeast(0)
        c.session.request = VodRequest(p.id, items, index, fromStart)
    }
}
