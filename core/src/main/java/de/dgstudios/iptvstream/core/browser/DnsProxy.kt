package de.dgstudios.iptvstream.core.browser

import okhttp3.Dns
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URI
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * Kleiner Proxy nur auf 127.0.0.1, über den die Browser-WebView läuft (WebView ProxyController).
 * Er löst Namen mit [dns] auf und verbindet direkt: HTTPS per CONNECT-Tunnel (Verschlüsselung und
 * Zertifikatsprüfung bleiben komplett in der WebView), HTTP wird weitergereicht. Inhalte werden nicht
 * gelesen oder verändert; Cookies, Weiterleitungen, Video-Bereiche, POST und WebSockets laufen unverändert.
 */
class DnsProxy(@Volatile var dns: Dns) {
    private var server: ServerSocket? = null
    private var pool: ExecutorService? = null

    val port: Int get() = server?.localPort ?: 0
    val running: Boolean get() = server?.isClosed == false

    @Synchronized
    fun start(): Int {
        server?.takeIf { !it.isClosed }?.let { return it.localPort }
        val n = AtomicInteger()
        val p = Executors.newCachedThreadPool { r -> Thread(r, "dns-proxy-${n.incrementAndGet()}").apply { isDaemon = true } }
        val s = ServerSocket(0, 64, InetAddress.getByName("127.0.0.1"))
        server = s
        pool = p
        p.execute {
            while (!s.isClosed) {
                val c = try { s.accept() } catch (_: IOException) { break }
                try {
                    p.execute { handle(c) }
                } catch (_: Exception) {
                    closeQuietly(c)
                }
            }
        }
        return s.localPort
    }

    @Synchronized
    fun stop() {
        closeQuietly(server)
        server = null
        pool?.shutdownNow()
        pool = null
    }

    private fun handle(client: Socket) {
        var upstream: Socket? = null
        try {
            client.soTimeout = HEAD_TIMEOUT_MS
            client.tcpNoDelay = true
            val input = BufferedInputStream(client.getInputStream(), 16 * 1024)
            val head = readHead(input) ?: return closeQuietly(client)
            val lines = head.split("\r\n")
            val parts = lines.first().split(' ')
            if (parts.size < 3) return reply(client, "400 Bad Request")
            val (method, target, version) = Triple(parts[0], parts[1], parts[2])
            val out = client.getOutputStream()
            if (method.equals("CONNECT", ignoreCase = true)) {
                val (host, port) = splitHostPort(target, 443) ?: return reply(client, "400 Bad Request")
                upstream = connect(host, port) ?: return reply(client, "502 Bad Gateway")
                out.write("HTTP/1.1 200 Connection Established\r\n\r\n".toByteArray(Charsets.ISO_8859_1))
                out.flush()
                tunnel(client, input, upstream)
            } else {
                val uri = try { URI(target) } catch (_: Exception) { null }
                val host = uri?.host ?: return reply(client, "400 Bad Request")
                if (uri.scheme?.lowercase() != "http") return reply(client, "400 Bad Request")
                val port = if (uri.port > 0) uri.port else 80
                val path = (uri.rawPath?.ifEmpty { "/" } ?: "/") + (uri.rawQuery?.let { "?$it" } ?: "")
                upstream = connect(host, port) ?: return reply(client, "502 Bad Gateway")
                val sb = StringBuilder("$method $path $version\r\n")
                for (h in lines.drop(1)) {
                    if (h.isEmpty()) continue
                    val name = h.substringBefore(':').trim().lowercase()
                    if (name in HOP_HEADERS) continue
                    sb.append(h).append("\r\n")
                }
                // Eine Verbindung pro Anfrage: so landet nie eine Anfrage beim falschen Server.
                sb.append("Connection: close\r\n\r\n")
                upstream.getOutputStream().apply { write(sb.toString().toByteArray(Charsets.ISO_8859_1)); flush() }
                tunnel(client, input, upstream)
            }
        } catch (_: Exception) {
            closeQuietly(upstream)
            closeQuietly(client)
        }
    }

