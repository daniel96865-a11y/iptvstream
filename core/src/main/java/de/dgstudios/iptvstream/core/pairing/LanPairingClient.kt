package de.dgstudios.iptvstream.core.pairing

import de.dgstudios.iptvstream.core.data.db.ProfileEntity
import java.io.IOException
import java.net.HttpURLConnection
import java.net.Proxy
import java.net.SocketTimeoutException
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Schickt ein Profil an den Fernseher. Null heißt: der Fernseher hat es übernommen. */
object LanPairingClient {
    fun send(
        host: String,
        port: Int,
        secret: String,
        profile: ProfileEntity,
        allowLoopback: Boolean = false,
        opener: (URL) -> HttpURLConnection = { it.openConnection(Proxy.NO_PROXY) as HttpURLConnection },
    ): String? {
        if (!LanPairing.isPrivateHost(host, allowLoopback) || port !in 1..65535 || secret.isBlank()) {
            return "Diese Adresse liegt nicht im Heimnetz."
        }
        val url = URL("http://$host:$port/pair?token=${java.net.URLEncoder.encode(secret, "UTF-8")}")
        return exchange(url, opener, "POST", LanPairing.formEncode(profile).toByteArray(Charsets.UTF_8), 5_000, 90_000)
    }

    /** Prüft, ob an dieser Adresse der Fernseher mit genau diesem Code lauscht. */
    fun probe(
        host: String,
        port: Int,
        secret: String,
        allowLoopback: Boolean = false,
        opener: (URL) -> HttpURLConnection = { it.openConnection(Proxy.NO_PROXY) as HttpURLConnection },
    ): Boolean {
        if (!LanPairing.isPrivateHost(host, allowLoopback) || port !in 1..65535 || secret.isBlank()) return false
        val url = URL("http://$host:$port/probe?token=${java.net.URLEncoder.encode(secret, "UTF-8")}")
        val conn = try {
            opener(url)
        } catch (e: IOException) {
            return false
        }
        return try {
            conn.requestMethod = "GET"
            conn.instanceFollowRedirects = false
            conn.connectTimeout = 800
            conn.readTimeout = 800
            conn.setRequestProperty("Accept", "application/json")
            conn.setRequestProperty("Connection", "close")
            val code = conn.responseCode
            val text = readBody(conn, code)
            code == 200 && LanPairing.parseAck(text).first
        } catch (e: IOException) {
            false
        } finally {
            conn.disconnect()
        }
    }

    /** True, wenn dort der Fernseher lauscht. Der Code wird dabei nicht mitgeschickt. */
    fun present(
        host: String,
        port: Int,
        allowLoopback: Boolean = false,
        opener: (URL) -> HttpURLConnection = { it.openConnection(Proxy.NO_PROXY) as HttpURLConnection },
    ): Boolean {
        if (!LanPairing.isPrivateHost(host, allowLoopback) || port !in 1..65535) return false
        val conn = try {
            opener(URL("http://$host:$port/who"))
        } catch (e: IOException) {
            return false
        }
        return try {
            conn.requestMethod = "GET"
            conn.instanceFollowRedirects = false
            conn.connectTimeout = 400
            conn.readTimeout = 400
            conn.setRequestProperty("Connection", "close")
            conn.responseCode == 200 && readBody(conn, 200).trim() == "iptvstream"
        } catch (e: IOException) {
            false
        } finally {
            conn.disconnect()
        }
    }

    /**
     * Sucht im Subnetz einen Fernseher, der den Code kennt.
     * Zuerst die Kennung, danach die Prüfung mit dem Code, damit der Code nicht an fremde Geräte geht.
     */
    fun findService(
        hosts: List<String>,
        secret: String,
        allowLoopback: Boolean = false,
        ports: IntRange = LanPairing.ports,
        opener: (URL) -> HttpURLConnection = { it.openConnection(Proxy.NO_PROXY) as HttpURLConnection },
    ): LanPairing.Endpoint? {
        if (secret.isBlank() || ports.isEmpty()) return null
        val candidates = hosts.filter { LanPairing.isPrivateHost(it, allowLoopback) }
        if (candidates.isEmpty()) return null
        val ordered = ArrayList<Int>(ports.count())
        ordered.add(ports.first)
        for (port in ports) if (port != ports.first) ordered.add(port)
        for (port in ordered) {
            val skip = HashSet<String>()
            while (skip.size < 4) {
                val hit = scanPort(candidates.filter { it !in skip }, port, allowLoopback, opener) ?: break
                if (probe(hit.host, hit.port, secret, allowLoopback, opener)) return hit
                skip.add(hit.host)
            }
        }
        return null
    }

