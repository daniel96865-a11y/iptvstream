package de.dgstudios.iptvstream.core

import android.app.Application
import android.content.Context
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import de.dgstudios.iptvstream.core.data.ContentRepository
import de.dgstudios.iptvstream.core.data.EpgManager
import de.dgstudios.iptvstream.core.data.PlaybackSession
import de.dgstudios.iptvstream.core.data.db.AppDatabase
import de.dgstudios.iptvstream.core.data.db.ProfileEntity
import de.dgstudios.iptvstream.core.data.remote.HttpService
import de.dgstudios.iptvstream.core.data.remote.Net
import de.dgstudios.iptvstream.core.data.remote.XtreamApi
import de.dgstudios.iptvstream.core.settings.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import okhttp3.OkHttpClient
import retrofit2.Retrofit

/** Das aktuell gewählte Profil; [loaded] ist erst true, wenn Profile und Einstellungen gelesen wurden. */
data class ActiveProfile(val loaded: Boolean, val profile: ProfileEntity?)

/** Einfacher Service-Locator; wird einmal in [IptvApp] erzeugt und von allen ViewModels genutzt. */
class AppContainer(context: Context) {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val db: AppDatabase = AppDatabase.build(context)
    val okHttp: OkHttpClient = Net.client()
    val http: HttpService = Retrofit.Builder()
        .baseUrl("http://localhost/")
        .client(okHttp)
        .build()
        .create(HttpService::class.java)
    val settings = SettingsStore(context.applicationContext)
    val xtream = XtreamApi(http)
    val epg = EpgManager(db, http, appScope)
    val repo = ContentRepository(db, http, xtream, epg, appScope)
    val session = PlaybackSession()

    val active: StateFlow<ActiveProfile> = combine(settings.flow, repo.profiles()) { s, profiles ->
        val wanted = s.raw[SettingsStore.ACTIVE_PROFILE]?.toLongOrNull()
        ActiveProfile(true, profiles.firstOrNull { it.id == wanted } ?: profiles.firstOrNull())
    }.stateIn(appScope, SharingStarted.Eagerly, ActiveProfile(false, null))
}

class IptvApp : Application(), ImageLoaderFactory {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }

    override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this)
        .memoryCache { MemoryCache.Builder(this).maxSizePercent(0.15).build() }
        .diskCache {
            DiskCache.Builder()
                .directory(cacheDir.resolve("images"))
                .maxSizeBytes(300L * 1024 * 1024)
                .build()
        }
        .okHttpClient(container.okHttp)
        .allowRgb565(true)
        .respectCacheHeaders(false)
        .crossfade(false)
        .build()
}

val Context.container: AppContainer
    get() = (applicationContext as IptvApp).container
