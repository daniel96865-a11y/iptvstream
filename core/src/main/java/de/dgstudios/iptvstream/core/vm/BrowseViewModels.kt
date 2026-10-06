package de.dgstudios.iptvstream.core.vm

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import de.dgstudios.iptvstream.core.container
import de.dgstudios.iptvstream.core.data.Cat
import de.dgstudios.iptvstream.core.data.LiveRequest
import de.dgstudios.iptvstream.core.data.NowNext
import de.dgstudios.iptvstream.core.data.db.CatType
import de.dgstudios.iptvstream.core.data.db.CategoryEntity
import de.dgstudios.iptvstream.core.data.db.ChannelEntity
import de.dgstudios.iptvstream.core.data.db.MovieEntity
import de.dgstudios.iptvstream.core.data.db.SeriesEntity
import de.dgstudios.iptvstream.core.settings.SettingsStore
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Gemeinsame Logik für Live/Filme/Serien: Kategorien, gewählte Kategorie, Elemente,
 * Favoriten und gemerkte Listenposition (übersteht auch einen App-Neustart).
 */
abstract class BrowseViewModel<T>(
    app: Application,
    private val section: String,
    private val catType: String,
    private val favType: String,
) : AndroidViewModel(app) {
    protected val c = app.container
    protected val repo = c.repo

    val profileId: StateFlow<Long?> = c.active
        .map { it.profile?.id }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val categories: StateFlow<List<CategoryEntity>> = profileId.filterNotNull()
        .flatMapLatest { repo.categories(it, catType) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val selectedCategory = MutableStateFlow(Cat.ALL)

    protected abstract fun itemsFlow(profileId: Long, category: String): Flow<List<T>>

    val items: StateFlow<List<T>> = combine(profileId.filterNotNull(), selectedCategory) { p, cat -> p to cat }
        .flatMapLatest { (p, cat) -> itemsFlow(p, cat) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val favoriteIds: StateFlow<Set<String>> = profileId.filterNotNull()
        .flatMapLatest { repo.favoriteIds(it, favType) }
        .map<List<String>, Set<String>> { it.toSet() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    /** Wird true, sobald die gespeicherte Kategorie und Position gelesen wurden. */
    val restored = MutableStateFlow(false)

    var scrollIndex = 0
    var scrollOffset = 0
    var focusIndex = 0

    private var persistJob: Job? = null

    init {
        viewModelScope.launch {
            val pid = profileId.filterNotNull().first()
            val raw = c.settings.getRaw(SettingsStore.listPosKey(pid, section))
            if (raw != null) {
                val cat = raw.substringBefore('|')
                val idx = raw.substringAfter('|', "0").toIntOrNull() ?: 0
                if (cat.isNotEmpty()) selectedCategory.value = cat
                scrollIndex = idx
                focusIndex = idx
            }
            restored.value = true
        }
    }

    fun selectCategory(id: String) {
        if (selectedCategory.value == id) return
        selectedCategory.value = id
        scrollIndex = 0
        scrollOffset = 0
        focusIndex = 0
        persist()
    }

    fun onScroll(index: Int, offset: Int) {
        scrollIndex = index
        scrollOffset = offset
        persist()
    }

    private fun persist() {
        val pid = profileId.value ?: return
        persistJob?.cancel()
        persistJob = c.appScope.launch {
            delay(800)
            c.settings.set(SettingsStore.listPosKey(pid, section), "${selectedCategory.value}|$scrollIndex")
        }
    }

    fun toggleFavorite(id: String) {
        val pid = profileId.value ?: return
        viewModelScope.launch { repo.toggleFavorite(pid, favType, id) }
    }
}

class LiveViewModel(app: Application) :
    BrowseViewModel<ChannelEntity>(app, "live", CatType.LIVE, "LIVE") {

    override fun itemsFlow(profileId: Long, category: String): Flow<List<ChannelEntity>> =
        repo.channels(profileId, category)

    /** Merkt den Kontext für den Player und gibt den Request zurück. */
    fun play(ch: ChannelEntity): LiveRequest {
        val req = LiveRequest(ch.profileId, selectedCategory.value, ch.streamId)
        c.session.request = req
        return req
    }

    suspend fun nowNext(ch: ChannelEntity): NowNext = repo.nowNext(ch.profileId, ch.epgKey)

    suspend fun nowNextFor(epgKey: String): NowNext {
        val pid = profileId.value ?: return NowNext(null, null)
        return repo.nowNext(pid, epgKey)
    }

    /** Legt den Sender als Startsender fest und aktiviert "Festen Startsender öffnen". */
    fun setStartChannel(ch: ChannelEntity) {
        viewModelScope.launch {
            c.settings.set(SettingsStore.fixedChannelKey(ch.profileId), ch.streamId)
            c.settings.set("start_mode", "fixed")
        }
    }

    suspend fun channelByNumber(n: Int): ChannelEntity? {
        val pid = profileId.value ?: return null
        return repo.channelByNumber(pid, n)
    }
}

class MoviesViewModel(app: Application) :
    BrowseViewModel<MovieEntity>(app, "movies", CatType.MOVIE, "MOVIE") {

    override fun itemsFlow(profileId: Long, category: String): Flow<List<MovieEntity>> =
        repo.movies(profileId, category)
}

class SeriesViewModel(app: Application) :
    BrowseViewModel<SeriesEntity>(app, "series", CatType.SERIES, "SERIES") {

    override fun itemsFlow(profileId: Long, category: String): Flow<List<SeriesEntity>> =
        repo.series(profileId, category)
}
