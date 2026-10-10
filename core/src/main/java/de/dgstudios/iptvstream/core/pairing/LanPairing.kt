package de.dgstudios.iptvstream.core.pairing

import de.dgstudios.iptvstream.core.data.db.ProfileEntity
import de.dgstudios.iptvstream.core.data.db.ProfileType
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InterfaceAddress
import java.net.NetworkInterface
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Übertragung eines IPTV-Profils vom Handy zum Fernseher, nur im Heimnetz.
 *
 * Der Fernseher startet für höchstens zehn Minuten einen kleinen HTTP-Server
 * und zeigt einen QR-Code plus einen kurzen Code. Der QR-Code enthält
 * `http://<lan-ip>:<port>/?token=<einmal-token>`. Das Handy öffnet diese Seite
 * oder die App über `iptvstream://pair`. Die Zugangsdaten gehen per POST an
 * denselben Server. Es gibt keinen Cloud-Dienst und keinen fremden Server.
 *
 * Zwei Geheimnisse, beide einmalig und nur auf dem Fernseher sichtbar:
 * - `token`: 128 Bit, hex, steht im QR-Code und in der Seitenadresse.
 * - `code`: 6 Zeichen, tippt man in der App ein. Die App findet den Fernseher
 *   per mDNS (`_iptvstream._tcp`, TXT-Attribut `code`) oder über die angezeigte IP.
 *
 * Der Server bindet an die IPv4 des WLANs, nicht an Localhost und nicht nur an
 * die Wildcard-Adresse. Er nimmt nur Clients aus dem privaten Netz an, schreibt
 * keine Zugangsdaten ins Log und stoppt nach Erfolg, Abbruch oder Timeout.
 * Ein Code gilt nur für eine erfolgreiche Übertragung.
 */
object LanPairing {
    const val TIMEOUT_MS = 10 * 60 * 1000L
    const val MAX_BODY_BYTES = 16 * 1024
    const val SERVICE_TYPE = "_iptvstream._tcp."
    const val PREFERRED_PORT = 28765
    const val PORT_SPAN = 16
    const val CODE_LENGTH = 6
    const val TOKEN_HEX_LENGTH = 32

    private const val CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
    private val random = SecureRandom()

    data class Secrets(val token: String, val code: String)

    data class Offer(
        val host: String,
        val port: Int,
        val token: String,
        val code: String,
        val deadlineEpochMs: Long,
    ) {
        val pageUrl: String get() = "http://$host:$port/?token=$token"
        val appLink: String get() = "iptvstream://pair?host=$host&port=$port&token=$token"
        val listenLabel: String get() = "$host:$port"
    }

    /** Adresse, die der Nutzer vom Fernseher abtippt. Port fehlt, wenn nur die IP da ist. */
    data class ManualTarget(val host: String, val port: Int?)

    data class Endpoint(val host: String, val port: Int)

    fun newSecrets(): Secrets {
        val tokenBytes = ByteArray(16)
        random.nextBytes(tokenBytes)
        val token = tokenBytes.joinToString("") { "%02x".format(it) }
        val code = buildString(CODE_LENGTH) {
            repeat(CODE_LENGTH) { append(CODE_ALPHABET[random.nextInt(CODE_ALPHABET.length)]) }
        }
        return Secrets(token, code)
    }

    fun normalizeCode(raw: String): String =
        raw.uppercase().filter { it in CODE_ALPHABET }

    fun displayCode(code: String): String =
        code.chunked(3).joinToString(" ")

    fun matchesSecret(presented: String?, token: String, code: String): Boolean {
        if (presented.isNullOrEmpty()) return false
        val asToken = constantEquals(presented, token)
        val asCode = presented.length <= 16 && constantEquals(normalizeCode(presented), code)
        return asToken || asCode
    }

    fun constantEquals(a: String, b: String): Boolean {
        val x = a.toByteArray(Charsets.UTF_8)
        val y = b.toByteArray(Charsets.UTF_8)
        return MessageDigest.isEqual(x, y)
    }

