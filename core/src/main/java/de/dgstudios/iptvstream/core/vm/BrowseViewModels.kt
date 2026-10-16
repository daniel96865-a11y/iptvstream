package de.dgstudios.iptvstream.core.vm

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import de.dgstudios.iptvstream.core.container
import de.dgstudios.iptvstream.core.data.Cat
import de.dgstudios.iptvstream.core.data.EpgGuide
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
import kotlinx.coroutines.flow.collectLatest
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

    /** Scroll der Kategorien-Schublade. Gilt nur für diese Sitzung, getrennt je Bereich. */
    var drawerIndex = 0
    var drawerOffset = 0

    private var persistJob: Job? = null
    private var persistProfile: Long? = null

    init {
        viewModelScope.launch {
            profileId.collectLatest { pid ->
                if (pid == null) {
                    restored.value = false
                    return@collectLatest
                }
                restored.value = false
                val raw = c.settings.getRaw(SettingsStore.listPosKey(pid, section))
                val saved = raw?.substringBefore('|')?.takeIf { it.isNotEmpty() }
                val idx = raw?.substringAfter('|', "0")?.toIntOrNull() ?: 0
                val cats = repo.categories(pid, catType).first()
                val cat = Cat.restoreCategory(saved, cats.map { it.id }.toSet())
                selectedCategory.value = cat
                val keepPos = cat == saved
                scrollIndex = if (keepPos) idx else 0
                scrollOffset = 0
                focusIndex = scrollIndex
                drawerIndex = drawerIndexFor(cat, cats)
                drawerOffset = 0
                restored.value = true
                repo.categories(pid, catType).collect { list ->
                    val current = selectedCategory.value
                    if (Cat.restoreCategory(current, list.map { it.id }.toSet()) != current) {
                        selectedCategory.value = Cat.ALL
                        scrollIndex = 0
                        scrollOffset = 0
                        focusIndex = 0
                        persist()
                    }
                }
            }
        }
    }

    fun selectCategory(id: String) {
        if (selectedCategory.value == id) return
        selectedCategory.value = id
        scrollIndex = 0
        scrollOffset = 0
        focusIndex = 0
        drawerIndex = drawerIndexFor(id)
        drawerOffset = 0
        persist()
    }

    fun onDrawerScroll(index: Int, offset: Int) {
        drawerIndex = index
        drawerOffset = offset
    }

    /** Alle, Favoriten und Zuletzt stehen vor den Playlist-Gruppen. */
    private fun drawerIndexFor(id: String, cats: List<CategoryEntity> = categories.value): Int = when (id) {
        Cat.ALL -> 0
        Cat.FAV -> 1
        Cat.RECENT -> 2
        else -> {
            val i = cats.indexOfFirst { it.id == id }
            if (i >= 0) i + 3 else 0
        }
    }

    fun onScroll(index: Int, offset: Int) {
        scrollIndex = index
        scrollOffset = offset
        persist()
        afterScroll(index)
    }

    /** Zusätzliche Arbeit nach dem Scrollen, zum Beispiel Programm vorab laden. */
    protected open fun afterScroll(index: Int) {}

    private fun persist() {
        val pid = profileId.value ?: return
        val snapshot = "${selectedCategory.value}|$scrollIndex"
        if (persistProfile == pid) persistJob?.cancel()
        persistProfile = pid
        persistJob = c.appScope.launch {
            delay(800)
            c.settings.set(SettingsStore.listPosKey(pid, section), snapshot)
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

    /** Wahr, solange der Player offen ist und die Liste danach wieder zum Sender zurückkehren soll. */
    var returnPending = false
        private set

    /** Liefert den zuletzt gespielten Sender (Index in [items]) einmalig nach der Rückkehr aus dem Player. */
    fun takeReturnTarget(items: List<ChannelEntity>): Int? {
        if (!returnPending || items.isEmpty()) return null
        returnPending = false
        val id = (c.session.request as? LiveRequest)?.channelId
        return ListReturn.indexOf(items.map { it.streamId }, id)
    }

    fun clearReturn() {
        returnPending = false
    }

    /** Merkt den Kontext für den Player und gibt den Request zurück. */
    fun play(ch: ChannelEntity): LiveRequest {
        returnPending = true
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

    private var warmJob: Job? = null

    init {
        viewModelScope.launch {
            combine(profileId.filterNotNull(), items) { pid, list -> pid to list }
                .collectLatest { (pid, list) -> warm(pid, list, scrollIndex) }
        }
    }

    override fun afterScroll(index: Int) {
        warmJob?.cancel()
        warmJob = viewModelScope.launch {
            delay(80)
            val pid = profileId.value ?: return@launch
            warm(pid, items.value, index)
        }
    }

    private suspend fun warm(pid: Long, list: List<ChannelEntity>, index: Int) {
        val (from, to) = EpgGuide.window(list.size, index)
        if (from >= to) return
        repo.warmGuide(pid, list.subList(from, to).map { it.epgKey })
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

/** Findet nach der Rückkehr aus dem Player die Position des zuletzt gespielten Senders. */
object ListReturn {
    fun indexOf(ids: List<String>, last: String?): Int? {
        if (last == null) return null
        val i = ids.indexOf(last)
        return if (i >= 0) i else null
    }
}
