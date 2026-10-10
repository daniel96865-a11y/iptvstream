package de.dgstudios.iptvstream.core.pairing

import de.dgstudios.iptvstream.core.data.db.ProfileEntity
import de.dgstudios.iptvstream.core.data.db.ProfileType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.InetAddress
import java.net.Socket

class LanPairingTest {
    @Test
    fun secretsAreRandomAndWellFormed() {
        val a = LanPairing.newSecrets()
        val b = LanPairing.newSecrets()
        assertEquals(LanPairing.TOKEN_HEX_LENGTH, a.token.length)
        assertTrue(a.token.all { it in '0'..'9' || it in 'a'..'f' })
        assertEquals(LanPairing.CODE_LENGTH, a.code.length)
        assertEquals(a.code, LanPairing.normalizeCode(a.code.lowercase()))
        assertNotEquals(a.token, b.token)
        assertNotEquals(a.code, b.code)
    }

    @Test
    fun codeIgnoresSpacesAndAmbiguousLetters() {
        assertEquals("K7H3P2", LanPairing.normalizeCode("k7h 3p2"))
        assertEquals("K7H3P2", LanPairing.normalizeCode("K7H-3P2"))
        assertEquals("AB", LanPairing.normalizeCode("A0B1"))
    }

    @Test
    fun onlyLiteralPrivateAddresses() {
        assertTrue(LanPairing.isPrivateHost("192.168.1.20"))
        assertTrue(LanPairing.isPrivateHost("10.1.2.3"))
        assertTrue(LanPairing.isPrivateHost("172.16.5.5"))
        assertFalse(LanPairing.isPrivateHost("172.32.5.5"))
        assertFalse(LanPairing.isPrivateHost("8.8.8.8"))
        assertFalse(LanPairing.isPrivateHost("example.com"))
        assertFalse(LanPairing.isPrivateHost("192.168.001.1"))
        assertFalse(LanPairing.isPrivateHost("127.0.0.1"))
        assertTrue(LanPairing.isPrivateHost("127.0.0.1", allowLoopback = true))
        val publicAddr = InetAddress.getByAddress(byteArrayOf(1, 2, 3, 4))
        assertFalse(LanPairing.isAllowedClient(publicAddr))
        assertTrue(LanPairing.isAllowedClient(InetAddress.getByName("127.0.0.1")))
    }

    @Test
    fun formRoundTripKeepsPassword() {
        val profile = sample(password = "a&=+% ä/\\")
        val back = LanPairing.profileFromFields(LanPairing.parseForm(LanPairing.formEncode(profile)))
        assertEquals(profile.name, back.name)
        assertEquals(profile.type, back.type)
        assertEquals(profile.url, back.url)
        assertEquals(profile.username, back.username)
        assertEquals(profile.password, back.password)
        assertEquals(profile.epgUrl, back.epgUrl)
        assertNull(LanPairing.validate(profile))
    }

    @Test
    fun pageStaysOnTheTvAndDoesNotEchoAPassword() {
        val offer = LanPairing.Offer("192.168.1.20", 28765, "a".repeat(32), "K7H3P2", 0L)
        val page = LanPairing.htmlPage(offer)
        assertTrue(page.contains("An den Fernseher senden"))
        assertTrue(page.contains("/pair?token=${offer.token}"))
        assertTrue(page.contains("iptvstream://pair?host=192.168.1.20&port=28765&token=${offer.token}"))
        assertTrue(page.contains("K7H 3P2"))
        assertFalse(page.contains("https://"))
        assertFalse(page.contains("http://"))
        assertFalse(page.contains("geheim"))
        val (ok, err) = LanPairing.parseAck("""{"ok":false,"error":"Benutzer \"x\""}""")
        assertFalse(ok)
        assertEquals("Benutzer \"x\"", err)
    }

    @Test
    fun serverAcceptsOneProfileAndRejectsTheNext() {
        var seen: ProfileEntity? = null
        val server = LanPairingServer(
            submit = { p ->
                seen = p
                null
            },
            bindHost = "127.0.0.1",
            preferredPort = 0,
        )
        try {
            val offer = server.start() ?: error("Server startet nicht")
            val profile = sample(password = "päss&wort")
            assertNull(LanPairingClient.send(offer.host, offer.port, offer.token, profile, allowLoopback = true))
            assertEquals(profile.password, seen?.password)
            assertEquals(profile.epgUrl, seen?.epgUrl)
            assertTrue(server.phase.value is LanPairingServer.Phase.Saved)
            val again = LanPairingClient.send(offer.host, offer.port, offer.token, profile, allowLoopback = true)
            assertTrue(again != null)
        } finally {
            server.stop()
            server.join()
        }
    }