    /** Nur literale private IPv4-Adressen. Hostnamen werden nicht aufgelöst. */
    fun isPrivateHost(host: String, allowLoopback: Boolean = false): Boolean {
        val addr = literalV4(host) ?: return false
        if (addr.isLoopbackAddress) return allowLoopback
        return isPrivateV4(addr)
    }

    /** Literale IPv4 ohne DNS. Null bei Hostnamen oder ungültiger Schreibweise. */
    fun literalV4(host: String): Inet4Address? {
        if (!IPV4_LITERAL.matches(host)) return null
        return parseLiteralV4(host)
    }

    /**
     * IP oder IP:Port vom Fernseher, auch wenn die ganze Seitenadresse eingefügt wurde.
     * Hostnamen werden abgelehnt.
     */
    fun manualTarget(raw: String): ManualTarget? {
        var text = raw.trim()
        if (text.isEmpty()) return null
        if (text.startsWith("http://", ignoreCase = true)) text = text.substring(7)
        else if (text.startsWith("https://", ignoreCase = true)) text = text.substring(8)
        text = text.substringBefore('/').substringBefore('?').trim()
        if (text.isEmpty()) return null
        val colon = text.lastIndexOf(':')
        val host: String
        val port: Int?
        if (colon > 0 && text.indexOf(':') == colon) {
            host = text.substring(0, colon)
            port = text.substring(colon + 1).toIntOrNull() ?: return null
            if (port !in 1..65535) return null
        } else {
            host = text
            port = null
        }
        if (!isPrivateHost(host)) return null
        return ManualTarget(host, port)
    }

    /**
     * Andere Adressen im eigenen Subnetz, ohne die eigene Adresse, Netz- und Broadcast-Adresse.
     * Größere Netze werden auf das /24 der eigenen Adresse begrenzt.
     */
    fun subnetHosts(host: String, prefixLength: Int): List<String> {
        val addr = literalV4(host) ?: return emptyList()
        if (!isPrivateV4(addr)) return emptyList()
        val prefix = if (prefixLength in 24..30) prefixLength else 24
        val ip = ipv4ToInt(addr)
        val mask = -1 shl (32 - prefix)
        val network = ip and mask
        val size = 1 shl (32 - prefix)
        val out = ArrayList<String>(size)
        for (i in 1 until size - 1) {
            val candidate = network + i
            if (candidate == ip) continue
            out.add(formatV4(candidate))
        }
        return out
    }

    fun isPrivateV4(addr: Inet4Address): Boolean {
        val b = addr.address
        val a0 = b[0].toInt() and 0xff
        val a1 = b[1].toInt() and 0xff
        if (a0 == 10) return true
        if (a0 == 192 && a1 == 168) return true
        if (a0 == 172 && a1 in 16..31) return true
        if (a0 == 169 && a1 == 254) return true
        return false
    }

    fun lanIpv4(): Inet4Address? {
        val found = ArrayList<Pair<Int, Inet4Address>>(4)
        val ifaces = NetworkInterface.getNetworkInterfaces() ?: return null
        for (nif in ifaces) {
            if (!nif.isUp || nif.isLoopback) continue
            val name = nif.name.lowercase()
            if (name.contains("dummy") || name.startsWith("docker") || name.startsWith("br-") ||
                name.startsWith("veth") || name.contains("tun") || name.contains("ppp")
            ) {
                continue
            }
            val prio = when {
                name.startsWith("wlan") || name.startsWith("wifi") || name.startsWith("wl") -> 0
                name.startsWith("eth") || name.startsWith("en") || name.startsWith("lan") -> 1
                else -> 2
            }
            for (addr in nif.inetAddresses) {
                if (addr is Inet4Address && isPrivateV4(addr)) found.add(prio to addr)
            }
        }
        return found.minByOrNull { it.first }?.second
    }

