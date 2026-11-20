package de.dgstudios.iptvstream.core.update

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest
import com.google.android.gms.tasks.Task
import com.google.android.play.core.appupdate.AppUpdateInfo
import com.google.android.play.core.appupdate.AppUpdateManager
import com.google.android.play.core.appupdate.AppUpdateManagerFactory
import com.google.android.play.core.appupdate.AppUpdateOptions
import com.google.android.play.core.install.InstallStateUpdatedListener
import com.google.android.play.core.install.model.ActivityResult
import com.google.android.play.core.install.model.AppUpdateType
import com.google.android.play.core.install.model.InstallStatus
import com.google.android.play.core.install.model.UpdateAvailability
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Aktion, die der sichtbare Bildschirm für den [UpdateChecker] ausführen muss. */
sealed interface UpdateAction {
    /** Google-Play-In-App-Update starten (Dialog von Play). */
    data class StartFlow(val info: AppUpdateInfo, val type: Int) : UpdateAction

    /** Seite der App im Play Store öffnen. */
    data object OpenStore : UpdateAction
}

/**
 * Play-Variante: Updates laufen über Google Play (In-App-Updates, Play Core). Gleicher Ablauf wie
 * bei der GitHub-Variante: Hinweis auf dem Startbildschirm, „Später“, „Jetzt aktualisieren“.
 *
 * - Handy/Tablet: flexibles Update (Download im Hintergrund, danach „Jetzt neu starten“),
 *   sofortiges Update nur, wenn Play das flexible nicht erlaubt oder die Priorität ≥ 4 ist.
 * - Android TV unterstützt In-App-Updates nicht (Play meldet „API nicht verfügbar“). Dann liest die App
 *   den öffentlichen Versions-Feed und zeigt den Hinweis „Im Play Store öffnen“.
 *
 * Es wird keine APK geladen und nichts installiert; die App enthält keinen eigenen Installer.
 */
