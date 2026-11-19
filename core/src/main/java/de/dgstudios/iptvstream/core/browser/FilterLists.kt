package de.dgstudios.iptvstream.core.browser

import android.content.Context
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Filterlisten: eingebauter Stand in den Assets (falls offline), heruntergeladene Fassung im App-Speicher,
 * Aktualisierung höchstens alle [MAX_AGE_MS] beim Öffnen des Browsers.
 */
object FilterLists {
    data class Source(val file: String, val title: String, val url: String)

    val sources = listOf(
        Source("easylist.txt", "EasyList", "https://easylist.to/easylist/easylist.txt"),
        Source("easylistgermany.txt", "EasyList Germany", "https://easylist.to/easylistgermany/easylistgermany.txt"),
        Source(
            "peterlowe.txt",
            "Peter Lowe (Werbe- und Popup-Server)",
            "https://pgl.yoyo.org/adservers/serverlist.php?hostformat=adblockplus&showintro=0&mimetype=plaintext",
        ),
    )

    const val MAX_AGE_MS = 3L * 24 * 60 * 60 * 1000

    @Volatile var engine: FilterEngine? = null
        private set
    @Volatile private var ready = CountDownLatch(1)
    @Volatile private var loading = false
    @Volatile private var updating = false

    private fun dir(ctx: Context) = File(ctx.filesDir, "adblock").apply { mkdirs() }

    /** Zeitpunkt des letzten erfolgreichen Downloads, 0 = nur eingebauter Stand. */
    fun lastUpdate(ctx: Context): Long =
        ctx.getSharedPreferences(BrowserPrefs.FILE, Context.MODE_PRIVATE).getLong("lists_updated", 0L)

    /** Lädt die Regeln im Hintergrund (einmal pro Prozess) und aktualisiert alte Listen. */
    fun ensure(ctx: Context, http: OkHttpClient, onChange: () -> Unit = {}) {
        val app = ctx.applicationContext
        if (engine == null && !loading) {
            loading = true
            thread(name = "adblock-load", isDaemon = true) {
                try {
                    engine = FilterEngine.parse(readAll(app))
                } catch (_: Throwable) {
                    engine = FilterEngine.parse(emptyList())
                } finally {
                    ready.countDown()
                    loading = false
                    onChange()
                }
            }
        }
        if (System.currentTimeMillis() - lastUpdate(app) > MAX_AGE_MS) update(app, http) { onChange() }
    }

    /** Wartet kurz auf die Regeln (für die ersten Anfragen einer Seite). */
    fun await(timeoutMs: Long): FilterEngine? {
        engine?.let { return it }
        ready.await(timeoutMs, TimeUnit.MILLISECONDS)
        return engine
    }

    fun update(ctx: Context, http: OkHttpClient, onDone: (Boolean) -> Unit = {}) {
        if (updating) return
        updating = true
        val app = ctx.applicationContext
        thread(name = "adblock-update", isDaemon = true) {
            var ok = 0
            try {
                val client = http.newBuilder().callTimeout(60, TimeUnit.SECONDS).build()
                for (s in sources) {
                    try {
                        val req = Request.Builder().url(s.url).header("User-Agent", "IPTVstream-Browser").build()
                        client.newCall(req).execute().use { resp ->
                            val body = resp.body?.string()
                            if (resp.isSuccessful && body != null && looksValid(body)) {
                                val tmp = File(dir(app), s.file + ".tmp")
                                tmp.writeText(body)
                                if (tmp.renameTo(File(dir(app), s.file))) ok++
                            }
                        }
                    } catch (_: Exception) {
                    }
                }
                if (ok > 0) {
                    app.getSharedPreferences(BrowserPrefs.FILE, Context.MODE_PRIVATE).edit()
                        .putLong("lists_updated", System.currentTimeMillis()).apply()
                    engine = FilterEngine.parse(readAll(app))
                }
            } catch (_: Throwable) {
            } finally {
                updating = false
                onDone(ok > 0)
            }
        }
    }

    fun looksValid(body: String): Boolean =
        body.length > 10_000 && body.trimStart().startsWith("[Adblock")

    private fun readAll(ctx: Context): List<String> = sources.map { s ->
        val f = File(dir(ctx), s.file)
        val downloaded = if (f.isFile && f.length() > 10_000) runCatching { f.readText() }.getOrNull() else null
        downloaded ?: ctx.assets.open("adblock/${s.file}").bufferedReader().use { it.readText() }
    }
}
