package de.dgstudios.iptvstream.core.vm

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import de.dgstudios.iptvstream.core.container
import de.dgstudios.iptvstream.core.data.Cat
import de.dgstudios.iptvstream.core.data.LiveRequest
import de.dgstudios.iptvstream.core.data.NowNext
import de.dgstudios.iptvstream.core.data.PlayItem
import de.dgstudios.iptvstream.core.data.PlayKind
import de.dgstudios.iptvstream.core.data.VodRequest
import de.dgstudios.iptvstream.core.data.db.ChannelEntity
import de.dgstudios.iptvstream.core.data.db.EpgEntity
import de.dgstudios.iptvstream.core.data.db.ProfileEntity
import de.dgstudios.iptvstream.core.player.PlayerController
import de.dgstudios.iptvstream.core.settings.AppSettings
import de.dgstudios.iptvstream.core.settings.SettingsStore
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class PlayerUi(
    val ready: Boolean = false,
    val noRequest: Boolean = false,
    val item: PlayItem? = null,
    val nowNext: NowNext? = null,
    val canPrev: Boolean = false,
    val canNext: Boolean = false,
    /** > 0: Countdown bis zur nächsten Episode läuft. */
    val nextOfferSeconds: Int = 0,
    val nextTitle: String? = null,
    val toast: String? = null,
    val isFavorite: Boolean = false,
    val settings: AppSettings = AppSettings(),
    /** Sender hat ein Archiv. Der Eintrag erscheint nur dann. */
    val archive: Boolean = false,
    /** Gerade läuft eine vergangene Sendung, nicht der Live-Rand. */
    val catchingUp: Boolean = false,
)

/**
 * Steuert eine Wiedergabesitzung. Der Controller (und damit der ExoPlayer) lebt genau so lange
 * wie dieses ViewModel – beim Verlassen des Players wird alles geschlossen.
 */
class PlayerViewModel(app: Application) : AndroidViewModel(app) {
    private val c = app.container
    private val repo = c.repo

    private val _ui = MutableStateFlow(PlayerUi())
    val ui: StateFlow<PlayerUi> = _ui

    private val _controller = MutableStateFlow<PlayerController?>(null)
    val controller: StateFlow<PlayerController?> = _controller

    private var profile: ProfileEntity? = null
    private var settings: AppSettings = AppSettings()
    private var isLive = false
    private var channels: List<ChannelEntity> = emptyList()
    private var vodItems: List<PlayItem> = emptyList()
    private var index = 0
    private var favIds: Set<String> = emptySet()

    private var nowNextJob: Job? = null
    private var autoNextJob: Job? = null
    private var toastJob: Job? = null
    /** Laufendes Umschalten inkl. Fortschritt-Lesen, damit ein langsamer Zap load() nicht nachholt. */
    private var playJob: Job? = null

    init {
        viewModelScope.launch { start() }
    }

    private val size: Int get() = if (isLive) channels.size else vodItems.size

    private fun itemAt(i: Int): PlayItem {
        val p = profile!!
        return if (isLive) repo.liveItem(p, channels[i], settings.liveFormat) else vodItems[i]
    }

    private suspend fun start() {
        val req = c.session.request
        val p = c.active.first { it.loaded }.profile
        if (req == null || p == null) {
            _ui.value = PlayerUi(noRequest = true)
            return
        }
        profile = p
        settings = c.settings.current()

        when (req) {
            is LiveRequest -> {
                isLive = true
                channels = repo.channels(p.id, req.categoryId).first()
                index = channels.indexOfFirst { it.streamId == req.channelId }
                if (index < 0) {
                    val ch = repo.channel(p.id, req.channelId)
                    channels = listOfNotNull(ch)
                    index = 0
                }
            }
            is VodRequest -> {
                isLive = false
                vodItems = req.items
                index = req.index.coerceIn(0, (req.items.size - 1).coerceAtLeast(0))
            }
        }
        if (size == 0) {
            _ui.value = PlayerUi(noRequest = true)
            return
        }

        val ctrl = PlayerController(getApplication(), settings, c.okHttp, viewModelScope) { item, pos, dur ->
            c.appScope.launch { repo.saveProgress(p.id, item, pos, dur) }
        }
        _controller.value = ctrl
        _ui.update { it.copy(settings = settings) }

        if (isLive) {
            viewModelScope.launch {
                repo.favoriteIds(p.id, "LIVE").collect { ids ->
                    favIds = ids.toSet()
                    _ui.update { u -> u.copy(isFavorite = u.item?.id in favIds) }
                }
            }
        }
        viewModelScope.launch {
            ctrl.state.map { it.ended }.distinctUntilChanged().collect { ended ->
                if (ended) onEnded()
            }
        }
        playIndex(index, (req as? VodRequest)?.fromStart == true)
    }

    private fun playIndex(i: Int, fromStart: Boolean = false) {
        if (profile == null || _controller.value == null) return
        if (i !in 0 until size) return
        launchPlay { playLoaded(i, fromStart) }
    }

    /** Bricht jedes laufende Umschalten ab, bevor ein neues load() starten kann. */
    private fun launchPlay(block: suspend kotlinx.coroutines.CoroutineScope.() -> Unit) {
        autoNextJob?.cancel()
        nowNextJob?.cancel()
        playJob?.cancel()
        playJob = viewModelScope.launch(block = block)
    }

