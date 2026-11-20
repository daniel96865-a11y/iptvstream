package de.dgstudios.iptvstream.core.vm

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import de.dgstudios.iptvstream.core.container
import de.dgstudios.iptvstream.core.data.Cat
import de.dgstudios.iptvstream.core.data.EpgState
import de.dgstudios.iptvstream.core.data.LiveRequest
import de.dgstudios.iptvstream.core.data.SyncState
import de.dgstudios.iptvstream.core.data.db.ProfileEntity
import de.dgstudios.iptvstream.core.data.db.ProfileType
import de.dgstudios.iptvstream.core.data.FreeTv
import de.dgstudios.iptvstream.core.settings.AppSettings
import de.dgstudios.iptvstream.core.settings.SettingsStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** App-weiter Zustand: Einstellungen, Profile, Synchronisierung und automatischer Start. */
class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val c = app.container

    val settings: StateFlow<AppSettings?> = c.settings.flow
        .map<AppSettings, AppSettings?> { it }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val active = c.active
    val profiles: StateFlow<List<ProfileEntity>> =
        c.repo.profiles().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val sync: StateFlow<SyncState> = c.repo.syncState
    val epg: StateFlow<EpgState> = c.epg.state

    private val _autoStart = MutableStateFlow<LiveRequest?>(null)
    val autoStart: StateFlow<LiveRequest?> = _autoStart
    private var startedFor: Long? = null

    init {
        viewModelScope.launch {
            c.active.filter { it.loaded }.collect { a ->
                val p = a.profile ?: return@collect
                if (startedFor == p.id) return@collect
                startedFor = p.id
                val s = c.settings.current()
                c.repo.startupRefresh(p.id, s.epgAuto)
                val channel = when (s.startMode) {
                    "last" -> c.repo.lastLive(p.id)?.let { c.repo.channel(p.id, it.itemId) }
                    "fixed" -> c.settings.getRaw(SettingsStore.fixedChannelKey(p.id))?.let { c.repo.channel(p.id, it) }
                    else -> null
                }
                if (channel != null) _autoStart.value = LiveRequest(p.id, Cat.ALL, channel.streamId)
                if (p.type != ProfileType.FREE) {
                    c.settings.set(SettingsStore.LAST_PROVIDER_PROFILE, p.id.toString())
                }
            }
        }
        viewModelScope.launch { ensureFreeProfile() }
    }

    fun consumeAutoStart() {
        _autoStart.value = null
    }

    fun set(key: String, value: String) {
        viewModelScope.launch { c.settings.set(key, value) }
    }

    fun selectProfile(id: Long) {
        viewModelScope.launch { c.settings.set(SettingsStore.ACTIVE_PROFILE, id.toString()) }
    }

    /** Prüft die Zugangsdaten, speichert das Profil, macht es aktiv und lädt die Inhalte. */
    fun saveProfile(p: ProfileEntity, onDone: (String?) -> Unit) {
        viewModelScope.launch { onDone(importProfile(p)) }
    }

    /** Wie [saveProfile], aber wartend. Für die Übertragung vom Handy an den Fernseher. */
    suspend fun importProfile(p: ProfileEntity): String? {
        val error = c.repo.testProfile(p)
        if (error != null) return error
        val id = c.repo.saveProfile(p)
        c.settings.set(SettingsStore.ACTIVE_PROFILE, id.toString())
        c.repo.launchSync(id)
        return null
    }

    fun deleteProfile(id: Long) {
        viewModelScope.launch { c.repo.deleteProfile(id) }
    }

    fun reloadContent() {
        val id = c.active.value.profile?.id ?: return
        c.repo.launchSync(id)
    }

    fun refreshEpg() {
        val id = c.active.value.profile?.id ?: return
        c.epg.launchRefresh(id)
    }

    /** Löscht die selbst gewählte Reihenfolge/Ausblendungen des aktiven Profils (Live). */
    fun resetLiveOrder() {
        val id = c.active.value.profile?.id ?: return
        viewModelScope.launch {
            val raw = c.settings.current().raw
            c.settings.remove(SettingsStore.catOrderKey(id, "LIVE"))
            c.settings.remove(SettingsStore.catHiddenKey(id, "LIVE"))
            c.settings.remove(SettingsStore.channelHiddenKey(id))
            val prefix = SettingsStore.channelOrderPrefix(id)
            raw.keys.filter { it.startsWith(prefix) }.forEach { c.settings.remove(it) }
        }
    }

    /**
     * Legt einmalig das Profil „Freie Sender“ mit Deutsch und Italienisch an,
     * ohne das aktive Anbieter-Profil zu wechseln.
     */
    suspend fun ensureFreeProfile(): Long {
        val existing = c.repo.allProfiles().firstOrNull { it.type == ProfileType.FREE }
        if (existing != null) return existing.id
        val id = c.repo.saveProfile(
            ProfileEntity(
                name = FreeTv.PROFILE_NAME,
                type = ProfileType.FREE,
                url = FreeTv.DEFAULT_LANGUAGES,
            ),
        )
        c.repo.launchSync(id)
        return id
    }

    /** True, wenn gerade die Freien Sender aktiv sind. */
    fun isFreeActive(): Boolean = c.active.value.profile?.type == ProfileType.FREE

    /** Wechselt mit einem Tipp zwischen Anbieter und Freien Sendern. */
    fun switchLiveSource(wantFree: Boolean) {
        viewModelScope.launch {
            val all = c.repo.allProfiles()
            if (wantFree) {
                val cur = c.active.value.profile
                if (cur != null && cur.type != ProfileType.FREE) {
                    c.settings.set(SettingsStore.LAST_PROVIDER_PROFILE, cur.id.toString())
                }
                val freeId = ensureFreeProfile()
                c.settings.set(SettingsStore.ACTIVE_PROFILE, freeId.toString())
            } else {
                val last = c.settings.getRaw(SettingsStore.LAST_PROVIDER_PROFILE)?.toLongOrNull()
                val provider = all.firstOrNull { it.id == last && it.type != ProfileType.FREE }
                    ?: all.firstOrNull { it.type != ProfileType.FREE }
                if (provider != null) {
                    c.settings.set(SettingsStore.ACTIVE_PROFILE, provider.id.toString())
                }
            }
        }
    }

}

