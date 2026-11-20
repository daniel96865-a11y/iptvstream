package de.dgstudios.iptvstream.core.vm

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import de.dgstudios.iptvstream.core.container
import de.dgstudios.iptvstream.core.data.Cat
import de.dgstudios.iptvstream.core.data.ListOrder
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

    private data class CatPrefs(val all: List<CategoryEntity>, val order: List<String>, val hidden: Set<String>)

    private val catPrefs: StateFlow<CatPrefs> = profileId.filterNotNull()
        .flatMapLatest { pid ->
            combine(repo.categories(pid, catType), c.settings.flow) { cats, prefs ->
                CatPrefs(
                    all = cats,
                    order = ListOrder.decode(prefs.raw.get(SettingsStore.catOrderKey(pid, catType))),
                    hidden = ListOrder.decode(prefs.raw.get(SettingsStore.catHiddenKey(pid, catType))).toSet(),
                )
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CatPrefs(emptyList(), emptyList(), emptySet()))

    val categories: StateFlow<List<CategoryEntity>> = catPrefs
        .map { ListOrder.apply(it.all, { c -> c.id }, it.order, it.hidden) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Ausgeblendete Kategorien, zum Wieder-Einblenden. */
    val hiddenCategories: StateFlow<List<CategoryEntity>> = catPrefs
        .map { prefs -> prefs.all.filter { it.id in prefs.hidden } }
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
        val changed = index != scrollIndex
        scrollIndex = index
        scrollOffset = offset
        // Nur bei neuer Zeile speichern/vorladen, nicht in jedem Frame des Scrollens.
        if (!changed) return
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

    private fun isFixedCat(id: String) = id == Cat.ALL || id == Cat.FAV || id == Cat.RECENT

    private suspend fun mutateCatOrder(transform: (List<String>) -> List<String>) {
        val pid = profileId.value ?: return
        val key = SettingsStore.catOrderKey(pid, catType)
        val current = categories.value.map { it.id }
        val saved = ListOrder.decode(c.settings.getRaw(key))
        val base = if (saved.isEmpty()) current else ListOrder.apply(current, { it }, saved, emptySet())
        c.settings.set(key, ListOrder.encode(transform(base)))
    }

    /** Kategorie eine Position nach oben (−1) oder unten (+1) schieben. */
    fun moveCategory(id: String, delta: Int) {
        if (isFixedCat(id)) return
        viewModelScope.launch { mutateCatOrder { ListOrder.move(it, id, delta) } }
    }

    fun moveCategoryToStart(id: String) {
        if (isFixedCat(id)) return
        viewModelScope.launch { mutateCatOrder { ListOrder.moveToStart(it, id) } }
    }

    fun moveCategoryToEnd(id: String) {
        if (isFixedCat(id)) return
        viewModelScope.launch { mutateCatOrder { ListOrder.moveToEnd(it, id) } }
    }

    fun sortCategoriesByName() {
        viewModelScope.launch {
            val labels = categories.value.associate { it.id to it.name }
            mutateCatOrder { ListOrder.sortBy(it) { id -> labels[id] ?: id } }
        }
    }

    fun hideCategory(id: String) {
        if (isFixedCat(id)) return
        val pid = profileId.value ?: return
        viewModelScope.launch {
            val key = SettingsStore.catHiddenKey(pid, catType)
            val hidden = ListOrder.decode(c.settings.getRaw(key)).toMutableList()
            if (id !in hidden) hidden += id
            c.settings.set(key, ListOrder.encode(hidden))
            if (selectedCategory.value == id) selectCategory(Cat.ALL)
        }
    }

    fun unhideCategory(id: String) {
        val pid = profileId.value ?: return
        viewModelScope.launch {
            val key = SettingsStore.catHiddenKey(pid, catType)
            c.settings.set(key, ListOrder.encode(ListOrder.decode(c.settings.getRaw(key)).filter { it != id }))
        }
    }

    fun resetListOrder() {
        val pid = profileId.value ?: return
        viewModelScope.launch {
            c.settings.remove(SettingsStore.catOrderKey(pid, catType))
            c.settings.remove(SettingsStore.catHiddenKey(pid, catType))
        }
    }
}

class LiveViewModel(app: Application) :
    BrowseViewModel<ChannelEntity>(app, "live", CatType.LIVE, "LIVE") {

    override fun itemsFlow(profileId: Long, category: String): Flow<List<ChannelEntity>> =
        combine(
            repo.channels(profileId, category),
            c.settings.flow.map { prefs ->
                ListOrder.decode(prefs.raw.get(SettingsStore.channelOrderKey(profileId, category))) to
                    ListOrder.decode(prefs.raw.get(SettingsStore.channelHiddenKey(profileId))).toSet()
            },
        ) { list, pair -> ListOrder.apply(list, { it.streamId }, pair.first, pair.second) }

    /** Ausgeblendete Sender (aller Kategorien), zum Wieder-Einblenden. */
    val hiddenChannels: StateFlow<List<ChannelEntity>> = profileId.filterNotNull()
        .flatMapLatest { pid ->
            combine(
                repo.channels(pid, Cat.ALL),
                c.settings.flow.map { ListOrder.decode(it.raw.get(SettingsStore.channelHiddenKey(pid))).toSet() },
            ) { list, hidden -> list.filter { it.streamId in hidden } }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Zuletzt gesehene Sender für "Weiterschauen" oben in Live TV. */
    val recent: StateFlow<List<ChannelEntity>> = profileId.filterNotNull()
        .flatMapLatest { repo.channels(it, Cat.RECENT) }
        .map { it.take(2) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

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

    private suspend fun mutateChannelOrder(transform: (List<String>) -> List<String>) {
        val pid = profileId.value ?: return
        val cat = selectedCategory.value
        val key = SettingsStore.channelOrderKey(pid, cat)
        val current = items.value.map { it.streamId }
        val saved = ListOrder.decode(c.settings.getRaw(key))
        val base = if (saved.isEmpty()) current else ListOrder.apply(current, { it }, saved, emptySet())
        c.settings.set(key, ListOrder.encode(transform(base)))
    }

    fun moveChannel(ch: ChannelEntity, delta: Int) {
        viewModelScope.launch { mutateChannelOrder { ListOrder.move(it, ch.streamId, delta) } }
    }

    fun moveChannelToStart(ch: ChannelEntity) {
        viewModelScope.launch { mutateChannelOrder { ListOrder.moveToStart(it, ch.streamId) } }
    }

    fun moveChannelToEnd(ch: ChannelEntity) {
        viewModelScope.launch { mutateChannelOrder { ListOrder.moveToEnd(it, ch.streamId) } }
    }

    fun sortChannelsByName() {
        viewModelScope.launch {
            val labels = items.value.associate { it.streamId to it.name }
            mutateChannelOrder { ListOrder.sortBy(it) { id -> labels[id] ?: id } }
        }
    }

    fun hideChannel(ch: ChannelEntity) {
        val pid = profileId.value ?: return
        viewModelScope.launch {
            val key = SettingsStore.channelHiddenKey(pid)
            val hidden = ListOrder.decode(c.settings.getRaw(key)).toMutableList()
            if (ch.streamId !in hidden) hidden += ch.streamId
            c.settings.set(key, ListOrder.encode(hidden))
        }
    }

    fun unhideChannel(id: String) {
        val pid = profileId.value ?: return
        viewModelScope.launch {
            val key = SettingsStore.channelHiddenKey(pid)
            c.settings.set(key, ListOrder.encode(ListOrder.decode(c.settings.getRaw(key)).filter { it != id }))
        }
    }

    fun resetChannelOrder() {
        val pid = profileId.value ?: return
        viewModelScope.launch {
            c.settings.remove(SettingsStore.channelOrderKey(pid, selectedCategory.value))
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
