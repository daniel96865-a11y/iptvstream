package de.dgstudios.iptvstream.core.update

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.content.FileProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

data class UpdateInfo(
    val versionCode: Int,
    val versionName: String,
    val apkUrl: String,
    val changelog: String,
)

sealed interface UpdatePrompt {
    data object Hidden : UpdatePrompt

    data class Offer(
        val info: UpdateInfo,
        val downloading: Boolean,
        val progress: Float?,
        val needPermission: Boolean,
    ) : UpdatePrompt

    data class Message(val text: String) : UpdatePrompt
}

/**
 * Prüft den öffentlichen JSON-Feed und lädt bei Bedarf die APK.
 * Automatische Prüfung höchstens alle paar Stunden, manuell immer.
 */
class UpdateChecker(
    private val context: Context,
    private val http: OkHttpClient,
    private val scope: CoroutineScope,
) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val downloadClient = http.newBuilder()
        .readTimeout(2, TimeUnit.MINUTES)
        .build()

    private val _prompt = MutableStateFlow<UpdatePrompt>(UpdatePrompt.Hidden)
    val prompt: StateFlow<UpdatePrompt> = _prompt

    private val _installs = MutableSharedFlow<File>(extraBufferCapacity = 1)
    val installs: SharedFlow<File> = _installs

    private var checkJob: Job? = null
    private var downloadJob: Job? = null

    /** Startbildschirm ist sichtbar (Home mit den Haupt-Tabs). */
    private var startVisible = false

    /** „Später“ auf diesem Besuch; der nächste Start zeigt das Update wieder. */
    private var suppressOnThisVisit = false

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
        downloadJob?.cancel()
        _prompt.value = UpdatePrompt.Hidden
    }

    fun confirm() {
        val offer = _prompt.value as? UpdatePrompt.Offer ?: return
        if (offer.downloading) return
        val cached = apkFile()
        if (cached.exists() && cached.length() > 0L && prefs.getInt(KEY_CACHED_CODE, -1) == offer.info.versionCode) {
            requestInstall(offer.info)
            return
        }
        downloadJob?.cancel()
        downloadJob = scope.launch {
            try {
                _prompt.value = offer.copy(downloading = true, progress = null, needPermission = false)
                download(offer.info)
                prefs.edit().putInt(KEY_CACHED_CODE, offer.info.versionCode).apply()
                if (!isActive) return@launch
                _prompt.value = offer.copy(downloading = false, progress = null, needPermission = false)
                requestInstall(offer.info)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                if (_prompt.value is UpdatePrompt.Offer) {
                    _prompt.value = UpdatePrompt.Message("Update konnte nicht geladen werden.")
                }
            }
        }
    }

    fun onPermissionReturned() {
        val offer = _prompt.value as? UpdatePrompt.Offer ?: return
        if (canInstall(appContext)) {
            _prompt.value = offer.copy(needPermission = false)
            _installs.tryEmit(apkFile())
        }
    }

    fun installIntent(file: File): Intent {
        val uri = FileProvider.getUriForFile(appContext, "${appContext.packageName}.updates", file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val matches = appContext.packageManager.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
        for (match in matches) {
            appContext.grantUriPermission(
                match.activityInfo.packageName,
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        }
        return intent
    }

    fun permissionIntent(): Intent = Intent(
        android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
        Uri.parse("package:${appContext.packageName}"),
    )

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
                val info = fetchFeed()
                if (!isActive) return@launch
                prefs.edit().putLong(KEY_LAST, System.currentTimeMillis()).apply()
                val newer = info.versionCode > installedVersionCode(appContext)
                if (newer) {
                    if (!manual && (!startVisible || suppressOnThisVisit)) return@launch
                    _prompt.value = UpdatePrompt.Offer(info, downloading = false, progress = null, needPermission = false)
                } else if (manual) {
                    val name = installedVersionName(appContext)
                    _prompt.value = UpdatePrompt.Message("IPTVstream $name ist bereits die aktuelle Version.")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                if (manual && isActive) _prompt.value = UpdatePrompt.Message("Update konnte nicht geprüft werden.")
            }
        }
    }

    private fun requestInstall(info: UpdateInfo) {
        if (!canInstall(appContext)) {
            _prompt.value = UpdatePrompt.Offer(info, downloading = false, progress = null, needPermission = true)
            return
        }
        _installs.tryEmit(apkFile())
    }

    private suspend fun fetchFeed(): UpdateInfo = withContext(Dispatchers.IO) {
        val url = feedUrl(appContext.packageName)
        val request = Request.Builder().url(url).header("Cache-Control", "no-cache").build()
        downloadClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("HTTP ${response.code}")
            val body = response.body?.string().orEmpty()
            parseFeed(body)
        }
    }

    private suspend fun download(info: UpdateInfo): File = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(info.apkUrl).build()
        downloadClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("HTTP ${response.code}")
            val body = response.body ?: error("leer")
            val total = body.contentLength()
            val file = apkFile()
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, file.name + ".part")
            body.byteStream().use { input ->
                tmp.outputStream().use { output ->
                    val buf = ByteArray(16 * 1024)
                    var readTotal = 0L
                    var lastEmit = 0L
                    while (true) {
                        if (!isActive) break
                        val n = input.read(buf)
                        if (n < 0) break
                        output.write(buf, 0, n)
                        readTotal += n
                        val now = System.currentTimeMillis()
                        if (isActive && total > 0 && now - lastEmit > 80) {
                            lastEmit = now
                            val current = _prompt.value
                            if (current is UpdatePrompt.Offer) {
                                _prompt.value = current.copy(progress = (readTotal.toFloat() / total).coerceIn(0f, 1f))
                            }
                        }
                    }
                }
            }
            if (!isActive) {
                tmp.delete()
                throw CancellationException()
            }
            if (tmp.length() <= 0L) error("leer")
            if (file.exists()) file.delete()
            if (!tmp.renameTo(file)) {
                tmp.copyTo(file, overwrite = true)
                tmp.delete()
            }
            file
        }
    }

    private fun apkFile(): File = File(appContext.cacheDir, "updates/iptvstream-update.apk")

    companion object {
        private const val PREFS = "iptvstream_update"
        private const val KEY_LAST = "last_check_ms"
        private const val KEY_CACHED_CODE = "cached_version_code"
        private const val INTERVAL_MS = 4L * 60L * 60L * 1000L

        fun feedUrl(packageName: String): String {
            val file = if (packageName.endsWith(".tv")) "iptvstream-tv.json" else "iptvstream-mobile.json"
            return "https://raw.githubusercontent.com/daniel96865-a11y/iptvstream/main/docs/$file"
        }

        fun parseFeed(text: String): UpdateInfo {
            val o = JSONObject(text)
            val code = o.getInt("versionCode")
            val name = o.getString("versionName")
            val apk = o.getString("apkUrl")
            if (code <= 0 || name.isBlank() || !apk.startsWith("https://")) error("ungültiger Feed")
            return UpdateInfo(code, name, apk, o.optString("changelog"))
        }
    }
}

fun installedVersionName(context: Context): String = try {
    context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: ""
} catch (_: PackageManager.NameNotFoundException) {
    ""
}

fun installedVersionCode(context: Context): Long {
    val info = context.packageManager.getPackageInfo(context.packageName, 0)
    return if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else {
        @Suppress("DEPRECATION")
        info.versionCode.toLong()
    }
}

fun canInstall(context: Context): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return true
    return context.packageManager.canRequestPackageInstalls()
}