    fun connectFailure(message: String?): Boolean =
        message == UNREACHABLE || message?.startsWith("Der Fernseher antwortet nicht") == true

    /** Probiert die festen Ports an einer vom Nutzer genannten IP. */
    fun findOnHost(
        host: String,
        secret: String,
        allowLoopback: Boolean = false,
        ports: IntRange = LanPairing.ports,
        opener: (URL) -> HttpURLConnection = { it.openConnection(Proxy.NO_PROXY) as HttpURLConnection },
    ): LanPairing.Endpoint? {
        if (!LanPairing.isPrivateHost(host, allowLoopback)) return null
        for (port in ports) {
            if (probe(host, port, secret, allowLoopback, opener)) return LanPairing.Endpoint(host, port)
        }
        return null
    }

    private fun exchange(
        url: URL,
        opener: (URL) -> HttpURLConnection,
        method: String,
        body: ByteArray?,
        connectMs: Int,
        readMs: Int,
    ): String? {
        val conn = try {
            opener(url)
        } catch (e: IOException) {
            return UNREACHABLE
        }
        return try {
            conn.requestMethod = method
            conn.instanceFollowRedirects = false
            conn.connectTimeout = connectMs
            conn.readTimeout = readMs
            conn.setRequestProperty("Accept", "application/json")
            conn.setRequestProperty("Connection", "close")
            if (body != null) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
                conn.setRequestProperty("Content-Length", body.size.toString())
                conn.outputStream.use { it.write(body) }
            }
            val code = conn.responseCode
            if (code in 300..399) return "Die Übertragung wurde abgelehnt."
            val text = readBody(conn, code)
            val (ok, error) = LanPairing.parseAck(text)
            when {
                ok && code == 200 -> null
                !error.isNullOrBlank() -> error
                code == 404 -> "Der Code ist ungültig oder abgelaufen."
                else -> "Die Übertragung ist fehlgeschlagen."
            }
        } catch (e: SocketTimeoutException) {
            "Der Fernseher antwortet nicht. Bitte den Code prüfen und erneut senden."
        } catch (e: IOException) {
            UNREACHABLE
        } finally {
            conn.disconnect()
        }
    }

    private fun scanPort(
        hosts: List<String>,
        port: Int,
        allowLoopback: Boolean,
        opener: (URL) -> HttpURLConnection,
    ): LanPairing.Endpoint? {
        if (hosts.isEmpty() || port !in 1..65535) return null
        if (hosts.size == 1) {
            val host = hosts[0]
            return if (present(host, port, allowLoopback, opener)) LanPairing.Endpoint(host, port) else null
        }
        val found = AtomicReference<LanPairing.Endpoint?>(null)
        val pool = Executors.newFixedThreadPool(minOf(32, hosts.size))
        val latch = CountDownLatch(hosts.size)
        try {
            for (host in hosts) {
                pool.execute {
                    try {
                        if (found.get() == null && present(host, port, allowLoopback, opener)) {
                            found.compareAndSet(null, LanPairing.Endpoint(host, port))
                        }
                    } finally {
                        latch.countDown()
                    }
                }
            }
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8)
            while (found.get() == null && latch.count > 0 && System.nanoTime() < deadline) {
                latch.await(100, TimeUnit.MILLISECONDS)
            }
        } finally {
            pool.shutdownNow()
        }
        return found.get()
    }

    private fun readBody(conn: HttpURLConnection, code: Int): String {
        val stream = if (code >= 400) conn.errorStream else conn.inputStream
        return stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
    }

    private const val UNREACHABLE =
        "Der Fernseher ist nicht erreichbar. Beide Geräte müssen im selben WLAN sein. Die Adresse steht auf dem Fernseher unter dem Code."
}
