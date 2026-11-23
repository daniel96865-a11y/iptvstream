package de.dgstudios.iptvstream.core.browser

import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ServerSocket
import java.net.Socket
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

private fun dns(f: (String) -> List<InetAddress>): Dns = object : Dns {
    override fun lookup(hostname: String) = f(hostname)
}

class SecureDnsTest {
    @Test
    fun homeNetworkAlwaysUsesSystemDns() {
        for (h in listOf("192.168.1.1", "10.0.0.5", "localhost", "fritz.box", "nas.local", "router", "[::1]", "fe80::1", "drucker.lan", "speedport.ip.home.arpa")) {
            assertTrue(h, SecureDns.useSystem(h))
        }
        for (h in listOf("example.com", "www.zdf.de", "dns.google")) assertFalse(h, SecureDns.useSystem(h))
    }

    @Test
    fun customProviderBootstrapTriesEncryptedResolversFirst() {
        val ip = InetAddress.getByName("203.0.113.7")
        val sys = InetAddress.getByName("198.51.100.1")
        val calls = mutableListOf<String>()
        val failing = dns { calls += "a"; throw UnknownHostException("weg") }
        val working = dns { calls += "b"; listOf(ip) }
        val system = dns { calls += "sys"; listOf(sys) }
        assertEquals(listOf(ip), BootstrapDns(listOf(failing, working), system).lookup("dns.example"))
        assertEquals(listOf("a", "b"), calls)
        calls.clear()
        assertEquals(listOf(sys), BootstrapDns(listOf(failing), system).lookup("dns.example"))
        assertEquals(listOf("a", "sys"), calls)
        calls.clear()
        BootstrapDns(listOf(working), system).lookup("fritz.box")
        assertEquals(listOf("sys"), calls)
    }

    @Test
    fun customUrlMustBeHttps() {
        assertTrue(SecureDns.validCustomUrl("https://dns.example/dns-query"))
        assertFalse(SecureDns.validCustomUrl("http://dns.example/dns-query"))
        assertFalse(SecureDns.validCustomUrl("dns.example"))
    }

    @Test
    fun offCreatesNoResolver() {
        assertEquals(null, SecureDns.create(SecureDns.OFF, "", OkHttpClient()) {})
        assertEquals(null, SecureDns.create(SecureDns.CUSTOM, "http://x", OkHttpClient()) {})
    }

    private val loop = listOf(InetAddress.getByName("127.0.0.1"))

    @Test
    fun resolverFallsBackOnNetworkErrorButNotOnNxdomain() {
        var fallbacks = 0
        val system = dns { loop }
        val broken = dns { throw UnknownHostException("weg").apply { initCause(IOException("timeout")) } }
        val r = SecureResolver(broken, system) { fallbacks++ }
        assertEquals(loop, r.lookup("example.com"))
        assertEquals(1, fallbacks)
        val nx = SecureResolver(dns { throw UnknownHostException("example.invalid: NXDOMAIN") }, system) { fallbacks++ }
        try {
            nx.lookup("example.invalid")
            throw AssertionError("erwartet: UnknownHostException")
        } catch (_: UnknownHostException) {
        }
        assertEquals(1, fallbacks)
        // Heimnetz fragt den Anbieter gar nicht.
        assertEquals(loop, SecureResolver(broken, system) { fallbacks++ }.lookup("192.168.1.1").let { loop })
    }

    /** Mini-Webserver: antwortet mit Pfad und Host-Kopf. */
    private fun server(): ServerSocket {
        val s = ServerSocket(0, 10, InetAddress.getByName("127.0.0.1"))
        thread(isDaemon = true) {
            while (!s.isClosed) {
                val c = try { s.accept() } catch (_: IOException) { break }
                thread(isDaemon = true) {
                    c.use {
                        val head = DnsProxy.readHead(c.getInputStream()) ?: return@thread
                        val first = head.lineSequence().first()
                        val host = head.lineSequence().firstOrNull { it.startsWith("Host:", true) }?.substringAfter(':')?.trim()
                        val body = "$first|$host"
                        c.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: ${body.length}\r\n\r\n$body".toByteArray())
                        c.getOutputStream().flush()
                    }
                }
            }
        }
        return s
    }

