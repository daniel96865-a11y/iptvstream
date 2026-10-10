package de.dgstudios.iptvstream.core.pairing

import de.dgstudios.iptvstream.core.data.db.ProfileEntity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InterruptedIOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Winziger HTTP-Server für genau eine Profil-Übertragung.
 * [submit] prüft und speichert das Profil. Rückgabe null heißt Erfolg, sonst die Fehlermeldung.
 * Der Aufruf blockiert den Server-Thread, damit die Antwort erst nach dem Speichern rausgeht.
 * Zugangsdaten werden nicht protokolliert.
 */
class LanPairingServer(
    private val submit: (ProfileEntity) -> String?,
    private val timeoutMs: Long = LanPairing.TIMEOUT_MS,
    private val now: () -> Long = { System.currentTimeMillis() },
    private val bindHost: String? = null,
    private val preferredPort: Int = LanPairing.PREFERRED_PORT,
) {
    sealed class Phase {
        data object Idle : Phase()
        data class Waiting(val offer: LanPairing.Offer, val message: String?) : Phase()
        data class Checking(val offer: LanPairing.Offer) : Phase()
        data object Saved : Phase()
        data class Stopped(val message: String) : Phase()
    }

    private val _phase = MutableStateFlow<Phase>(Phase.Idle)
    val phase: StateFlow<Phase> = _phase

    @Volatile
    private var running = false
    private var socket: ServerSocket? = null
    private var worker: Thread? = null
    private val used = AtomicBoolean(false)
    private val busy = AtomicBoolean(false)
    private val failures = AtomicInteger(0)
    private var offer: LanPairing.Offer? = null

    /**
     * Startet den Server. [listenHost] ist die IPv4 des WLANs. Daran wird gebunden,
     * weil eine Wildcard-Adresse auf Android TV oft keine Verbindungen aus dem WLAN annimmt.
     * Null, wenn kein Heimnetz da ist oder kein Port frei ist.
     */
    fun start(listenHost: String? = bindHost): LanPairing.Offer? {
        if (running) return offer
        val explicit = listenHost?.let { LanPairing.literalV4(it) }
        val advertise = explicit ?: LanPairing.lanIpv4()
        if (advertise == null) {
            _phase.value = Phase.Stopped("Kein WLAN. Fernseher und Handy müssen im selben Netzwerk sein.")
            return null
        }
        val server = try {
            openSocket(advertise)
        } catch (e: IOException) {
            try {
                openSocket(InetAddress.getByAddress(byteArrayOf(0, 0, 0, 0)))
            } catch (e2: IOException) {
                _phase.value = Phase.Stopped("Der Fernseher kann gerade keine Verbindung annehmen. Bitte erneut versuchen.")
                return null
            }
        }
        val hostText = advertise.hostAddress
        if (hostText.isNullOrEmpty()) {
            server.close()
            _phase.value = Phase.Stopped("Keine lokale Adresse gefunden.")
            return null
        }
        val secrets = LanPairing.newSecrets()
        val ready = LanPairing.Offer(
            host = hostText,
            port = server.localPort,
            token = secrets.token,
            code = secrets.code,
            deadlineEpochMs = now() + timeoutMs,
        )
        offer = ready
        socket = server
        running = true
        _phase.value = Phase.Waiting(ready, null)
        worker = Thread({ loop(server, ready) }, "iptv-pair").apply { isDaemon = true; start() }
        return ready
    }

    fun stop() {
        running = false
        try {
            socket?.close()
        } catch (e: IOException) {
        }
    }

    fun join(timeoutMs: Long = 2_000) {
        worker?.join(timeoutMs)
    }

    private fun openSocket(addr: InetAddress): ServerSocket {
        if (preferredPort == 0) {
            return ServerSocket().apply {
                reuseAddress = true
                bind(InetSocketAddress(addr, 0))
            }
        }
        var last: IOException? = null
        for (port in preferredPort until preferredPort + LanPairing.PORT_SPAN) {
            try {
                return ServerSocket().apply {
                    reuseAddress = true
                    bind(InetSocketAddress(addr, port))
                }
            } catch (e: IOException) {
                last = e
            }
        }
        throw last ?: IOException("port")
    }

    private fun loop(server: ServerSocket, ready: LanPairing.Offer) {
        try {
            server.soTimeout = 1000
        } catch (e: IOException) {
        }
        try {
            while (running && !used.get()) {
                if (now() >= ready.deadlineEpochMs) {
                    _phase.value = Phase.Stopped("Die Zeit ist abgelaufen. Bitte erneut auf „Mit Handy übertragen“ tippen.")
                    return
                }
                if (failures.get() >= MAX_FAILURES) {
                    _phase.value = Phase.Stopped("Zu viele Fehlversuche. Bitte die Übertragung neu starten.")
                    return
                }
                val client = try {
                    server.accept()
                } catch (e: InterruptedIOException) {
                    continue
                } catch (e: IOException) {
                    if (!running || server.isClosed) return
                    try {
                        Thread.sleep(100)
                    } catch (ie: InterruptedException) {
                        return
                    }
                    continue
                }
                try {
                    client.soTimeout = 8_000
                    handle(client, ready)
                } catch (e: IOException) {
                    // Verbindung abgebrochen. Inhalt der Anfrage wird nicht protokolliert.
                } finally {
                    try {
                        client.close()
                    } catch (e: IOException) {
                    }
                }
            }
        } finally {
            running = false
            try {
                server.close()
            } catch (e: IOException) {
            }
            if (_phase.value is Phase.Waiting || _phase.value is Phase.Checking || _phase.value is Phase.Idle) {
                if (!used.get()) _phase.value = Phase.Stopped("Übertragung beendet.")
            }
        }
    }

    private fun handle(client: Socket, ready: LanPairing.Offer) {
        if (!LanPairing.isAllowedClient(client.inetAddress)) {
            write(client, 403, "text/plain; charset=UTF-8", "Nur im selben Heimnetz.".toByteArray(Charsets.UTF_8))
            return
        }
        val req = readRequest(client) ?: run {
            write(client, 400, "text/plain; charset=UTF-8", "Anfrage ungültig.".toByteArray(Charsets.UTF_8))
            return
        }
        if (req.method == "GET" && req.path == "/who") {
            write(client, 200, "text/plain; charset=UTF-8", WHO)
            return
        }
        val presented = req.query["token"]
        if (!LanPairing.matchesSecret(presented, ready.token, ready.code)) {
            if (!presented.isNullOrEmpty()) failures.incrementAndGet()
            write(client, 404, "text/plain; charset=UTF-8", ByteArray(0))
            return
        }
        when (req.method) {
            "GET" -> when (req.path) {
                "/", "/pair" -> {
                    val page = LanPairing.htmlPage(ready).toByteArray(Charsets.UTF_8)
                    write(client, 200, "text/html; charset=UTF-8", page)
                }
                "/probe" -> write(client, 200, "application/json; charset=UTF-8", PROBE_OK)
                else -> write(client, 404, "text/plain; charset=UTF-8", ByteArray(0))
            }
            "POST" -> if (req.path == "/pair") postProfile(client, ready, req) else {
                write(client, 404, "text/plain; charset=UTF-8", ByteArray(0))
            }
            else -> write(client, 405, "text/plain; charset=UTF-8", ByteArray(0))
        }
    }

    private fun postProfile(client: Socket, ready: LanPairing.Offer, req: Request) {
        if (used.get()) {
            respond(client, req, false, "Der Code wurde bereits verwendet.")
            return
        }
        val profile = LanPairing.profileFromFields(LanPairing.parseForm(req.body))
        val invalid = LanPairing.validate(profile)
        if (invalid != null) {
            _phase.value = Phase.Waiting(ready, invalid)
            respond(client, req, false, invalid)
            return
        }
        if (!busy.compareAndSet(false, true)) {
            respond(client, req, false, "Es wird schon eine Anmeldung geprüft.")
            return
        }
        _phase.value = Phase.Checking(ready)
        val error = try {
            submit(profile)
        } catch (e: Exception) {
            "Die Zugangsdaten konnten nicht geprüft werden."
        }
        if (error != null) {
            busy.set(false)
            _phase.value = Phase.Waiting(ready, error)
            respond(client, req, false, error)
            return
        }
        used.set(true)
        _phase.value = Phase.Saved
        running = false
        respond(client, req, true, "Der Fernseher speichert das Profil und lädt die Sender.")
    }

    private fun respond(client: Socket, req: Request, ok: Boolean, message: String) {
        val wantJson = req.headers["accept"]?.contains("application/json") == true
        if (wantJson) {
            val body = LanPairing.jsonResult(ok, if (ok) null else message).toByteArray(Charsets.UTF_8)
            write(client, if (ok) 200 else 400, "application/json; charset=UTF-8", body)
        } else {
            val html = LanPairing.htmlResult(ok, message, if (ok) null else offer?.token).toByteArray(Charsets.UTF_8)
            write(client, if (ok) 200 else 400, "text/html; charset=UTF-8", html)
        }
    }

    private fun readRequest(client: Socket): Request? {
        val input = client.getInputStream()
        val header = ByteArrayOutputStream()
        var last = 0
        while (header.size() < 8 * 1024) {
            val b = input.read()
            if (b < 0) return null
            header.write(b)
            last = (last shl 8) or b
            if (last == 0x0d0a0d0a || (last and 0xffff) == 0x0a0a) break
        }
        if (header.size() >= 8 * 1024 && last != 0x0d0a0d0a && (last and 0xffff) != 0x0a0a) return null
        val text = header.toString(Charsets.ISO_8859_1.name()).replace("\r\n", "\n").replace('\r', '\n')
        val lines = text.split('\n')
        if (lines.isEmpty()) return null
        val parts = lines[0].split(' ')
        if (parts.size < 2) return null
        val method = parts[0].uppercase(Locale.US)
        val rawPath = parts[1]
        val q = rawPath.indexOf('?')
        val path = if (q >= 0) rawPath.substring(0, q) else rawPath
        val query = if (q >= 0) LanPairing.parseForm(rawPath.substring(q + 1)) else emptyMap()
        val headers = HashMap<String, String>()
        for (i in 1 until lines.size) {
            val line = lines[i]
            if (line.isEmpty()) break
            val c = line.indexOf(':')
            if (c <= 0) continue
            headers[line.substring(0, c).trim().lowercase(Locale.US)] = line.substring(c + 1).trim()
        }
        val len = headers["content-length"]?.toIntOrNull() ?: 0
        if (len < 0) return null
        if (len > LanPairing.MAX_BODY_BYTES) {
            write(client, 413, "text/plain; charset=UTF-8", "Zu groß.".toByteArray(Charsets.UTF_8))
            throw IOException("body")
        }
        val bodyBytes = ByteArray(len)
        var off = 0
        while (off < len) {
            val n = input.read(bodyBytes, off, len - off)
            if (n < 0) break
            off += n
        }
        if (off != len) return null
        return Request(method, path, query, headers, bodyBytes.toString(Charsets.UTF_8))
    }

    private fun write(client: Socket, status: Int, contentType: String, body: ByteArray) {
        val reason = when (status) {
            200 -> "OK"
            400 -> "Bad Request"
            403 -> "Forbidden"
            404 -> "Not Found"
            405 -> "Method Not Allowed"
            413 -> "Payload Too Large"
            else -> "Error"
        }
        val head = "HTTP/1.1 $status $reason\r\n" +
            "Content-Type: $contentType\r\n" +
            "Content-Length: ${body.size}\r\n" +
            "Connection: close\r\n" +
            "Cache-Control: no-store\r\n" +
            "X-Content-Type-Options: nosniff\r\n" +
            "Referrer-Policy: no-referrer\r\n" +
            "\r\n"
        val out = client.getOutputStream()
        out.write(head.toByteArray(Charsets.US_ASCII))
        out.write(body)
        out.flush()
    }

    private data class Request(
        val method: String,
        val path: String,
        val query: Map<String, String>,
        val headers: Map<String, String>,
        val body: String,
    )

    private companion object {
        const val MAX_FAILURES = 8
        val PROBE_OK = """{"ok":true}""".toByteArray(Charsets.UTF_8)
        val WHO = "iptvstream".toByteArray(Charsets.UTF_8)
    }
}
