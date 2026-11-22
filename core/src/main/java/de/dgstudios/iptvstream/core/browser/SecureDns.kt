package de.dgstudios.iptvstream.core.browser

import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.dnsoverhttps.DnsOverHttps
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * „Sicheres DNS“ (DNS über HTTPS) für den eingebauten Browser, wie in Chrome/Firefox.
 * Namen werden verschlüsselt beim gewählten Anbieter aufgelöst. Heimnetz-Adressen
 * (192.168.…, .local, localhost, reine IPs, Namen ohne Punkt) gehen immer über das normale DNS.
 */
object SecureDns {
    const val OFF = "off"
    const val CLOUDFLARE = "cloudflare"
    const val QUAD9 = "quad9"
    const val GOOGLE = "google"
    const val CUSTOM = "custom"

    data class Provider(val id: String, val label: String, val url: String, val bootstrap: List<String>)

    val PROVIDERS = listOf(
        Provider(
            CLOUDFLARE, "Cloudflare (1.1.1.1)", "https://cloudflare-dns.com/dns-query",
            listOf("1.1.1.1", "1.0.0.1", "2606:4700:4700::1111", "2606:4700:4700::1001"),
        ),
        Provider(
            QUAD9, "Quad9 (9.9.9.9)", "https://dns.quad9.net/dns-query",
            listOf("9.9.9.9", "149.112.112.112", "2620:fe::fe", "2620:fe::9"),
        ),
        Provider(
            GOOGLE, "Google (8.8.8.8)", "https://dns.google/dns-query",
            listOf("8.8.8.8", "8.8.4.4", "2001:4860:4860::8888", "2001:4860:4860::8844"),
        ),
    )

    fun label(mode: String, customUrl: String): String = when (mode) {
        OFF -> "Aus (System-DNS)"
        CUSTOM -> "Eigener Anbieter" + (customUrl.toHttpUrlOrNull()?.host?.let { " ($it)" } ?: "")
        else -> PROVIDERS.firstOrNull { it.id == mode }?.label ?: "Aus (System-DNS)"
    }

    /** Eigene Adresse muss https sein, z. B. https://dns.example/dns-query. */
    fun validCustomUrl(url: String): Boolean {
        val u = url.trim().toHttpUrlOrNull() ?: return false
        return u.isHttps && u.host.isNotEmpty()
    }

    private val ipv4 = Regex("""^\d{1,3}(\.\d{1,3}){3}$""")
    private val localSuffixes = listOf(".local", ".lan", ".home", ".internal", ".localdomain", ".home.arpa", ".fritz.box", ".box")

    /** Diese Namen löst immer das normale System-DNS auf (Heimnetz, Router, IP-Adressen). */
    fun useSystem(host: String): Boolean {
        val h = host.trim().trimEnd('.').lowercase().removePrefix("[").removeSuffix("]")
        if (h.isEmpty() || h == "localhost" || h.endsWith(".localhost")) return true
        if (ipv4.matches(h) || ':' in h) return true
        if ('.' !in h) return true
        return localSuffixes.any { h.endsWith(it) }
    }

    /** Baut den Auflöser; null bei „Aus“ oder ungültiger eigener Adresse. */
    fun create(mode: String, customUrl: String, base: OkHttpClient, onFallback: (Throwable) -> Unit): SecureResolver? {
        val (url, bootstrap) = when (mode) {
            OFF -> return null
            CUSTOM -> if (validCustomUrl(customUrl)) customUrl.trim() to emptyList() else return null
            else -> PROVIDERS.firstOrNull { it.id == mode }?.let { it.url to it.bootstrap } ?: return null
        }
        val client = base.newBuilder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .callTimeout(8, TimeUnit.SECONDS)
            .build()
        val b = DnsOverHttps.Builder().client(client).url(url.toHttpUrlOrNull()!!).includeIPv6(true)
        if (bootstrap.isNotEmpty()) b.bootstrapDnsHosts(bootstrap.map { InetAddress.getByName(it) })
        return SecureResolver(b.build(), Dns.SYSTEM, onFallback)
    }
}

/**
 * Löst über DNS-über-HTTPS auf. Heimnetz-Namen gehen an das System-DNS. Ist der Anbieter nicht
 * erreichbar (Netzwerkfehler), wird für diesen Namen das System-DNS genommen und [onFallback] gemeldet.
 * Gibt es den Namen beim Anbieter nicht (NXDOMAIN), bleibt es bei „nicht gefunden“.
 */
class SecureResolver(
    private val doh: Dns,
    private val system: Dns,
    private val onFallback: (Throwable) -> Unit,
) : Dns {
    private data class Entry(val addrs: List<InetAddress>, val until: Long)
    private val cache = ConcurrentHashMap<String, Entry>()

    override fun lookup(hostname: String): List<InetAddress> {
        if (SecureDns.useSystem(hostname)) return system.lookup(hostname)
        val key = hostname.lowercase()
        val now = System.currentTimeMillis()
        cache[key]?.takeIf { it.until > now }?.let { return it.addrs }
        val addrs = try {
            doh.lookup(hostname)
        } catch (e: UnknownHostException) {
            // Ohne Ursache = Antwort des Anbieters „gibt es nicht“; mit Ursache = Anbieter nicht erreichbar.
            if (e.cause == null) throw e
            onFallback(e)
            return system.lookup(hostname)
        }
        if (cache.size > 2_000) cache.clear()
        cache[key] = Entry(addrs, now + CACHE_MS)
        return addrs
    }

    companion object {
        const val CACHE_MS = 5 * 60_000L
    }
}