class UpdateChecker(
    context: Context,
    private val http: OkHttpClient,
    private val scope: CoroutineScope,
    private val manager: AppUpdateManager = AppUpdateManagerFactory.create(context.applicationContext),
    private val feedLoader: (suspend () -> UpdateInfo)? = null,
) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val _prompt = MutableStateFlow<UpdatePrompt>(UpdatePrompt.Hidden)
    val prompt: StateFlow<UpdatePrompt> = _prompt

    private val _actions = MutableSharedFlow<UpdateAction>(extraBufferCapacity = 4)
    val actions: SharedFlow<UpdateAction> = _actions

    private var checkJob: Job? = null
    private var startVisible = false
    private var suppressOnThisVisit = false

    /** Der Nutzer hat „Jetzt aktualisieren“ gedrückt; nur dann zeigt der Fortschritt den Dialog. */
    private var showProgress = false
    private var pendingInfo: AppUpdateInfo? = null
    private var lastOffered: UpdateInfo? = null

    private val installListener = InstallStateUpdatedListener { state ->
        when (state.installStatus()) {
            InstallStatus.PENDING, InstallStatus.DOWNLOADING -> {
                if (!showProgress) return@InstallStateUpdatedListener
                val total = state.totalBytesToDownload()
                val fraction = if (total > 0) (state.bytesDownloaded().toFloat() / total).coerceIn(0f, 1f) else null
                val info = lastOffered ?: UpdateInfo(0, "", "", "", "")
                _prompt.value = UpdatePrompt.Offer(info, downloading = true, progress = fraction, needPermission = false)
            }
            InstallStatus.DOWNLOADED -> {
                showProgress = false
                if (!suppressOnThisVisit) _prompt.value = UpdatePrompt.ReadyToRestart
            }
            InstallStatus.INSTALLED -> _prompt.value = UpdatePrompt.Hidden
            InstallStatus.FAILED -> {
                showProgress = false
                _prompt.value = UpdatePrompt.Message("Das Update über Google Play ist fehlgeschlagen. Bitte versuch es später noch einmal.")
            }
            InstallStatus.CANCELED -> {
                showProgress = false
                _prompt.value = UpdatePrompt.Hidden
            }
            else -> Unit
        }
    }

    init {
        manager.registerListener(installListener)
    }

    fun maybeCheck() {
        val last = prefs.getLong(KEY_LAST, 0L)
        if (System.currentTimeMillis() - last < INTERVAL_MS) return
        check(manual = false, ignoreInterval = false)
    }

    /** Home wird angezeigt: sofort prüfen, ohne auf das Intervall zu warten. */
    fun onStartScreenVisible() {
        val entered = !startVisible
        startVisible = true
        if (!entered) return
        suppressOnThisVisit = false
        check(manual = false, ignoreInterval = true)
    }

    fun onStartScreenHidden() {
        startVisible = false
    }

    /** Zurück in die App, solange der Startbildschirm offen ist. */
    fun onStartScreenResume() {
        if (!startVisible || suppressOnThisVisit) return
        if (_prompt.value !is UpdatePrompt.Hidden) return
        check(manual = false, ignoreInterval = true)
    }

    fun onAppBackground() {
        suppressOnThisVisit = false
    }

    fun checkNow() {
        check(manual = true, ignoreInterval = true)
    }

    fun later() {
        if (_prompt.value !is UpdatePrompt.Hidden) suppressOnThisVisit = true
        showProgress = false
        _prompt.value = UpdatePrompt.Hidden
    }

    /** „Jetzt aktualisieren“ beziehungsweise „Jetzt neu starten“ beziehungsweise „Im Play Store öffnen“. */
    fun confirm() {
        when (val current = _prompt.value) {
            is UpdatePrompt.Offer -> {
                if (current.downloading) return
                if (current.viaStore) {
                    _actions.tryEmit(UpdateAction.OpenStore)
                    later()
                    return
                }
                val info = pendingInfo
                val type = info?.let(::chooseType)
                if (info == null || type == null) {
                    offerViaStore(current.info)
                    return
                }
                showProgress = true
                _prompt.value = current.copy(downloading = true, progress = null)
                _actions.tryEmit(UpdateAction.StartFlow(info, type))
            }
            UpdatePrompt.ReadyToRestart -> {
                try {
                    manager.completeUpdate()
                } catch (_: Exception) {
                    _prompt.value = UpdatePrompt.Message("Neustart nicht möglich. Bitte die App einmal schließen und neu öffnen.")
                }
            }
            else -> Unit
        }
    }

    /** Startet den Play-Dialog. Wird vom sichtbaren Bildschirm mit dem Launcher aufgerufen. */
    fun startFlow(action: UpdateAction.StartFlow, launcher: ActivityResultLauncher<IntentSenderRequest>) {
        val started = try {
            manager.startUpdateFlowForResult(
                action.info,
                launcher,
                AppUpdateOptions.newBuilder(action.type).build(),
            )
        } catch (_: Exception) {
            false
        }
        if (!started) offerViaStore(lastOffered ?: UpdateInfo(action.info.availableVersionCode(), "", "", "", ""))
    }

    /** Ergebnis des Play-Dialogs (Activity-Result-Code). */
    fun onFlowResult(resultCode: Int) {
        when (resultCode) {
            Activity.RESULT_OK -> Unit
            Activity.RESULT_CANCELED -> later()
            ActivityResult.RESULT_IN_APP_UPDATE_FAILED ->
                offerViaStore(lastOffered ?: UpdateInfo(0, "", "", "", ""))
            else -> later()
        }
    }

    /** Der Play Store lässt sich nicht öffnen. */
    fun storeUnavailable() {
        _prompt.value = UpdatePrompt.Message("Bitte öffne den Google Play Store und suche dort nach „IPTVstream“, um zu aktualisieren.")
    }

    private fun chooseType(info: AppUpdateInfo): Int? {
        val flexible = info.isUpdateTypeAllowed(AppUpdateType.FLEXIBLE)
        val immediate = info.isUpdateTypeAllowed(AppUpdateType.IMMEDIATE)
        return when {
            immediate && info.updatePriority() >= HIGH_PRIORITY -> AppUpdateType.IMMEDIATE
            flexible -> AppUpdateType.FLEXIBLE
            immediate -> AppUpdateType.IMMEDIATE
            else -> null
        }
    }

    private fun offerViaStore(info: UpdateInfo) {
        showProgress = false
        _prompt.value = UpdatePrompt.Offer(info, downloading = false, progress = null, needPermission = false, viaStore = true)
    }

    private fun check(manual: Boolean, ignoreInterval: Boolean) {
        val current = _prompt.value
        if (current is UpdatePrompt.Offer && current.downloading) return
        if (!manual && !ignoreInterval) {
            val last = prefs.getLong(KEY_LAST, 0L)
            if (System.currentTimeMillis() - last < INTERVAL_MS) return
        }
        if (!manual && checkJob?.isActive == true) return
        checkJob?.cancel()
        checkJob = scope.launch {
            try {
                val info = manager.appUpdateInfo.await()
                if (!isActive) return@launch
                prefs.edit().putLong(KEY_LAST, System.currentTimeMillis()).apply()
                handle(info, manual)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Zum Beispiel Android TV oder ohne Play Store: „API nicht verfügbar“.
                if (isActive) checkViaFeed(manual)
            }
        }
    }

    private fun handle(info: AppUpdateInfo, manual: Boolean) {
        val visible = startVisible && !suppressOnThisVisit
        if (info.installStatus() == InstallStatus.DOWNLOADED) {
            if (manual || visible) _prompt.value = UpdatePrompt.ReadyToRestart
            return
        }
        when (info.updateAvailability()) {
            UpdateAvailability.DEVELOPER_TRIGGERED_UPDATE_IN_PROGRESS -> {
                // Sofortiges Update läuft bereits: Play verlangt, es nach der Rückkehr fortzusetzen.
                if (info.isUpdateTypeAllowed(AppUpdateType.IMMEDIATE)) {
                    pendingInfo = info
                    _actions.tryEmit(UpdateAction.StartFlow(info, AppUpdateType.IMMEDIATE))
                }
            }
            UpdateAvailability.UPDATE_AVAILABLE -> {
                pendingInfo = info
                if (!manual && !visible) return
                val offered = UpdateInfo(info.availableVersionCode(), "", "", "", "")
                lastOffered = offered
                _prompt.value = UpdatePrompt.Offer(offered, downloading = false, progress = null, needPermission = false)
            }
            UpdateAvailability.UPDATE_NOT_AVAILABLE -> {
                if (manual) _prompt.value = UpdatePrompt.Message("IPTVstream ${installedVersionName(appContext)} ist bereits die aktuelle Version.")
            }
            else -> throw IllegalStateException("Play: Update-Status unbekannt")
        }
    }

    /** Fallback ohne In-App-Updates: öffentlicher Feed sagt, welche Version aktuell ist; installiert wird über den Play Store. */
    private suspend fun checkViaFeed(manual: Boolean) {
        try {
            val info = feedLoader?.invoke() ?: loadFeed()
            if (!currentCoroutineContext().isActive) return
            prefs.edit().putLong(KEY_LAST, System.currentTimeMillis()).apply()
            if (info.versionCode > installedVersionCode(appContext)) {
                if (!manual && (!startVisible || suppressOnThisVisit)) return
                lastOffered = info
                offerViaStore(info)
            } else if (manual) {
                _prompt.value = UpdatePrompt.Message("IPTVstream ${installedVersionName(appContext)} ist bereits die aktuelle Version.")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            if (manual) _prompt.value = UpdatePrompt.Message("Update konnte nicht geprüft werden.")
        }
    }

    private suspend fun loadFeed(): UpdateInfo = withContext(Dispatchers.IO) {
        val client = http.newBuilder().readTimeout(30, TimeUnit.SECONDS).build()
        val request = Request.Builder().url(feedUrl(appContext.packageName)).header("Cache-Control", "no-cache").build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("HTTP ${response.code}")
            parseFeed(response.body?.string().orEmpty())
        }
    }

    companion object {
        private const val PREFS = "iptvstream_update"
        private const val KEY_LAST = "last_check_ms"
        private const val INTERVAL_MS = 4L * 60L * 60L * 1000L
        private const val HIGH_PRIORITY = 4

        fun feedUrl(packageName: String): String {
            val file = if (packageName.endsWith(".tv")) "iptvstream-tv.json" else "iptvstream-mobile.json"
            return "https://raw.githubusercontent.com/daniel96865-a11y/iptvstream/main/docs/$file"
        }

        /** Aus dem Feed zählt nur die Versionsnummer; die APK-Adresse wird hier nie benutzt. */
        fun parseFeed(text: String): UpdateInfo {
            val o = JSONObject(text)
            val code = o.getInt("versionCode")
            val name = o.getString("versionName")
            if (code <= 0 || name.isBlank()) error("ungültiger Feed")
            return UpdateInfo(code, name, "", "", "")
        }

        /** Play-Store-Adressen für die Seite der App: erst der Play-Store-Link, dann die Webseite. */
        fun storeIntents(packageName: String): List<Intent> = listOf(
            Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$packageName")).setPackage("com.android.vending"),
            Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=$packageName")),
        )
    }
}

private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { cont ->
    addOnSuccessListener { if (cont.isActive) cont.resume(it) }
    addOnFailureListener { if (cont.isActive) cont.resumeWithException(it) }
    addOnCanceledListener { cont.cancel() }
}
