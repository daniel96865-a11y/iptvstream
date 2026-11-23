package de.dgstudios.iptvstream.core.browser

import android.annotation.SuppressLint
import android.webkit.WebSettings
import android.webkit.WebView

/** WebView-Einstellungen des Browsers (eigene Datei, damit ein Test sie prüfen kann). */
object BrowserWebSettings {
    @SuppressLint("SetJavaScriptEnabled")
    fun apply(web: WebView) {
        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            loadWithOverviewMode = true
            useWideViewPort = true
            builtInZoomControls = true
            displayZoomControls = false
            setSupportZoom(true)
            // Popups: nur nach einem Tipp und nur, wenn das Ziel keine Werbung ist (onCreateWindow).
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(true)
            // http-Bilder, -Videos und -Streams (auch per Skript/XHR) auf https-Seiten erlauben.
            // Geprüft (Context7-Review 1.0.77): MIXED_CONTENT_COMPATIBILITY_MODE lässt nur passive
            // Inhalte (<img>/<video>) durch und sperrt http-HLS per Skript (hls.js/XHR/fetch) – genau
            // der Fall vieler Stream-Seiten. Darum bewusst ALWAYS_ALLOW. Die Gefahr ist begrenzt:
            // kein Datei-/Content-Zugriff, keine App-Daten über DGBridge, Werbefilter aktiv.
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            allowFileAccess = false
            allowContentAccess = false
        }
    }
}
