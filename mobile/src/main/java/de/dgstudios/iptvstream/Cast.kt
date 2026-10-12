package de.dgstudios.iptvstream

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.mediarouter.media.MediaControlIntent
import androidx.mediarouter.media.MediaRouteSelector
import androidx.mediarouter.media.MediaRouter
import com.google.android.gms.cast.CastMediaControlIntent
import com.google.android.gms.cast.MediaInfo
import com.google.android.gms.cast.MediaLoadRequestData
import com.google.android.gms.cast.MediaMetadata
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.CastOptions
import com.google.android.gms.cast.framework.CastSession
import com.google.android.gms.cast.framework.OptionsProvider
import com.google.android.gms.cast.framework.SessionManagerListener
import de.dgstudios.iptvstream.core.data.PlayItem
import de.dgstudios.iptvstream.core.data.PlayKind
import de.dgstudios.iptvstream.core.util.Urls
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Chromecast-Sender fürs Handy: Standard-Empfänger (Chromecast, Google TV, Android TV mit
 * eingebautem Chromecast). Die Geräteauswahl ist bewusst eine eigene Compose-Auswahl im
 * Glas-Look, damit kein AppCompat-Dialog nötig ist.
 */
class CastOptionsProvider : OptionsProvider {
    override fun getCastOptions(context: Context): CastOptions =
        CastOptions.Builder()
            .setReceiverApplicationId(CastMediaControlIntent.DEFAULT_MEDIA_RECEIVER_APPLICATION_ID)
            .build()

    override fun getAdditionalSessionProviders(context: Context)
        : List<com.google.android.gms.cast.framework.SessionProvider>? = null
}

object CastHub {
    data class Device(val id: String, val name: String, val route: MediaRouter.RouteInfo)

