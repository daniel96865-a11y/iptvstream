package de.dgstudios.iptvstream.core.browser

import androidx.webkit.ProxyConfig
import androidx.webkit.ProxyController
import androidx.webkit.WebViewFeature
import okhttp3.OkHttpClient
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Schaltet „Sicheres DNS“ für die Browser-WebView an oder aus. Weg: WebView → lokaler [DnsProxy]
 * (ProxyController von androidx.webkit) → Ziel. Heimnetz-Adressen werden am Proxy vorbeigeleitet.
 */
object BrowserDns {
    private var proxy: DnsProxy? = null
    private var appliedKey: String? = null
    private val fallbackNoted = AtomicBoolean(false)

    /** Ob dieses Gerät (WebView-Version) eine Proxy-Umleitung für die App erlaubt. */
    fun supported(): Boolean = WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)

    private val direct = Executor { it.run() }

    /** Heimnetz und lokale Namen gehen nie über den Proxy (der Proxy würde sie aber auch selbst normal auflösen). */
    val BYPASS = listOf(
        "<local>", "localhost", "*.local", "*.lan", "*.home.arpa", "*.fritz.box",
        "127.0.0.0/8", "10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16", "169.254.0.0/16",
        "[::1]", "fc00::/7", "fe80::/10",
    )

    /**
     * Wendet die Einstellung an. [onFallback]: Anbieter war nicht erreichbar, es wurde normales DNS genommen
     * (wird pro Einschalten nur einmal gemeldet). [done] läuft, wenn die WebView die Umleitung übernommen hat.
     * Rückgabe: true, wenn Sicheres DNS jetzt aktiv ist.
     */
    @Synchronized
    fun apply(mode: String, customUrl: String, http: OkHttpClient, onFallback: () -> Unit, done: () -> Unit = {}): Boolean {
        val key = "$mode|$customUrl"
        if (!supported()) {
            done()
            return false
        }
        val resolver = SecureDns.create(mode, customUrl, http) {
            if (fallbackNoted.compareAndSet(false, true)) onFallback()
        }
        if (resolver == null) {
            if (appliedKey != null) {
                ProxyController.getInstance().clearProxyOverride(direct) { done() }
                proxy?.stop()
                proxy = null
                appliedKey = null
            } else {
                done()
            }
            return false
        }
        if (key == appliedKey && proxy?.running == true) {
            done()
            return true
        }
        fallbackNoted.set(false)
        val p = proxy ?: DnsProxy(resolver).also { proxy = it }
        p.dns = resolver
        val port = p.start()
        val cfg = ProxyConfig.Builder().addProxyRule("127.0.0.1:$port").apply { BYPASS.forEach { addBypassRule(it) } }.build()
        ProxyController.getInstance().setProxyOverride(cfg, direct) { done() }
        appliedKey = key
        return true
    }
}
