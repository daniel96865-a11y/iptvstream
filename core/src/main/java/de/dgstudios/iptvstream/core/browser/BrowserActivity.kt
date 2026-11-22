package de.dgstudios.iptvstream.core.browser

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.os.Message
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.net.http.SslError
import android.webkit.SslErrorHandler
import android.webkit.WebResourceError
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import java.io.ByteArrayInputStream
import java.text.DateFormat
import java.util.Date
import java.util.concurrent.atomic.AtomicInteger

/**
 * Eingebauter Browser (Beta) für Handy und TV: WebView mit Werbeblocker aus echten Filterlisten,
 * Popup- und Weiterleitungs-Sperre, Video-Vollbild per Tipp, Zurück-Taste = Seite zurück.
 * Startet ohne vorbelegte Seite.
 */
class BrowserActivity : ComponentActivity() {
    private lateinit var ui: BrowserChrome
    private lateinit var web: WebView
    private lateinit var prefs: BrowserPrefs
    private var tv = false

    @Volatile private var pageHost: String? = null
    private val blockedOnPage = AtomicInteger(0)
    @Volatile private var lastTap = 0L
    private var customView: View? = null
    private var customCallback: WebChromeClient.CustomViewCallback? = null
    private var pageFullscreen = false
    private var injectedEarly = false
    /** Eingabe ohne Schema: https wird versucht; schlägt es fehl, diese http-Adresse laden. */
    private var pendingFallback: Pair<String, String>? = null
    /** Hosts, deren unsicheres Zertifikat der Nutzer in dieser Sitzung bestätigt hat. */
    private val trustedHosts = HashSet<String>()
    private var sslDialog: AlertDialog? = null

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        tv = intent.getBooleanExtra(EXTRA_TV, false)
        prefs = BrowserPrefs(this)
        ui = BrowserChrome(this, tv)
        // Randlos (Android 15 erzwingt das ohnehin); die Leisten-Abstände setzt BrowserChrome.applyInsets.
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }
        setContentView(ui.root)
        current = this
        val reused = bindWeb()
        setupChrome()
        refreshStatus()
        FilterLists.ensure(this, http()) { runOnUiThread { refreshStatus() } }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = goBack()
        })

        // Sicheres DNS zuerst einrichten, erst danach die erste Seite laden.
        // Falls die WebView die Bestätigung schuldig bleibt, nach kurzer Zeit trotzdem starten.
        val started = java.util.concurrent.atomic.AtomicBoolean(false)
        val go = Runnable { if (!isDestroyed && started.compareAndSet(false, true)) startContent(reused) }
        applySecureDns { runOnUiThread(go) }
        ui.root.postDelayed(go, 2_000)
    }

    private fun startContent(reused: Boolean) {
        val start = intent.dataString
        when {
            BrowserUrl.isWeb(start) -> open(start!!)
            // Browser lief noch im Hintergrund: Seite ist sofort da, nichts neu laden.
            reused && BrowserUrl.isWeb(web.url) -> showPage()
            // Nach App-Neustart: letzte Seite samt Verlauf wiederherstellen.
            !reused && BrowserSession.restore(this, web) -> showPage()
            else -> showStart()
        }
    }

    /** WebView aus dem Hintergrund holen oder neu anlegen und einrichten. true = wiederverwendet. */
    private fun bindWeb(): Boolean {
        val (w, reused) = BrowserHolder.obtain(this)
        web = w
        ui.attachWeb(web)
        setupWeb(fresh = !reused)
        return reused
    }

    private fun showPage() {
        ui.startPage.visibility = View.GONE
        pageHost = web.url?.let { FilterEngine.hostOf(it.lowercase()) }
        setAddress(web.url)
        updateNav()
        toolbarMode = false
        web.requestFocus()
    }

    private fun showStart() {
        toolbarMode = true
        ui.startPage.visibility = View.VISIBLE
        ui.address.setText("")
        updateNav()
        focusAddress(showKeyboard = !tv)
    }

    /** Browser sofort schließen und zum IPTV-Bildschirm zurück, von dem er geöffnet wurde. */
    /** HTTP-Client der App (in Tests ohne IptvApp ein eigener). */
    private fun http(): okhttp3.OkHttpClient =
        (application as? de.dgstudios.iptvstream.core.IptvApp)?.container?.okHttp ?: fallbackHttp

    private fun closeBrowser() {
        if (customView != null) exitCustomView()
        hideKeyboard()
        finish()
    }

    /** „Startseite“: gemerkte Seite und Verlauf verwerfen, leere Startseite zeigen. */
    private fun resetToStart() {
        if (customView != null) exitCustomView()
        BrowserSession.clear(this)
        BrowserHolder.discard()
        bindWeb()
        web.onResume()
        web.resumeTimers()
        showStart()
    }

    // ------------------------------------------------------------ WebView

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWeb(fresh: Boolean) {
        BrowserWebSettings.apply(web)
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true)
        web.setBackgroundColor(BrowserChrome.BG)
        web.isFocusable = true
        web.isFocusableInTouchMode = true
        if (fresh) web.addJavascriptInterface(Bridge(), "DGBridge")
        web.setOnTouchListener { _, e ->
            if (e.actionMasked == MotionEvent.ACTION_DOWN) lastTap = System.currentTimeMillis()
            false
        }
        if (fresh && WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            WebViewCompat.addDocumentStartJavaScript(web, BrowserJs.HIDE + BrowserJs.AUTO_FULLSCREEN + BrowserJs.PAUSE_LISTENER, setOf("*"))
            BrowserHolder.injectedEarly = true
        }
        injectedEarly = BrowserHolder.injectedEarly
        web.setDownloadListener { url, _, _, _, _ ->
            toast("Downloads sind im Browser nicht möglich.")
            if (!tv) runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
        }
        web.webViewClient = Client()
        web.webChromeClient = Chrome()
    }

    private inner class Client : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val url = request.url.toString()
            if (!BrowserUrl.isWeb(url)) {
                // Andere Apps/Schemata (intent:, market:, tel: …) nur auf einen echten Tipp und nur am Handy.
                if (!tv && request.hasGesture() && (url.startsWith("tel:") || url.startsWith("mailto:"))) {
                    runCatching { startActivity(Intent(Intent.ACTION_VIEW, request.url)) }
                }
                return true
            }
            if (request.isForMainFrame && prefs.adblock) {
                val e = FilterLists.engine
                if (e != null && e.shouldBlockPopup(url, pageHost)) {
                    toast("Weiterleitung zu Werbung blockiert")
                    return true
                }
            }
            return false
        }

        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
            if (request.isForMainFrame || !prefs.adblock) return null
            val url = request.url.toString()
            if (!BrowserUrl.isWeb(url)) return null
            val e = FilterLists.await(3_000) ?: return null
            if (!e.shouldBlock(url, pageHost)) return null
            val n = blockedOnPage.incrementAndGet()
            runOnUiThread { ui.setBlocked(n) }
            return WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
        }

        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
            if (!request.isForMainFrame) return
            val failed = request.url.toString()
            val fb = pendingFallback
            if (fb != null && BrowserUrl.same(fb.first, failed) && error.errorCode in FALLBACK_ERRORS) {
                pendingFallback = null
                toast("https nicht erreichbar – öffne http")
                view.loadUrl(fb.second)
            }
        }

        override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
            val url = error.url ?: ""
            val host = FilterEngine.hostOf(url.lowercase()) ?: ""
            val fb = pendingFallback
            if (fb != null && FilterEngine.hostOf(fb.first.lowercase()) == host) {
                // https war nur ein Versuch (Eingabe ohne Schema): nicht nachfragen, sondern http nehmen.
                handler.cancel()
                pendingFallback = null
                toast("https-Zertifikat ungültig – öffne http")
                view.loadUrl(fb.second)
                return
            }
            if (host in trustedHosts) {
                handler.proceed()
                return
            }
            if (sslDialog?.isShowing == true || isFinishing) {
                handler.cancel()
                return
            }
            val why = when (error.primaryError) {
                SslError.SSL_UNTRUSTED -> "Das Zertifikat stammt nicht von einer anerkannten Stelle (z. B. selbst signiert)."
                SslError.SSL_EXPIRED -> "Das Zertifikat ist abgelaufen."
                SslError.SSL_IDMISMATCH -> "Das Zertifikat gehört zu einer anderen Adresse."
                SslError.SSL_NOTYETVALID -> "Das Zertifikat ist noch nicht gültig."
                SslError.SSL_DATE_INVALID -> "Das Datum des Zertifikats ist ungültig."
                else -> "Das Zertifikat ist ungültig."
            }
            sslDialog = AlertDialog.Builder(this@BrowserActivity, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("Unsichere Verbindung")
                .setMessage("$host\n\n$why\n\nNur öffnen, wenn du dem Gerät vertraust (z. B. Router oder NAS im Heimnetz).")
                .setPositiveButton("Zurück") { _, _ -> handler.cancel() }
                .setNegativeButton("Trotzdem öffnen") { _, _ ->
                    trustedHosts.add(host)
                    handler.proceed()
                }
                .setOnCancelListener { handler.cancel() }
                .show()
        }

        override fun onRenderProcessGone(view: WebView, detail: android.webkit.RenderProcessGoneDetail): Boolean {
            // Seitenprozess beendet (z. B. Speicher knapp im Hintergrund): WebView ersetzen statt App-Absturz.
            if (view !== web) return true
            BrowserHolder.discard()
            if (isFinishing || isDestroyed) return true
            bindWeb()
            if (BrowserSession.restore(this@BrowserActivity, web)) showPage() else showStart()
            return true
        }

        override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
            pageHost = url?.let { FilterEngine.hostOf(it.lowercase()) }
            blockedOnPage.set(0)
            ui.setBlocked(0)
            ui.startPage.visibility = View.GONE
            setAddress(url)
            if (!injectedEarly) {
                view.evaluateJavascript(BrowserJs.HIDE + BrowserJs.AUTO_FULLSCREEN, null)
            }
            updateNav()
        }

        override fun onPageFinished(view: WebView, url: String?) {
            // Seite ist da: kein Rückfall mehr nötig (außer es war genau die fehlgeschlagene https-Seite).
            val fb = pendingFallback
            if (fb != null && url != null && !BrowserUrl.same(fb.first, url) && url != "about:blank") pendingFallback = null
            if (!injectedEarly) view.evaluateJavascript(BrowserJs.HIDE + BrowserJs.AUTO_FULLSCREEN, null)
            setAddress(url)
            updateNav()
        }

        override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
            // Letzte Adresse sofort merken (Verlauf kommt beim Schließen dazu).
            if (BrowserUrl.isWeb(url)) prefs.lastUrl = url!!
            pageHost = url?.let { FilterEngine.hostOf(it.lowercase()) }
            setAddress(url)
            updateNav()
        }
    }

    private inner class Chrome : WebChromeClient() {
        override fun onProgressChanged(view: WebView, newProgress: Int) {
            ui.progress.progress = newProgress
            ui.progress.visibility = if (newProgress in 1..99) View.VISIBLE else View.INVISIBLE
        }

        override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message): Boolean {
            if (!isUserGesture) {
                toast("Popup blockiert")
                return false
            }
            // Ziel des neuen Fensters abfangen und im selben Fenster öffnen – außer es ist Werbung.
            val probe = WebView(this@BrowserActivity)
            var handled = false
            fun take(url: String?) {
                if (handled) return
                handled = true
                probe.stopLoading()
                probe.post { probe.destroy() }
                if (!BrowserUrl.isWeb(url)) return
                val e = FilterLists.engine
                if (prefs.adblock && e != null && e.shouldBlockPopup(url!!, pageHost)) {
                    toast("Popup blockiert")
                } else {
                    web.loadUrl(url!!)
                }
            }
            probe.webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(v: WebView, r: WebResourceRequest): Boolean {
                    take(r.url.toString())
                    return true
                }

                override fun onPageStarted(v: WebView, url: String?, favicon: Bitmap?) {
                    if (url != null && url != "about:blank") take(url)
                }
            }
            (resultMsg.obj as WebView.WebViewTransport).webView = probe
            resultMsg.sendToTarget()
            return true
        }

        override fun onShowCustomView(view: View, callback: CustomViewCallback) {
            if (customView != null) {
                callback.onCustomViewHidden()
                return
            }
            customView = view
            customCallback = callback
            ui.fullscreen.addView(view)
            ui.fullscreen.visibility = View.VISIBLE
            ui.fullscreen.isFocusable = true
            ui.fullscreen.requestFocus()
            immersive(true)
            if (!tv) requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        }

        override fun onHideCustomView() {
            exitCustomView()
        }
    }

    /**
     * Für die Seitenskripte (alle Methoden harmlos, keine Daten der App).
     * Die WebView überlebt die Aktivität: darum immer an die gerade offene Aktivität weitergeben.
     */
    private class Bridge {
        private fun act(): BrowserActivity? = current

        @JavascriptInterface
        fun css(host: String?): String {
            val a = act() ?: return ""
            if (!a.prefs.adblock) return ""
            val e = FilterLists.await(1_500) ?: return ""
            return e.genericCss + "\n" + e.siteCss(host)
        }

        @JavascriptInterface
        fun autoFs(src: String?): Boolean = act()?.prefs?.autoFullscreen ?: false

        @JavascriptInterface
        fun recentTap(ms: Int): Boolean = System.currentTimeMillis() - (act()?.lastTap ?: 0L) < ms.coerceIn(0, 30_000)

        @JavascriptInterface
        fun pageFs(on: Boolean) {
            val a = act() ?: return
            a.runOnUiThread { a.setPageFullscreen(on) }
        }

        @JavascriptInterface
        fun fsVideo(w: Int, h: Int) {
            val a = act() ?: return
            if (a.tv) return
            a.runOnUiThread {
                if (a.customView != null || a.pageFullscreen) {
                    a.requestedOrientation = if (h > w) ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT else ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                }
            }
        }
    }

    private fun setPageFullscreen(on: Boolean) {
        pageFullscreen = on
        ui.toolbar.visibility = if (on) View.GONE else View.VISIBLE
        immersive(on)
        if (!tv) requestedOrientation = if (on) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    }

    // ------------------------------------------------------------ Leiste

    private fun setupChrome() {
        ui.close.setOnClickListener { closeBrowser() }
        ui.back.setOnClickListener { if (web.canGoBack()) web.goBack() }
        ui.forward.setOnClickListener { if (web.canGoForward()) web.goForward() }
        ui.reload.setOnClickListener { if (web.url != null) web.reload() }
        ui.menu.setOnClickListener { showMenu() }
        // Tippen auf die Adresse markiert sofort die ganze Adresse (auch beim zweiten Tippen).
        ui.address.setOnFocusChangeListener { v, has ->
            if (has) v.post { if (ui.address.hasFocus()) ui.address.selectAll() }
        }
        // Klick (Tipp oder OK) markiert alles und öffnet die Tastatur. Vorher verhinderte der eigene Klick-Handler,
        // dass Android am TV bei OK die Tastatur öffnet.
        ui.address.setOnClickListener { openAddressInput() }
        ui.address.setOnEditorActionListener { _, actionId, ev ->
            // Enter von einer echten Fernbedienung/Tastatur ohne offene Bildschirmtastatur = Tastatur öffnen,
            // nicht die alte Adresse neu laden. Enter von der Bildschirmtastatur = Los.
            val hardwareEnter = ev != null && ev.keyCode != KeyEvent.KEYCODE_UNKNOWN && TvRemote.isOk(ev.keyCode) &&
                ev.deviceId != android.view.KeyCharacterMap.VIRTUAL_KEYBOARD &&
                (ev.flags and KeyEvent.FLAG_SOFT_KEYBOARD) == 0 && !imeVisible()
            if (tv && hardwareEnter) {
                if (ev!!.action == KeyEvent.ACTION_DOWN) openAddressInput()
                return@setOnEditorActionListener true
            }
            val go = actionId == EditorInfo.IME_ACTION_GO || actionId == EditorInfo.IME_ACTION_DONE ||
                (ev != null && ev.keyCode == KeyEvent.KEYCODE_ENTER && ev.action == KeyEvent.ACTION_DOWN)
            if (go) {
                val target = BrowserUrl.target(ui.address.text.toString())
                if (target != null) {
                    pendingFallback = target.fallback?.let { target.url to it }
                    open(target.url)
                }
            }
            go
        }
        updateNav()
    }

    private fun open(url: String) {
        hideKeyboard()
        ui.startPage.visibility = View.GONE
        web.loadUrl(url)
        toolbarMode = false
        web.requestFocus()
    }

    private fun setAddress(url: String?) {
        if (url == null || ui.address.hasFocus() || !BrowserUrl.isWeb(url)) return
        ui.address.setText(url)
    }

    private fun updateNav() {
        ui.back.alpha = if (web.canGoBack()) 1f else 0.4f
        ui.forward.alpha = if (web.canGoForward()) 1f else 0.4f
    }

    private fun focusAddress(showKeyboard: Boolean) {
        ui.address.requestFocus()
        if (showKeyboard) showKeyboard(ui.address)
    }

    private fun hideKeyboard() {
        getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(ui.address.windowToken, 0)
    }

    private fun refreshStatus() {
        val e = FilterLists.engine
        val last = FilterLists.lastUpdate(this)
        val stand = if (last > 0) "Stand " + DateFormat.getDateInstance(DateFormat.SHORT).format(Date(last)) else "eingebauter Stand"
        ui.setStatus(
            when {
                !prefs.adblock -> "Werbeblocker aus"
                e == null -> "Werbeblocker lädt …"
                else -> "Werbeblocker an · ${"%,d".format(e.ruleCount).replace(',', '.')} Regeln · $stand\nEasyList, EasyList Germany, Peter Lowe"
            } + if (prefs.secureDns != SecureDns.OFF && BrowserDns.supported()) "\nSicheres DNS: " + secureDnsLabel() else "",
        )
    }

    private fun showMenu() {
        if (!tv) {
            showPhoneMenu()
            return
        }
        // TV: einfache Liste, mit der Fernbedienung gut zu bedienen.
        val items = arrayOf(
            "Browser schließen (zurück zu IPTV)",
            "Startseite",
            "Adresse eingeben",
            "Werbeblocker: " + if (prefs.adblock) "an" else "aus",
            "Angetipptes Video sofort im Vollbild: " + if (prefs.autoFullscreen) "an" else "aus",
            "Sicheres DNS: " + secureDnsLabel(),
            "Filterlisten aktualisieren",
            "Browserdaten löschen",
        )
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Browser · Beta")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> closeBrowser()
                    1 -> resetToStart()
                    2 -> showAddressDialog()
                    3 -> toggleAdblock()
                    4 -> toggleAutoFs()
                    5 -> showSecureDnsDialog()
                    6 -> updateLists()
                    7 -> clearData()
                }
            }
            .setNegativeButton("Fertig", null)
            .show()
    }

    // ------------------------------------------------------------ Sicheres DNS

    private fun applySecureDns(done: () -> Unit = {}) {
        BrowserDns.apply(
            prefs.secureDns, prefs.secureDnsUrl, http(),
            onFallback = { toast("Sicheres DNS nicht erreichbar – normales DNS wird verwendet") },
            done = done,
        )
    }

    private fun secureDnsLabel(): String = SecureDns.label(prefs.secureDns, prefs.secureDnsUrl)

    /** Auswahl wie in Chrome/Firefox: Aus, Cloudflare, Quad9, Google oder eigener DoH-Anbieter. */
    private fun showSecureDnsDialog() {
        if (!BrowserDns.supported()) {
            AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("Sicheres DNS")
                .setMessage("Auf diesem Gerät nicht verfügbar: Die Android System WebView ist zu alt. Bitte im Play Store aktualisieren.")
                .setPositiveButton("OK", null)
                .show()
            return
        }
        val ids = listOf(SecureDns.OFF) + SecureDns.PROVIDERS.map { it.id } + SecureDns.CUSTOM
        val labels = ids.map { SecureDns.label(it, if (it == SecureDns.CUSTOM) prefs.secureDnsUrl else "") }.toTypedArray()
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Sicheres DNS")
            .setSingleChoiceItems(labels, ids.indexOf(prefs.secureDns).coerceAtLeast(0)) { d, which ->
                d.dismiss()
                val id = ids[which]
                if (id == SecureDns.CUSTOM) showCustomDnsDialog() else setSecureDns(id)
            }
            .setNegativeButton("Abbrechen", null)
            .show()
    }

    private fun showCustomDnsDialog() {
        val input = android.widget.EditText(this).apply {
            setSingleLine()
            hint = "https://dns.example/dns-query"
            setText(prefs.secureDnsUrl)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI
        }
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Eigener DNS-über-HTTPS-Anbieter")
            .setView(input)
            .setPositiveButton("Übernehmen") { _, _ ->
                val url = input.text.toString().trim()
                if (!SecureDns.validCustomUrl(url)) {
                    toast("Bitte eine https-Adresse eingeben")
                } else {
                    prefs.secureDnsUrl = url
                    setSecureDns(SecureDns.CUSTOM)
                }
            }
            .setNegativeButton("Abbrechen", null)
            .show()
    }

    private fun setSecureDns(id: String) {
        prefs.secureDns = id
        applySecureDns {
            runOnUiThread {
                refreshStatus()
                toast("Sicheres DNS: " + secureDnsLabel())
                if (web.url != null) web.reload()
            }
        }
    }

    private fun toggleAdblock() {
        prefs.adblock = !prefs.adblock
        refreshStatus()
        toast(if (prefs.adblock) "Werbeblocker an" else "Werbeblocker aus")
        if (web.url != null) web.reload()
    }

    private fun toggleAutoFs() {
        prefs.autoFullscreen = !prefs.autoFullscreen
        toast(if (prefs.autoFullscreen) "Videos gehen sofort ins Vollbild" else "Kein automatisches Vollbild")
    }

    /** Handy: kompaktes Menü am ⋮ mit Vor/Neu laden (die stehen dort nicht in der Leiste). */
    private fun showPhoneMenu() {
        val pm = android.widget.PopupMenu(this, ui.menu)
        val m = pm.menu
        m.add(0, 1, 0, "Vor").isEnabled = web.canGoForward()
        m.add(0, 2, 1, "Neu laden").isEnabled = web.url != null
        m.add(0, 8, 2, "Startseite")
        m.add(0, 3, 3, "Werbeblocker").apply { isCheckable = true; isChecked = prefs.adblock }
        m.add(0, 4, 4, "Video sofort im Vollbild").apply { isCheckable = true; isChecked = prefs.autoFullscreen }
        m.add(0, 9, 5, "Sicheres DNS: " + secureDnsLabel())
        m.add(0, 5, 5, "Filterlisten aktualisieren")
        m.add(0, 6, 6, "Browserdaten löschen")
        m.add(0, 7, 7, "Browser schließen")
        pm.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> if (web.canGoForward()) web.goForward()
                2 -> web.reload()
                3 -> toggleAdblock()
                4 -> toggleAutoFs()
                5 -> updateLists()
                6 -> clearData()
                7 -> closeBrowser()
                8 -> resetToStart()
                9 -> showSecureDnsDialog()
            }
            true
        }
        pm.show()
    }

    private fun updateLists() {
        toast("Filterlisten werden geladen …")
        FilterLists.update(this, http()) { ok ->
            runOnUiThread {
                refreshStatus()
                toast(if (ok) "Filterlisten aktualisiert" else "Aktualisierung fehlgeschlagen – eingebaute Liste bleibt aktiv")
            }
        }
    }

    private fun clearData() {
        web.clearCache(true)
        web.clearHistory()
        CookieManager.getInstance().removeAllCookies(null)
        WebStorage.getInstance().deleteAllData()
        // Gemerkte Seite gehört dazu: zurück zur leeren Startseite.
        resetToStart()
        toast("Cookies, Verlauf, gemerkte Seite und Zwischenspeicher gelöscht")
    }

    // ------------------------------------------------------------ Zurück & Vollbild

    private fun goBack() {
        when {
            customView != null -> exitCustomView()
            pageFullscreen -> web.evaluateJavascript("window.__dgFsExit && window.__dgFsExit()", null)
            tv && toolbarMode && web.url != null && ui.startPage.visibility != View.VISIBLE -> enterPointer()
            web.canGoBack() -> web.goBack()
            else -> finish()
        }
    }

    private fun exitCustomView() {
        val v = customView ?: return
        ui.fullscreen.removeView(v)
        ui.fullscreen.visibility = View.GONE
        customView = null
        customCallback?.onCustomViewHidden()
        customCallback = null
        immersive(false)
        if (!tv) requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        web.requestFocus()
    }

    private fun immersive(on: Boolean) {
        ui.immersive = on
        val c = WindowInsetsControllerCompat(window, window.decorView)
        if (on) {
            c.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            c.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            c.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    // ------------------------------------------------------------ Fernbedienung (TV)

    // Fehlalarm von Lint: Überschreiben von Activity.dispatchKeyEvent ist öffentliche API.
    @SuppressLint("RestrictedApi")
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_BACK && handleBackKey(event)) return true
        if (tv && handleTvKey(event)) return true
        return super.dispatchKeyEvent(event)
    }

    private var backDownAt = 0L
    private var backClosed = false
    private var backTracking = false

    /** Zurück-Taste (Fernbedienung, Hardware-Taste): kurz = Seite zurück, lang = Browser schließen. */
    private fun handleBackKey(e: KeyEvent): Boolean {
        val down = e.action == KeyEvent.ACTION_DOWN
        if (down && e.repeatCount == 0) {
            backDownAt = e.downTime
            backClosed = false
            backTracking = true
        }
        // Loslassen ohne eigenes Drücken (z. B. aus einem Dialog heraus): dem System überlassen.
        if (!backTracking) return false
        val action = BackKey.decide(
            down = down,
            repeatCount = e.repeatCount,
            heldMs = e.eventTime - backDownAt,
            longFlag = e.isLongPress,
            closed = backClosed,
            canceled = e.isCanceled,
        )
        if (!down) backTracking = false
        when (action) {
            BackKey.Action.CLOSE -> {
                backClosed = true
                closeBrowser()
            }
            BackKey.Action.BACK -> goBack()
            BackKey.Action.NONE -> Unit
        }
        return true
    }

    private val remote = TvRemote.Tracker()
    private val accel = TvRemote.Accel()

    /**
     * Fernbedienung am TV. Funktioniert unabhängig davon, woher die Tasten kommen (Stick, HDMI-CEC,
     * Handy-App): keine Quellen-Filter, Einzeldrücke ohne Wiederholung bewegen den Zeiger, und ein
     * „Losgelassen“ ohne „Gedrückt“ (manche CEC-Fernbedienungen) zählt als Tastendruck.
     */
    private fun handleTvKey(e: KeyEvent): Boolean {
        val code = e.keyCode
        val down = e.action == KeyEvent.ACTION_DOWN
        if (code == KeyEvent.KEYCODE_MENU) {
            if (remote.shouldAct(code, e.action, e.repeatCount, clickOnce = true)) showMenu()
            return true
        }
        // Vollbild-Video: Tasten an das Video geben.
        if (customView != null || pageFullscreen) {
            val k = when {
                TvRemote.isOk(code) || code == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> "toggle"
                code == KeyEvent.KEYCODE_MEDIA_PLAY -> "play"
                code == KeyEvent.KEYCODE_MEDIA_PAUSE -> "pause"
                code == KeyEvent.KEYCODE_DPAD_RIGHT || code == KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> "fwd"
                code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_MEDIA_REWIND -> "back"
                else -> null
            }
            if (k == null) {
                remote.track(code, e.action)
                return false
            }
            if (remote.shouldAct(code, e.action, e.repeatCount, clickOnce = TvRemote.isOk(code))) {
                web.evaluateJavascript("window.__dgKey && window.__dgKey('$k')", null)
            }
            return true
        }
        val cmd = TvRemote.command(code)
        if (cmd == TvRemote.Cmd.NONE) {
            if (code == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE && pointerMode()) {
                if (remote.shouldAct(code, e.action, e.repeatCount, clickOnce = true)) {
                    web.evaluateJavascript("window.__dgKey && window.__dgKey('toggle')", null)
                }
                return true
            }
            remote.track(code, e.action)
            return false
        }
        val act = remote.shouldAct(code, e.action, e.repeatCount, clickOnce = cmd == TvRemote.Cmd.CLICK)

        if (!pointerMode()) {
            // Leiste/Startseite: normale Fokus-Steuerung, aber OK selbst auslösen (CEC liefert teils nur „losgelassen“,
            // und die Adressleiste soll die Tastatur öffnen).
            val focused = focusedView()
            val inToolbar = focused != null && isInToolbar(focused)
            when (cmd) {
                TvRemote.Cmd.CLICK -> {
                    // Tastatur offen: Enter gehört der Eingabe („Los“), nicht neu öffnen.
                    if (focused === ui.address && imeVisible()) {
                        remote.track(code, e.action)
                        return false
                    }
                    if (act) {
                        when {
                            focused === ui.address || !inToolbar -> openAddressInput()
                            else -> focused!!.performClick()
                        }
                    }
                    return true
                }
                TvRemote.Cmd.DOWN -> if (ui.startPage.visibility != View.VISIBLE && web.url != null) {
                    if (act) enterPointer()
                    return true
                }
                else -> Unit
            }
            if (!inToolbar) {
                if (act) enterToolbar()
                return true
            }
            // Pfeil nur als „losgelassen“ angekommen: Fokus selbst weitergeben (sonst macht das System es beim Drücken).
            if (act && !down) {
                val dir = when (cmd) {
                    TvRemote.Cmd.LEFT -> View.FOCUS_LEFT
                    TvRemote.Cmd.RIGHT -> View.FOCUS_RIGHT
                    TvRemote.Cmd.UP -> View.FOCUS_UP
                    else -> View.FOCUS_DOWN
                }
                focused?.focusSearch(dir)?.requestFocus()
                return true
            }
            return false
        }

        // Zeiger-Modus: Seite ist offen und der Fokus liegt nicht in der Leiste.
        if (!act) return true
        if (!web.hasFocus()) web.requestFocus()
        // Ein Knopf der Leiste hält noch den Fokus (WebView nimmt ihn nicht): Leiste nicht versehentlich bedienen.
        when (cmd) {
            TvRemote.Cmd.CLICK -> tapAtCursor()
            else -> moveCursor(cmd, e)
        }
        return true
    }

    // Für Tests
    internal fun cursorPosition(): Pair<Float, Float> = ui.cursor.cx to ui.cursor.cy
    internal fun addressField(): View = ui.address
    internal fun closeButton(): View = ui.close
    internal var lastTapForTest = 0
        private set

    /** Zeiger aktiv: eine Seite ist offen und der Fokus liegt nicht in der Leiste. */
    private fun pointerMode(): Boolean =
        tv && !toolbarMode && ui.startPage.visibility != View.VISIBLE && web.url != null

    /**
     * TV: true = Fernbedienung bedient die Leiste, false = den Zeiger auf der Seite.
     * Bewusst ein eigener Zustand statt „wer hat gerade den Fokus“: Android setzt den Fokus beim Start
     * gern selbst auf den ersten Knopf der Leiste (seit 1.0.73 ✕ IPTV), dann reagierte der Zeiger nicht mehr.
     */
    private var toolbarMode = true

    private fun enterPointer() {
        toolbarMode = false
        hideKeyboard()
        web.requestFocus()
    }

    private fun enterToolbar(focus: View = ui.address) {
        toolbarMode = true
        focus.requestFocus()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && pointerMode() && !web.hasFocus()) web.requestFocus()
    }

    private fun focusedView(): View? = currentFocus ?: ui.root.findFocus()

    private fun isInToolbar(v: View): Boolean {
        var p: View? = v
        while (p != null) {
            if (p === ui.toolbar) return true
            p = p.parent as? View
        }
        return false
    }

    private fun moveCursor(cmd: TvRemote.Cmd, e: KeyEvent) {
        val dx = when (cmd) { TvRemote.Cmd.LEFT -> -1; TvRemote.Cmd.RIGHT -> 1; else -> 0 }
        val dy = when (cmd) { TvRemote.Cmd.UP -> -1; TvRemote.Cmd.DOWN -> 1; else -> 0 }
        val c = ui.cursor
        val density = resources.displayMetrics.density
        val level = accel.level(SystemClock.uptimeMillis(), e.repeatCount)
        val step = CursorNav.stepPx(CURSOR_STEP_DP * density, level)
        val s = CursorNav.move(
            c.cx, c.cy, dx, dy, step, c.width.toFloat(), c.height.toFloat(), 36f * density,
            pageAtTop = !web.canScrollVertically(-1),
        )
        when (s.kind) {
            CursorNav.Kind.TO_TOOLBAR -> enterToolbar()
            CursorNav.Kind.MOVE -> c.moveTo(s.x, s.y)
            CursorNav.Kind.SCROLL -> {
                c.moveTo(s.x, s.y)
                web.evaluateJavascript(BrowserJs.scrollAt(s.x / density, s.y / density, (s.scrollDy / density).toInt()), null)
            }
        }
    }

    // ------------------------------------------------------------ Tastatur

    private fun imeVisible(): Boolean =
        ViewCompat.getRootWindowInsets(ui.root)?.isVisible(WindowInsetsCompat.Type.ime()) == true

    /** Bildschirmtastatur für eine View öffnen (am TV ausdrücklich, SHOW_IMPLICIT wird dort oft ignoriert). */
    private fun showKeyboard(v: View) {
        v.requestFocus()
        v.post {
            getSystemService(InputMethodManager::class.java)?.showSoftInput(v, 0)
            WindowInsetsControllerCompat(window, v).show(WindowInsetsCompat.Type.ime())
        }
    }

    /** OK auf der Adressleiste: alles markieren und Tastatur öffnen; erscheint keine, Eingabe-Dialog. */
    private fun openAddressInput() {
        toolbarMode = true
        ui.address.requestFocus()
        ui.address.selectAll()
        showKeyboard(ui.address)
        if (tv) {
            ui.address.postDelayed({
                if (!isFinishing && ui.address.hasFocus() && !imeVisible()) showAddressDialog()
            }, KEYBOARD_FALLBACK_MS)
        }
    }

    private var inputDialog: AlertDialog? = null

    /** Ersatz, falls die Tastatur für die Adressleiste nicht aufgeht. */
    private fun showAddressDialog() {
        if (inputDialog?.isShowing == true) return
        textDialog("Adresse oder Suche", ui.address.text.toString(), uri = true) { text ->
            ui.address.setText(text)
            val target = BrowserUrl.target(text) ?: return@textDialog
            pendingFallback = target.fallback?.let { target.url to it }
            open(target.url)
        }
    }

    /** Einfacher Eingabe-Dialog mit Tastatur. */
    private fun textDialog(title: String, value: String, uri: Boolean, submitLabel: String? = null, onSubmit: ((String) -> Unit)? = null, onDone: (String) -> Unit) {
        val field = android.widget.EditText(this).apply {
            setText(value)
            setSelectAllOnFocus(true)
            setSingleLine(true)
            inputType = if (uri) android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI
            else android.text.InputType.TYPE_CLASS_TEXT
            imeOptions = EditorInfo.IME_ACTION_DONE or EditorInfo.IME_FLAG_NO_EXTRACT_UI
        }
        val pad = (20 * resources.displayMetrics.density).toInt()
        val box = android.widget.FrameLayout(this).apply {
            setPadding(pad, pad / 2, pad, 0)
            addView(field)
        }
        val b = AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(title)
            .setView(box)
            .setPositiveButton("OK") { _, _ -> onDone(field.text.toString()) }
            .setNegativeButton("Abbrechen", null)
        if (submitLabel != null && onSubmit != null) b.setNeutralButton(submitLabel) { _, _ -> onSubmit(field.text.toString()) }
        val d = b.create()
        d.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
        field.setOnEditorActionListener { _, id, _ ->
            if (id == EditorInfo.IME_ACTION_DONE || id == EditorInfo.IME_ACTION_GO) {
                onDone(field.text.toString())
                d.dismiss()
                true
            } else {
                false
            }
        }
        d.setOnShowListener {
            field.requestFocus()
            field.post { getSystemService(InputMethodManager::class.java)?.showSoftInput(field, 0) }
        }
        inputDialog = d
        d.show()
    }

    /** Nach einem Klick am TV: Liegt der Fokus in einem Web-Eingabefeld, Tastatur sicher öffnen, sonst Dialog. */
    private fun checkWebInput() {
        web.postDelayed({
            if (isFinishing) return@postDelayed
            web.evaluateJavascript(BrowserJs.ACTIVE_INPUT) { res ->
                val info = res?.trim('"') ?: ""
                if (!info.startsWith("edit")) return@evaluateJavascript
                web.requestFocus()
                showKeyboard(web)
                web.postDelayed({
                    if (!isFinishing && !imeVisible() && inputDialog?.isShowing != true) {
                        web.evaluateJavascript(BrowserJs.ACTIVE_VALUE) { v ->
                            val current = runCatching { org.json.JSONArray("[$v]").optString(0) }.getOrDefault("")
                            textDialog(
                                "Text eingeben", current, uri = false, submitLabel = "Senden",
                                onSubmit = { t -> web.evaluateJavascript(BrowserJs.setActiveValue(t, submit = true), null) },
                            ) { t -> web.evaluateJavascript(BrowserJs.setActiveValue(t, submit = false), null) }
                        }
                    }
                }, KEYBOARD_FALLBACK_MS)
            }
        }, 250)
    }

    private fun tapAtCursor() {
        lastTapForTest++
        val c = ui.cursor
        c.flash()
        lastTap = System.currentTimeMillis()
        val t = SystemClock.uptimeMillis()
        fun ev(action: Int, time: Long) = MotionEvent.obtain(t, time, action, c.cx, c.cy, 0).apply {
            source = InputDevice.SOURCE_TOUCHSCREEN
        }
        val downEv = ev(MotionEvent.ACTION_DOWN, t)
        val upEv = ev(MotionEvent.ACTION_UP, t + 60)
        web.dispatchTouchEvent(downEv)
        web.dispatchTouchEvent(upEv)
        downEv.recycle()
        upEv.recycle()
        checkWebInput()
    }

    // ------------------------------------------------------------ Lebenszyklus

    override fun onPause() {
        // Verlassen (egal wie): Seite und Verlauf merken, nichts im Hintergrund weiterspielen.
        BrowserSession.save(this, web)
        web.evaluateJavascript(BrowserJs.PAUSE_ALL, null)
        web.onPause()
        web.pauseTimers()
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        current = this
        web.resumeTimers()
        web.onResume()
    }

    override fun onDestroy() {
        customCallback?.onCustomViewHidden()
        sslDialog?.dismiss()
        if (current === this) current = null
        if (BrowserHolder.web === web) {
            // WebView bleibt für das nächste Öffnen erhalten (pausiert, ohne Fenster).
            BrowserHolder.release(this)
        } else {
            runCatching {
                web.stopLoading()
                (web.parent as? android.view.ViewGroup)?.removeView(web)
                web.destroy()
            }
        }
        super.onDestroy()
    }

    private fun toast(text: String) {
        runOnUiThread { Toast.makeText(this, text, Toast.LENGTH_SHORT).show() }
    }

    companion object {
        const val EXTRA_TV = "tv"

        /** Gerade offene Browser-Aktivität (für die JS-Brücke der weiterlebenden WebView). */
        @Volatile private var current: BrowserActivity? = null
        private val fallbackHttp by lazy { okhttp3.OkHttpClient() }

        /** Fehler, bei denen nach einem https-Versuch auf http gewechselt wird (nicht bei „Host unbekannt“). */
        /** Zeiger-Schritt pro Tastendruck (wird bei schnellen Drücken/Halten größer). */
        const val CURSOR_STEP_DP = 22f

        /** So lange auf die Bildschirmtastatur warten, bevor der Eingabe-Dialog kommt. */
        const val KEYBOARD_FALLBACK_MS = 1_200L

        val FALLBACK_ERRORS = setOf(
            WebViewClient.ERROR_CONNECT,
            WebViewClient.ERROR_TIMEOUT,
            WebViewClient.ERROR_FAILED_SSL_HANDSHAKE,
            WebViewClient.ERROR_IO,
            WebViewClient.ERROR_UNKNOWN,
            WebViewClient.ERROR_REDIRECT_LOOP,
        )

        fun start(ctx: Context, tv: Boolean) {
            ctx.startActivity(Intent(ctx, BrowserActivity::class.java).putExtra(EXTRA_TV, tv))
        }
    }
}