    @Test
    fun proxyForwardsPlainHttpWithItsOwnDns() {
        val web = server()
        val asked = ArrayList<String>()
        val proxy = DnsProxy(dns { h -> asked += h; loop })
        val port = proxy.start()
        try {
            val client = OkHttpClient.Builder().proxy(Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", port))).build()
            val body = client.newCall(Request.Builder().url("http://seite.example:${web.localPort}/a/b?x=1").build()).execute().use { it.body!!.string() }
            assertEquals("GET /a/b?x=1 HTTP/1.1|seite.example:${web.localPort}", body)
            assertEquals(listOf("seite.example"), asked)
        } finally {
            proxy.stop()
            web.close()
        }
    }

    @Test
    fun proxyTunnelsConnect() {
        val web = server()
        val proxy = DnsProxy(dns { loop })
        val port = proxy.start()
        try {
            Socket("127.0.0.1", port).use { s ->
                s.soTimeout = 5_000
                val out = s.getOutputStream()
                out.write("CONNECT tunnel.example:${web.localPort} HTTP/1.1\r\nHost: tunnel.example\r\n\r\n".toByteArray())
                out.flush()
                val input = s.getInputStream()
                val ok = DnsProxy.readHead(input)!!
                assertTrue(ok, ok.startsWith("HTTP/1.1 200"))
                out.write("GET /durch HTTP/1.1\r\nHost: tunnel.example\r\n\r\n".toByteArray())
                out.flush()
                val resp = input.readBytes().toString(Charsets.ISO_8859_1)
                assertTrue(resp, resp.endsWith("GET /durch HTTP/1.1|tunnel.example"))
            }
            // Unbekannter Name → 502 statt Hängen.
            val bad = DnsProxy(dns { h -> throw UnknownHostException(h) })
            val p2 = bad.start()
            Socket("127.0.0.1", p2).use { s ->
                s.soTimeout = 5_000
                s.getOutputStream().write("CONNECT nix.example:443 HTTP/1.1\r\n\r\n".toByteArray())
                assertTrue(DnsProxy.readHead(s.getInputStream())!!.startsWith("HTTP/1.1 502"))
            }
            bad.stop()
        } finally {
            proxy.stop()
            web.close()
        }
    }

    @Test
    fun splitHostPort() {
        assertEquals("example.com" to 443, DnsProxy.splitHostPort("example.com:443", 80))
        assertEquals("::1" to 8443, DnsProxy.splitHostPort("[::1]:8443", 443))
        assertEquals("example.com" to 443, DnsProxy.splitHostPort("example.com", 443))
    }

    /** Echt über das Netz: Cloudflare/Quad9 DoH und eine https-Seite durch den Proxy. Nur mit DOH_LIVE=1. */
    @Test
    fun liveDohAndHttpsThroughProxy() {
        assumeTrue(System.getenv("DOH_LIVE") == "1")
        val base = OkHttpClient.Builder().callTimeout(15, TimeUnit.SECONDS).build()
        for (mode in listOf(SecureDns.CLOUDFLARE, SecureDns.QUAD9, SecureDns.GOOGLE)) {
            var fell = false
            val r = SecureDns.create(mode, "", base) { fell = true }!!
            val addrs = r.lookup("example.com")
            println("DoH $mode example.com -> $addrs (Rückfall=$fell)")
            assertTrue(addrs.isNotEmpty())
            assertFalse(fell)
        }
        // Anbieter nicht erreichbar → normales DNS + Hinweis.
        var broke = false
        val dead = SecureDns.create(SecureDns.CUSTOM, "https://doh.invalid/dns-query", base) { broke = true }!!
        println("Rückfall bei totem Anbieter: ${dead.lookup("example.com")} Hinweis=$broke")
        assertTrue(broke)
        // Nicht existierende Namen bleiben „nicht gefunden“ (kein Rückfall).
        var nxFell = false
        val cf = SecureDns.create(SecureDns.CLOUDFLARE, "", base) { nxFell = true }!!
        try { cf.lookup("gibt-es-nicht-${System.nanoTime()}.example.com"); throw AssertionError("erwartet NXDOMAIN") } catch (_: UnknownHostException) {}
        assertFalse(nxFell)
        var fell = false
        val r = SecureDns.create(SecureDns.CLOUDFLARE, "", base) { fell = true }!!
        val proxy = DnsProxy(r)
        val port = proxy.start()
        try {
            val client = base.newBuilder().proxy(Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", port))).build()
            client.newCall(Request.Builder().url("https://www.example.com/").build()).execute().use {
                println("https über Proxy: ${it.code}")
                assertEquals(200, it.code)
            }
            client.newCall(Request.Builder().url("http://example.com/").build()).execute().use {
                println("http über Proxy: ${it.code}")
                assertTrue(it.code in 200..399)
            }
            assertFalse(fell)
        } finally {
            proxy.stop()
        }
    }
}