    /**
     * Client darf nur aus dem Loopback (Tests, derselbe Apparat) oder demselben privaten Subnetz kommen.
     * Android meldet die Präfixlänge oft als /32. Dann gilt dasselbe /24.
     */
    fun isAllowedClient(remote: InetAddress?): Boolean {
        if (remote == null) return false
        if (remote.isLoopbackAddress) return true
        val v4 = asV4(remote) ?: return false
        if (!isPrivateV4(v4)) return false
        val nets = localNets()
        if (nets.isEmpty()) return true
        val ip = ipv4ToInt(v4)
        if (nets.any { it.contains(ip) }) return true
        return nets.any { same24(it.network, ip) }
    }

    fun profileFromFields(fields: Map<String, String>): ProfileEntity {
        val typeRaw = fields["type"].orEmpty().trim().uppercase()
        val type = when (typeRaw) {
            ProfileType.M3U -> ProfileType.M3U
            ProfileType.XTREAM -> ProfileType.XTREAM
            else -> typeRaw
        }
        return ProfileEntity(
            name = fields["name"].orEmpty().trim(),
            type = type,
            url = fields["url"].orEmpty().trim(),
            username = fields["username"].orEmpty(),
            password = fields["password"].orEmpty(),
            epgUrl = fields["epgUrl"].orEmpty().trim(),
        )
    }

    /** Fehlermeldung oder null, wenn die Felder grundsätzlich brauchbar sind. */
    fun validate(p: ProfileEntity): String? {
        if (p.type != ProfileType.XTREAM && p.type != ProfileType.M3U) return "Bitte Xtream Codes oder M3U wählen."
        if (p.name.length > 80) return "Der Profilname ist zu lang."
        if (p.url.isBlank()) return "Die Adresse fehlt."
        if (p.url.length > 2000) return "Die Adresse ist zu lang."
        if (p.username.length > 200 || p.password.length > 500 || p.epgUrl.length > 2000) {
            return "Eine Eingabe ist zu lang."
        }
        if (hasControlChar(p.name) || hasControlChar(p.url) || hasControlChar(p.username) ||
            hasControlChar(p.password) || hasControlChar(p.epgUrl)
        ) {
            return "Die Eingabe enthält ungültige Zeichen."
        }
        val url = if (p.type == ProfileType.XTREAM && !p.url.startsWith("http://", true) && !p.url.startsWith("https://", true)) {
            "http://${p.url}"
        } else {
            p.url
        }
        if (!url.startsWith("http://", true) && !url.startsWith("https://", true)) {
            return "Die Adresse muss mit http:// oder https:// beginnen."
        }
        if (p.type == ProfileType.XTREAM) {
            if (p.username.isBlank() || p.password.isBlank()) return "Benutzername und Passwort fehlen."
        }
        if (p.epgUrl.isNotEmpty() && !p.epgUrl.startsWith("http://", true) && !p.epgUrl.startsWith("https://", true)) {
            return "Die EPG-Adresse muss mit http:// oder https:// beginnen."
        }
        return null
    }

    fun formEncode(p: ProfileEntity): String {
        fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
        return listOf(
            "name" to p.name,
            "type" to p.type,
            "url" to p.url,
            "username" to p.username,
            "password" to p.password,
            "epgUrl" to p.epgUrl,
        ).joinToString("&") { (k, v) -> "$k=${enc(v)}" }
    }

    fun parseForm(body: String): Map<String, String> {
        if (body.isEmpty()) return emptyMap()
        val out = LinkedHashMap<String, String>()
        for (part in body.split('&')) {
            if (part.isEmpty()) continue
            val i = part.indexOf('=')
            val key = urlDecode(if (i >= 0) part.substring(0, i) else part)
            val value = urlDecode(if (i >= 0) part.substring(i + 1) else "")
            out[key] = value
        }
        return out
    }

    fun jsonResult(ok: Boolean, error: String? = null): String {
        return if (ok) {
            """{"ok":true}"""
        } else {
            """{"ok":false,"error":"${escapeJson(error ?: "Übertragung fehlgeschlagen.")}"}"""
        }
    }