    @Test
    fun wrongTokenDoesNotCallSubmitAndPageNeedsTheToken() {
        var calls = 0
        val server = LanPairingServer(submit = { calls++; null }, bindHost = "127.0.0.1", preferredPort = 0)
        try {
            val offer = server.start() ?: error("Server startet nicht")
            val status = httpStatus(offer.host, offer.port, "GET / HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n")
            assertEquals(404, status)
            val rejected = LanPairingClient.send(offer.host, offer.port, "falsch", sample(), allowLoopback = true)
            assertTrue(rejected != null)
            assertEquals(0, calls)
            val page = httpBody(offer.host, offer.port, "GET /?token=${offer.token} HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n")
            assertTrue(page.contains("IPTVstream"))
            assertTrue(LanPairingClient.probe(offer.host, offer.port, offer.code, allowLoopback = true))
            assertFalse(LanPairingClient.probe(offer.host, offer.port, "ZZZZZZ", allowLoopback = true))
        } finally {
            server.stop()
            server.join()
        }
    }

    @Test
    fun codePathSavesAndStops() {
        val server = LanPairingServer(submit = { null }, bindHost = "127.0.0.1", preferredPort = 0)
        try {
            val offer = server.start() ?: error("Server startet nicht")
            val found = LanPairingClient.findOnHost(
                offer.host,
                offer.code,
                allowLoopback = true,
                ports = offer.port..offer.port,
            )
            assertEquals(offer.port, found?.port)
            assertNull(LanPairingClient.send(offer.host, offer.port, offer.code, sample(), allowLoopback = true))
            assertTrue(server.phase.value is LanPairingServer.Phase.Saved)
        } finally {
            server.stop()
            server.join()
        }
    }

    @Test
    fun badLoginDoesNotUseUpTheCode() {
        var tries = 0
        val server = LanPairingServer(
            submit = {
                tries++
                if (tries == 1) "Anmeldung abgelehnt." else null
            },
            bindHost = "127.0.0.1",
            preferredPort = 0,
        )
        try {
            val offer = server.start() ?: error("Server startet nicht")
            val error = LanPairingClient.send(offer.host, offer.port, offer.token, sample(), allowLoopback = true)
            assertEquals("Anmeldung abgelehnt.", error)
            assertTrue(server.phase.value is LanPairingServer.Phase.Waiting)
            assertNull(LanPairingClient.send(offer.host, offer.port, offer.code, sample(password = "anderes"), allowLoopback = true))
        } finally {
            server.stop()
            server.join()
        }
    }

    @Test
    fun oversizedBodyIsRejected() {
        val server = LanPairingServer(submit = { error("darf nicht speichern") }, bindHost = "127.0.0.1", preferredPort = 0)
        try {
            val offer = server.start() ?: error("Server startet nicht")
            val status = httpStatus(
                offer.host,
                offer.port,
                "POST /pair?token=${offer.token} HTTP/1.1\r\nHost: localhost\r\nContent-Length: 200000\r\nConnection: close\r\n\r\n",
            )
            assertEquals(413, status)
            assertTrue(server.phase.value is LanPairingServer.Phase.Waiting)
        } finally {
            server.stop()
            server.join()
        }
    }

    @Test
    fun clientRefusesPublicHosts() {
        val error = LanPairingClient.send("8.8.8.8", 80, "x", sample())
        assertEquals("Diese Adresse liegt nicht im Heimnetz.", error)
        assertFalse(LanPairingClient.probe("1.1.1.1", 80, "ABCDEF"))
    }

    private fun sample(password: String = "geheim") = ProfileEntity(
        name = "Wohnzimmer",
        type = ProfileType.XTREAM,
        url = "http://192.0.2.10:8080",
        username = "user",
        password = password,
        epgUrl = "http://192.0.2.10/xmltv.php",
    )

    private fun httpStatus(host: String, port: Int, request: String): Int {
        Socket(host, port).use { socket ->
            socket.soTimeout = 3000
            val out = PrintWriter(socket.getOutputStream(), true)
            out.print(request)
            out.flush()
            val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.ISO_8859_1))
            val status = reader.readLine() ?: return -1
            return status.split(' ').getOrNull(1)?.toIntOrNull() ?: -1
        }
    }

    private fun httpBody(host: String, port: Int, request: String): String {
        Socket(host, port).use { socket ->
            socket.soTimeout = 3000
            val out = PrintWriter(socket.getOutputStream(), true)
            out.print(request)
            out.flush()
            return socket.getInputStream().bufferedReader(Charsets.UTF_8).readText()
        }
    }
}
