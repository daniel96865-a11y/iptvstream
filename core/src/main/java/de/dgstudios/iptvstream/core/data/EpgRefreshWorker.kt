package de.dgstudios.iptvstream.core.data

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import de.dgstudios.iptvstream.core.AppContainer
import de.dgstudios.iptvstream.core.IptvApp
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

/** Zieht den Programmführer nach, solange die App zu ist. Die Einstellung „EPG automatisch“ gilt weiter. */
class EpgRefreshWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as? IptvApp ?: return Result.success()
        val c = app.container
        if (!c.settings.current().epgAuto) return Result.success()
        val active = c.active.first { it.loaded }
        val id = active.profile?.id ?: return Result.success()
        c.epg.refreshIfStale(id, EpgRefreshInterval.STALE_MS)
        return Result.success()
    }
}

/** Während die App offen ist, und über WorkManager, wenn sie geschlossen ist. */
internal object EpgAutoRefresh {
    private const val WORK_NAME = "epg-auto"

    fun install(context: Context, container: AppContainer) {
        val scope = container.appScope
        val appContext = context.applicationContext
        scope.launch {
            container.settings.flow.map { it.epgAuto }.distinctUntilChanged().collect { on ->
                val wm = WorkManager.getInstance(appContext)
                if (!on) {
                    wm.cancelUniqueWork(WORK_NAME)
                    return@collect
                }
                val request = PeriodicWorkRequestBuilder<EpgRefreshWorker>(6, TimeUnit.HOURS)
                    .setInitialDelay(15, TimeUnit.MINUTES)
                    .setConstraints(
                        Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build(),
                    )
                    .build()
                wm.enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
            }
        }
        scope.launch {
            while (isActive) {
                delay(EpgRefreshInterval.WATCH_MS)
                if (!container.settings.current().epgAuto) continue
                val id = container.active.value.profile?.id ?: continue
                container.epg.refreshIfStale(id, EpgRefreshInterval.STALE_MS)
            }
        }
    }
}
