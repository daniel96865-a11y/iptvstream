package de.dgstudios.iptvstream.core.vm

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import de.dgstudios.iptvstream.core.container
import de.dgstudios.iptvstream.core.data.LiveRequest
import de.dgstudios.iptvstream.core.data.Cat
import de.dgstudios.iptvstream.core.data.SearchResults
import de.dgstudios.iptvstream.core.data.db.ChannelEntity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn

class SearchViewModel(app: Application) : AndroidViewModel(app) {
    private val c = app.container

    val query = MutableStateFlow("")

    val results: StateFlow<SearchResults> = combine(
        c.active.map { it.profile?.id }.distinctUntilChanged(),
        query.debounce(250).distinctUntilChanged(),
    ) { p, q -> p to q }
        .mapLatest { (p, q) -> if (p == null) SearchResults() else c.repo.search(p, q) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SearchResults())

    fun setQuery(q: String) {
        query.value = q
    }

    /** Merkt den Sender für den Player (Suche läuft in der Kategorie "Alle"). */
    fun playChannel(ch: ChannelEntity) {
        c.session.request = LiveRequest(ch.profileId, Cat.ALL, ch.streamId)
    }
}