    private fun connect(host: String, port: Int): Socket? {
        val addrs = try { dns.lookup(host) } catch (_: Exception) { return null }
        for ((i, a) in addrs.withIndex()) {
            val s = Socket()
            try {
                // Hängt eine Adresse (z. B. IPv6 ohne Verbindung), schnell die nächste probieren.
                s.connect(InetSocketAddress(a, port), if (i < addrs.size - 1) FIRST_TRY_TIMEOUT_MS else CONNECT_TIMEOUT_MS)
                s.tcpNoDelay = true
                return s
            } catch (_: IOException) {
                closeQuietly(s)
            }
        }
        return null
    }

    /** Daten in beide Richtungen durchreichen, bis eine Seite schließt. */
    private fun tunnel(client: Socket, clientIn: InputStream, upstream: Socket) {
        client.soTimeout = IDLE_TIMEOUT_MS
        upstream.soTimeout = IDLE_TIMEOUT_MS
        val up = Thread({
            pump(clientIn, upstream.getOutputStream())
            runCatching { upstream.shutdownOutput() }
        }, "dns-proxy-up").apply { isDaemon = true; start() }
        pump(upstream.getInputStream(), client.getOutputStream())
        runCatching { client.shutdownOutput() }
        up.join(IDLE_TIMEOUT_MS.toLong())
        closeQuietly(upstream)
        closeQuietly(client)
    }

    private fun pump(from: InputStream, to: OutputStream) {
        val buf = ByteArray(32 * 1024)
        try {
            while (true) {
                val n = from.read(buf)
                if (n < 0) break
                to.write(buf, 0, n)
                to.flush()
            }
        } catch (_: SocketTimeoutException) {
        } catch (_: IOException) {
        }
    }

    private fun reply(client: Socket, status: String) {
        runCatching {
            client.getOutputStream().apply {
                write("HTTP/1.1 $status\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray(Charsets.ISO_8859_1))
                flush()
            }
        }
        closeQuietly(client)
    }

    companion object {
        const val HEAD_TIMEOUT_MS = 30_000
        const val CONNECT_TIMEOUT_MS = 15_000
        const val FIRST_TRY_TIMEOUT_MS = 5_000
        const val IDLE_TIMEOUT_MS = 5 * 60_000
        private const val MAX_HEAD = 64 * 1024
        private val HOP_HEADERS = setOf("proxy-connection", "proxy-authorization", "connection", "keep-alive")

        /** Liest bis zur Leerzeile; null bei Verbindungsende oder zu großem Kopf. */
        internal fun readHead(input: InputStream): String? {
            val buf = ByteArrayOutputStream(1024)
            var state = 0
            while (buf.size() < MAX_HEAD) {
                val b = input.read()
                if (b < 0) return null
                buf.write(b)
                state = when {
                    b == '\r'.code && (state == 0 || state == 2) -> state + 1
                    b == '\n'.code && (state == 1 || state == 3) -> state + 1
                    else -> 0
                }
                if (state == 4) return buf.toString(Charsets.ISO_8859_1.name()).removeSuffix("\r\n\r\n")
            }
            return null
        }

        /** „host:443“ oder „[::1]:443“. */
        internal fun splitHostPort(target: String, defaultPort: Int): Pair<String, Int>? {
            if (target.startsWith("[")) {
                val end = target.indexOf(']')
                if (end < 0) return null
                val host = target.substring(1, end)
                val port = target.substring(end + 1).removePrefix(":").toIntOrNull() ?: defaultPort
                return host to port
            }
            val i = target.lastIndexOf(':')
            if (i < 0) return target to defaultPort
            val port = target.substring(i + 1).toIntOrNull() ?: return null
            return target.substring(0, i) to port
        }

        private fun closeQuietly(c: java.io.Closeable?) {
            try { c?.close() } catch (_: Exception) {}
        }
    }
}
