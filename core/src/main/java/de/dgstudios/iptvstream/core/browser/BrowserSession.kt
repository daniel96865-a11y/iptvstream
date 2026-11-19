package de.dgstudios.iptvstream.core.browser

import android.content.Context
import android.content.MutableContextWrapper
import android.os.Bundle
import android.os.Parcel
import android.webkit.WebView
import java.io.File

/**
 * Merkt sich die letzte Seite samt Verlauf (WebView.saveState) über App-Neustarts hinweg.
 * Fällt das Wiederherstellen aus (andere WebView-Version o. ä.), bleibt die letzte Adresse.
 */
object BrowserSession {
    private const val FILE = "browser_state.bin"
    private const val MAX_BYTES = 2 * 1024 * 1024

    private fun file(ctx: Context) = File(ctx.applicationContext.filesDir, FILE)

    fun save(ctx: Context, web: WebView) {
        val url = web.url
        if (!BrowserUrl.isWeb(url)) return
        BrowserPrefs(ctx).lastUrl = url!!
        runCatching {
            val b = Bundle()
            web.saveState(b) ?: return
            val p = Parcel.obtain()
            val bytes = try {
                p.writeBundle(b)
                p.marshall()
            } finally {
                p.recycle()
            }
            if (bytes.size > MAX_BYTES) {
                file(ctx).delete()
                return
            }
            val f = file(ctx)
            val tmp = File(f.parentFile, "$FILE.tmp")
            tmp.writeBytes(bytes)
            if (!tmp.renameTo(f)) {
                f.delete()
                tmp.renameTo(f)
            }
        }
    }

    /** Stellt Verlauf und Seite wieder her; sonst die letzte Adresse laden. false = nichts gemerkt. */
    fun restore(ctx: Context, web: WebView): Boolean {
        val restored = runCatching {
            val f = file(ctx)
            if (!f.isFile || f.length() > MAX_BYTES) return@runCatching false
            val bytes = f.readBytes()
            val p = Parcel.obtain()
            val b = try {
                p.unmarshall(bytes, 0, bytes.size)
                p.setDataPosition(0)
                p.readBundle(BrowserSession::class.java.classLoader)
            } finally {
                p.recycle()
            }
            b != null && web.restoreState(b) != null
        }.getOrDefault(false)
        if (restored) return true
        val last = BrowserPrefs(ctx).lastUrl
        if (BrowserUrl.isWeb(last)) {
            web.loadUrl(last)
            return true
        }
        return false
    }

    /** „Startseite“ und „Browserdaten löschen“: gemerkte Seite vergessen. */
    fun clear(ctx: Context) {
        BrowserPrefs(ctx).lastUrl = ""
        file(ctx).delete()
    }

    fun hasSaved(ctx: Context): Boolean = BrowserUrl.isWeb(BrowserPrefs(ctx).lastUrl) || file(ctx).isFile
}

/**
 * Hält die WebView im Hintergrund, solange die App läuft: Beim erneuten Öffnen ist die Seite sofort da,
 * ohne neu zu laden. Beim Schließen wird sie pausiert (Medien, Timer) und an den App-Kontext gehängt.
 */
internal object BrowserHolder {
    var web: WebView? = null
        private set
    private var wrapper: MutableContextWrapper? = null
    /** Aktivität, an der die WebView gerade hängt. */
    var owner: Any? = null
        private set
    /** Seitenskripte wurden bei dieser WebView schon beim Dokumentstart eingehängt. */
    var injectedEarly = false

    /** Vorhandene WebView an die Aktivität hängen oder neue anlegen. second = wiederverwendet. */
    fun obtain(activity: Context): Pair<WebView, Boolean> {
        val existing = web
        val w = wrapper
        if (existing != null && w != null) {
            w.baseContext = activity
            owner = activity
            (existing.parent as? android.view.ViewGroup)?.removeView(existing)
            return existing to true
        }
        val ctx = MutableContextWrapper(activity)
        val fresh = WebView(ctx)
        wrapper = ctx
        web = fresh
        owner = activity
        injectedEarly = false
        return fresh to false
    }

    /** Aktivität endet: WebView behalten, aber vom Fenster lösen. */
    fun release(activity: Context) {
        if (owner !== activity) return
        val w = web ?: return
        (w.parent as? android.view.ViewGroup)?.removeView(w)
        wrapper?.baseContext = activity.applicationContext
        owner = null
    }

    /** WebView verwerfen (Startseite, Daten löschen, Renderer weg). */
    fun discard() {
        val w = web ?: return
        web = null
        wrapper = null
        owner = null
        injectedEarly = false
        runCatching {
            (w.parent as? android.view.ViewGroup)?.removeView(w)
            w.stopLoading()
            w.removeJavascriptInterface("DGBridge")
            w.destroy()
        }
    }
}