    /** Liefert (ok, Fehlermeldung). */
    fun parseAck(body: String): Pair<Boolean, String?> {
        val ok = Regex("\"ok\"\\s*:\\s*true").containsMatchIn(body)
        val raw = Regex("\"error\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"").find(body)?.groupValues?.getOrNull(1)
        return ok to raw?.let { unescapeJson(it) }
    }

    fun htmlPage(offer: Offer): String {
        val token = html(offer.token)
        val host = html(offer.host)
        val code = html(displayCode(offer.code))
        val app = html(offer.appLink)
        return """
            <!DOCTYPE html>
            <html lang="de">
            <head>
            <meta charset="utf-8">
            <meta name="viewport" content="width=device-width, initial-scale=1">
            <meta name="referrer" content="no-referrer">
            <title>IPTVstream</title>
            <style>
            body{font-family:sans-serif;background:#0e121b;color:#f2f4f8;margin:0;padding:20px}
            main{max-width:420px;margin:0 auto}
            h1{font-size:1.4rem;margin:0 0 8px}
            p{color:#b7c0d0;line-height:1.4}
            a,button,label,input,select{font-size:1rem}
            a{color:#8eb6ff}
            label{display:block;margin:14px 0 6px}
            input,select{width:100%;box-sizing:border-box;padding:12px;border-radius:12px;border:1px solid #334;background:#171d2b;color:#fff}
            button{margin-top:18px;width:100%;padding:14px;border:0;border-radius:999px;background:#3d7eff;color:#fff;font-weight:700}
            .card{background:#171d2b;border-radius:16px;padding:16px;margin:16px 0}
            </style>
            </head>
            <body>
            <main>
            <h1>An den Fernseher senden</h1>
            <p>Handy und Fernseher sind im selben WLAN. Die Zugangsdaten gehen nur an diesen Fernseher ($host) und nicht ins Internet.</p>
            <p class="card">Code auf dem Fernseher: <strong>$code</strong><br>Adresse: <strong>$host:${offer.port}</strong></p>
            <form method="post" action="/pair?token=$token" autocomplete="off">
            <label for="name">Profilname (optional)</label>
            <input id="name" name="name" maxlength="80">
            <label for="type">Art</label>
            <select id="type" name="type">
            <option value="XTREAM" selected>Xtream Codes</option>
            <option value="M3U">M3U / M3U8</option>
            </select>
            <label for="url">Server- oder Playlist-Adresse</label>
            <input id="url" name="url" inputmode="url" autocapitalize="off" autocorrect="off" spellcheck="false" required>
            <label for="username">Benutzername (nur Xtream)</label>
            <input id="username" name="username" autocapitalize="off" autocorrect="off" spellcheck="false">
            <label for="password">Passwort (nur Xtream)</label>
            <input id="password" name="password" type="password">
            <label for="epgUrl">EPG-Adresse (optional, bei M3U)</label>
            <input id="epgUrl" name="epgUrl" inputmode="url" autocapitalize="off" autocorrect="off" spellcheck="false">
            <button type="submit">An den Fernseher senden</button>
            </form>
            <p><a href="$app">Gespeichertes Profil in der IPTVstream-App senden</a></p>
            </main>
            </body>
            </html>
        """.trimIndent()
    }

    fun htmlResult(ok: Boolean, message: String, token: String?): String {
        val title = if (ok) "Geschafft" else "Nicht gesendet"
        val back = if (!ok && token != null) {
            """<p><a href="/?token=${html(token)}">Erneut versuchen</a></p>"""
        } else {
            ""
        }
        return """
            <!DOCTYPE html>
            <html lang="de">
            <head>
            <meta charset="utf-8">
            <meta name="viewport" content="width=device-width, initial-scale=1">
            <title>IPTVstream</title>
            <style>body{font-family:sans-serif;background:#0e121b;color:#f2f4f8;margin:0;padding:24px}a{color:#8eb6ff}</style>
            </head>
            <body>
            <h1>${html(title)}</h1>
            <p>${html(message)}</p>
            $back
            </body>
            </html>
        """.trimIndent()
    }

    private fun hasControlChar(s: String): Boolean = s.any { it < ' ' || it == '\u007f' }

    private fun urlDecode(s: String): String = URLDecoder.decode(s, "UTF-8")

    private fun html(s: String): String = buildString(s.length + 8) {
        for (c in s) {
            when (c) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '"' -> append("&quot;")
                '\'' -> append("&#39;")
                else -> append(c)
            }
        }
    }

