package de.dgstudios.iptvstream.core.pairing

import de.dgstudios.iptvstream.core.data.db.ProfileEntity
import java.io.IOException
import java.net.HttpURLConnection
import java.net.Proxy
import java.net.SocketTimeoutException
import java.net.URL

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

    private fun readBody(conn: HttpURLConnection, code: Int): String {
        val stream = if (code >= 400) conn.errorStream else conn.inputStream
        return stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
    }

    private const val UNREACHABLE =
        "Der Fernseher ist nicht erreichbar. Beide Geräte müssen im selben WLAN sein, und der Code muss noch auf dem Fernseher stehen."
}