    private suspend fun playLoaded(i: Int, fromStart: Boolean) {
        val p = profile ?: return
        val ctrl = _controller.value ?: return
        if (i !in 0 until size) return
        index = i
        val item = itemAt(i)
        val archive = isLive && (channels.getOrNull(i)?.archiveDays ?: 0) > 0
        var startMs = 0L
        if (item.kind != PlayKind.LIVE && !fromStart) {
            val pr = repo.progress(p.id, item.kind, item.id)
            coroutineContext.ensureActive()
            if (pr != null && pr.positionMs > 10_000) startMs = (pr.positionMs - 2_000).coerceAtLeast(0)
        }
        coroutineContext.ensureActive()
        ctrl.load(item, startMs)
        coroutineContext.ensureActive()
        _ui.update {
            it.copy(
                ready = true,
                item = item,
                nowNext = null,
                canPrev = if (isLive) size > 1 else index > 0,
                canNext = if (isLive) size > 1 else index < size - 1,
                nextOfferSeconds = 0,
                nextTitle = null,
                isFavorite = item.id in favIds,
                archive = archive,
                catchingUp = false,
            )
        }
        if (item.kind == PlayKind.LIVE) {
            repo.saveProgress(p.id, item, 0, 0)
            c.settings.set(SettingsStore.lastChannelKey(p.id), item.id)
            nowNextJob = viewModelScope.launch {
                while (isActive) {
                    val nn = repo.nowNext(p.id, item.epgKey)
                    _ui.update { u -> if (u.item?.id == item.id) u.copy(nowNext = nn) else u }
                    delay(30_000)
                }
            }
        }
    }

    // ------------------------------------------------------------------ Bedienung

    fun next() {
        if (size == 0) return
        if (isLive) playIndex((index + 1) % size)
        else if (index < size - 1) playIndex(index + 1)
    }

    fun prev() {
        if (size == 0) return
        if (isLive) playIndex((index - 1 + size) % size)
        else if (index > 0) playIndex(index - 1)
    }

    /** Sender über Zahleneingabe aufrufen. */
    fun zapNumber(n: Int) {
        val p = profile ?: return
        if (!isLive) return
        launchPlay {
            val ch = repo.channelByNumber(p.id, n)
            coroutineContext.ensureActive()
            if (ch == null) {
                showToast("Sender $n nicht gefunden")
                return@launchPlay
            }
            var i = channels.indexOfFirst { it.streamId == ch.streamId }
            if (i < 0) {
                channels = repo.channels(p.id, Cat.ALL).first()
                coroutineContext.ensureActive()
                i = channels.indexOfFirst { it.streamId == ch.streamId }
            }
            if (i >= 0) playLoaded(i, false)
        }
    }

    suspend fun archiveProgrammes(): List<EpgEntity> {
        val p = profile ?: return emptyList()
        val ch = channels.getOrNull(index) ?: return emptyList()
        return repo.archiveProgrammes(p.id, ch)
    }

    /** Spielt eine vergangene Sendung. False, wenn keine Archiv-URL gebaut werden konnte. */
    fun playCatchup(programme: EpgEntity): Boolean {
        val p = profile ?: return false
        if (!isLive) return false
        val ch = channels.getOrNull(index) ?: return false
        val ctrl = _controller.value ?: return false
        val item = repo.catchupItem(p, ch, programme, settings.liveFormat)
        if (item == null) {
            showToast("Diese Sendung ist im Archiv nicht verfügbar")
            return false
        }
        launchPlay {
            ctrl.load(item, 0)
            _ui.update {
                it.copy(
                    ready = true,
                    item = item,
                    canPrev = size > 1,
                    canNext = size > 1,
                    nextOfferSeconds = 0,
                    nextTitle = null,
                    isFavorite = item.id in favIds,
                    archive = true,
                    catchingUp = true,
                )
            }
        }
        return true
    }

    fun returnToLive() {
        if (!isLive) return
        playIndex(index)
    }

    fun toggleFavorite() {
        val p = profile ?: return
        val item = _ui.value.item ?: return
        if (item.kind != PlayKind.LIVE) return
        viewModelScope.launch { repo.toggleFavorite(p.id, "LIVE", item.id) }
    }

    fun setResize(mode: String) {
        _controller.value?.setResize(mode)
        viewModelScope.launch { c.settings.set("resize", mode) }
    }

    fun cancelNextOffer() {
        autoNextJob?.cancel()
        _ui.update { it.copy(nextOfferSeconds = 0) }
    }

    fun playNextNow() {
        autoNextJob?.cancel()
        next()
    }

    private fun onEnded() {
        val item = _ui.value.item ?: return
        if (item.kind != PlayKind.EPISODE || index >= size - 1) return
        val nextItem = itemAt(index + 1)
        autoNextJob?.cancel()
        autoNextJob = viewModelScope.launch {
            for (s in 8 downTo 1) {
                _ui.update { it.copy(nextOfferSeconds = s, nextTitle = nextItem.subtitle ?: nextItem.title) }
                delay(1_000)
            }
            _ui.update { it.copy(nextOfferSeconds = 0) }
            next()
        }
    }

    private fun showToast(text: String) {
        _ui.update { it.copy(toast = text) }
        toastJob?.cancel()
        toastJob = viewModelScope.launch {
            delay(2_500)
            _ui.update { u -> if (u.toast == text) u.copy(toast = null) else u }
        }
    }

    fun onBackground() {
        _controller.value?.onBackground()
    }

    fun onForeground() {
        _controller.value?.onForeground()
    }

    override fun onCleared() {
        _controller.value?.release()
        _controller.value = null
        super.onCleared()
    }
}