    private fun escapeJson(s: String): String = buildString(s.length + 8) {
        for (c in s) {
            when (c) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                else -> append(c)
            }
        }
    }

    private fun unescapeJson(s: String): String = buildString(s.length) {
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) {
                when (s[i + 1]) {
                    '\\' -> append('\\')
                    '"' -> append('"')
                    'n' -> append('\n')
                    'r' -> append('\r')
                    else -> append(s[i + 1])
                }
                i += 2
            } else {
                append(c)
                i++
            }
        }
    }

    private data class V4Net(val network: Int, val prefix: Int) {
        fun contains(ip: Int): Boolean {
            if (prefix <= 0 || prefix > 32) return false
            val mask = if (prefix == 32) -1 else (-1 shl (32 - prefix))
            return (ip xor network) and mask == 0
        }
    }

    private fun localNets(): List<V4Net> {
        val out = ArrayList<V4Net>(4)
        val ifaces = NetworkInterface.getNetworkInterfaces() ?: return out
        for (nif in ifaces) {
            if (!nif.isUp || nif.isLoopback) continue
            for (ia in nif.interfaceAddresses) {
                val net = toNet(ia) ?: continue
                out.add(net)
            }
        }
        return out
    }

    private fun toNet(ia: InterfaceAddress): V4Net? {
        val addr = ia.address as? Inet4Address ?: return null
        if (!isPrivateV4(addr)) return null
        val prefix = ia.networkPrefixLength.toInt()
        if (prefix <= 0 || prefix > 32) return null
        return V4Net(ipv4ToInt(addr), prefix)
    }

    private fun ipv4ToInt(addr: Inet4Address): Int {
        val b = addr.address
        return ((b[0].toInt() and 0xff) shl 24) or
            ((b[1].toInt() and 0xff) shl 16) or
            ((b[2].toInt() and 0xff) shl 8) or
            (b[3].toInt() and 0xff)
    }

    private fun formatV4(ip: Int): String =
        "${(ip ushr 24) and 0xff}.${(ip ushr 16) and 0xff}.${(ip ushr 8) and 0xff}.${ip and 0xff}"

    private fun same24(a: Int, b: Int): Boolean = (a xor b) and 0xffffff00.toInt() == 0

    /** IPv4, auch wenn die Verbindung als IPv4-mapped IPv6 ankommt. */
    private fun asV4(addr: InetAddress): Inet4Address? {
        if (addr is Inet4Address) return addr
        val b = addr.address
        if (b.size == 16 &&
            b[0] == 0.toByte() && b[1] == 0.toByte() && b[2] == 0.toByte() && b[3] == 0.toByte() &&
            b[4] == 0.toByte() && b[5] == 0.toByte() && b[6] == 0.toByte() && b[7] == 0.toByte() &&
            b[8] == 0.toByte() && b[9] == 0.toByte() && b[10] == 0xff.toByte() && b[11] == 0xff.toByte()
        ) {
            return InetAddress.getByAddress(b.copyOfRange(12, 16)) as Inet4Address
        }
        return null
    }

    private fun parseLiteralV4(host: String): Inet4Address? {
        val parts = host.split('.')
        if (parts.size != 4) return null
        val bytes = ByteArray(4)
        for (i in 0..3) {
            val n = parts[i].toIntOrNull() ?: return null
            if (n !in 0..255) return null
            if (parts[i].length > 1 && parts[i].startsWith('0')) return null
            bytes[i] = n.toByte()
        }
        return InetAddress.getByAddress(bytes) as Inet4Address
    }

    private val IPV4_LITERAL = Regex("""\d{1,3}(?:\.\d{1,3}){3}""")

    val ports: IntRange get() = PREFERRED_PORT until (PREFERRED_PORT + PORT_SPAN)
}