    data class UiState(
        val ready: Boolean = false,
        val devices: List<Device> = emptyList(),
        val connecting: Boolean = false,
        val deviceName: String? = null,
        val casting: Boolean = false,
        val error: String? = null,
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    val selector: MediaRouteSelector by lazy {
        MediaRouteSelector.Builder()
            .addControlCategory(CastMediaControlIntent.categoryForCast(CastMediaControlIntent.DEFAULT_MEDIA_RECEIVER_APPLICATION_ID))
            .addControlCategory(MediaControlIntent.CATEGORY_LIVE_VIDEO)
            .addControlCategory(MediaControlIntent.CATEGORY_REMOTE_PLAYBACK)
            .build()
    }

    private var app: Context? = null
    private var router: MediaRouter? = null
    private var castContext: CastContext? = null
    private var refs = 0

    private val main = Handler(Looper.getMainLooper())

    private val sessionListener = object : SessionManagerListener<CastSession> {
        override fun onSessionStarted(session: CastSession, sessionId: String) = onStarted(session)
        override fun onSessionResumed(session: CastSession, wasSuspended: Boolean) = onStarted(session)
        override fun onSessionEnded(session: CastSession, error: Int) = onEnded()
        override fun onSessionEnding(session: CastSession) = Unit
        override fun onSessionResumeFailed(session: CastSession, error: Int) {
            _state.update { it.copy(connecting = false, error = "Verbindung fehlgeschlagen ($error)") }
        }
        override fun onSessionStartFailed(session: CastSession, error: Int) {
            _state.update { it.copy(connecting = false, error = "Verbindung fehlgeschlagen ($error)") }
        }
        override fun onSessionResuming(session: CastSession, sessionId: String) {
            _state.update { it.copy(connecting = true, error = null) }
        }
        override fun onSessionStarting(session: CastSession) {
            _state.update { it.copy(connecting = true, error = null) }
        }
        override fun onSessionSuspended(session: CastSession, reason: Int) = onEnded()

        private fun onStarted(session: CastSession) {
            val name = try {
                session.castDevice?.friendlyName
            } catch (e: Exception) {
                null
            }
            _state.update { it.copy(connecting = false, casting = true, deviceName = name, error = null) }
        }

        private fun onEnded() {
            _state.update { it.copy(connecting = false, casting = false, deviceName = null) }
        }
    }

    private val routeCallback = object : MediaRouter.Callback() {
        override fun onRouteAdded(router: MediaRouter, route: MediaRouter.RouteInfo) = refresh()
        override fun onRouteRemoved(router: MediaRouter, route: MediaRouter.RouteInfo) = refresh()
        override fun onRouteChanged(router: MediaRouter, route: MediaRouter.RouteInfo) = refresh()
        override fun onProviderAdded(router: MediaRouter, provider: MediaRouter.ProviderInfo) = refresh()
        override fun onProviderRemoved(router: MediaRouter, provider: MediaRouter.ProviderInfo) = refresh()
    }

    /** Startet die Gerätesuche. Mehrfachaufrufe sind gezählt; [shutdown] baut wieder ab. */
    @Synchronized
    fun ensure(context: Context) {
        refs++
        if (app != null) {
            refresh()
            return
        }
        val c = context.applicationContext
        app = c
        try {
            val r = MediaRouter.getInstance(c)
            router = r
            r.addCallback(selector, routeCallback, MediaRouter.CALLBACK_FLAG_REQUEST_DISCOVERY)
        } catch (e: Exception) {
            _state.update { it.copy(error = "Chromecast nicht verfügbar") }
            return
        }
        try {
            val cc = CastContext.getSharedInstance(c)
            main.post {
                if (app == null) return@post
                castContext = cc
                try {
                    cc.sessionManager.addSessionManagerListener(sessionListener, CastSession::class.java)
                    val current = cc.sessionManager.currentCastSession
                    if (current != null && current.isConnected) {
                        val name = try {
                            current.castDevice?.friendlyName
                        } catch (e: Exception) {
                            null
                        }
                        _state.update { it.copy(ready = true, casting = true, deviceName = name) }
                    } else {
                        _state.update { it.copy(ready = true) }
                    }
                } catch (e: Exception) {
                    _state.update { it.copy(ready = true) }
                }
                refresh()
            }
        } catch (e: Exception) {
            val msg = e.message
            _state.update { it.copy(error = "Chromecast nicht verfügbar" + if (msg.isNullOrBlank()) "" else ": $msg") }
        }
        refresh()
    }

    @Synchronized
    fun shutdown() {
        if (refs > 0) refs--
        if (refs > 0) return
        try {
            router?.removeCallback(routeCallback)
        } catch (e: Exception) {
        }
        try {
            castContext?.sessionManager?.removeSessionManagerListener(sessionListener, CastSession::class.java)
        } catch (e: Exception) {
        }
        router = null
        castContext = null
        app = null
        _state.value = UiState()
    }

    fun connect(device: Device) {
        _state.update { it.copy(connecting = true, error = null) }
        try {
            router?.selectRoute(device.route)
        } catch (e: Exception) {
            _state.update { it.copy(connecting = false, error = "Verbindung fehlgeschlagen") }
        }
    }

    fun disconnect() {
        try {
            castContext?.sessionManager?.endCurrentSession(true)
        } catch (e: Exception) {
            _state.update { it.copy(casting = false, deviceName = null) }
        }
    }

    fun clearError() {
        _state.update { if (it.error != null) it.copy(error = null) else it }
    }

    /**
     * Lädt den laufenden Stream auf das verbundene Gerät. Live läuft dort am Live-Rand,
     * Filme/Serien ab [positionMs]. Gibt null zurück, wenn das Laden angestoßen wurde.
     */
    fun cast(item: PlayItem, positionMs: Long = 0L): String? {
        val session = try {
            castContext?.sessionManager?.currentCastSession
        } catch (e: Exception) {
            null
        } ?: return "Kein Gerät verbunden"
        if (!session.isConnected) return "Kein Gerät verbunden"
        val client = session.remoteMediaClient ?: return "Gerät antwortet nicht"
        val url = receiverUrl(item.url)
        val info = MediaInfo.Builder(url)
            .setStreamType(if (item.kind == PlayKind.LIVE && !item.catchup) MediaInfo.STREAM_TYPE_LIVE else MediaInfo.STREAM_TYPE_BUFFERED)
            .setContentType(contentType(url))
            .setMetadata(
                MediaMetadata(
                    if (item.kind == PlayKind.LIVE) MediaMetadata.MEDIA_TYPE_TV_SHOW else MediaMetadata.MEDIA_TYPE_MOVIE,
                ).apply {
                    putString(MediaMetadata.KEY_TITLE, item.title)
                    val sub = item.subtitle
                    if (!sub.isNullOrBlank()) putString(MediaMetadata.KEY_SUBTITLE, sub)
                    val img = item.image
                    if (!img.isNullOrBlank()) {
                        try {
                            addImage(com.google.android.gms.common.images.WebImage(Uri.parse(img)))
                        } catch (e: Exception) {
                        }
                    }
                },
            )
            .build()
        val startMs = if (item.kind == PlayKind.LIVE && !item.catchup) 0L else positionMs.coerceAtLeast(0L)
        return try {
            client.load(
                MediaLoadRequestData.Builder()
                    .setMediaInfo(info)
                    .setAutoplay(true)
                    .setCurrentTime(startMs)
                    .build(),
            )
            null
        } catch (e: Exception) {
            "Senden fehlgeschlagen"
        }
    }

    /**
     * Der Standard-Empfänger spielt MPEG-TS nicht ab. Wo die HLS-Variante bekannt ist
     * (Zahlsender-URL), wird sie genommen; sonst die Original-URL.
     */
    fun receiverUrl(url: String): String {
        if (Urls.isHls(url)) return url
        return Urls.alternateLiveContainer(url) ?: url
    }

    fun contentType(url: String): String {
        val path = url.lowercase().substringBefore('?').substringBefore('#')
        return when {
            path.endsWith(".m3u8") -> "application/x-mpegURL"
            path.endsWith(".mpd") -> "application/dash+xml"
            path.endsWith(".mp4") || path.endsWith(".m4v") -> "video/mp4"
            path.endsWith(".mkv") -> "video/x-matroska"
            path.endsWith(".webm") -> "video/webm"
            path.endsWith(".ts") -> "video/mp2t"
            Urls.isHls(url) -> "application/x-mpegURL"
            else -> "video/mp4"
        }
    }

    private fun refresh() {
        val r = router ?: return
        main.post {
            try {
                val routes = r.routes.filter { it.matchesSelector(selector) && it.id != r.defaultRoute?.id }
                _state.update {
                    it.copy(
                        devices = routes.map { route ->
                            Device(
                                id = route.id,
                                name = route.name ?: "Fernseher",
                                route = route,
                            )
                        }.distinctBy { it.id },
                    )
                }
            } catch (e: Exception) {
            }
        }
    }
}
