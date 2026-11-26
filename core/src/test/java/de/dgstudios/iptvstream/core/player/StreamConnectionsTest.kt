package de.dgstudios.iptvstream.core.player

import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamConnectionsTest {
    private val request = Request.Builder().url("http://example.invalid/live/1.ts").build()

    @Test fun closeAllCancelsOpenCallsOnce() {
        val c = StreamConnections(OkHttpClient())
        val a = c.newCall(request)
        val b = c.newCall(request)
        assertEquals(2, c.closeAll())
        assertTrue(a.isCanceled() && b.isCanceled())
        assertEquals(0, c.closeAll())
    }

    @Test fun keepsOnlyRecentCalls() {
        val c = StreamConnections(OkHttpClient())
        val calls = (1..StreamConnections.MAX_TRACKED + 5).map { c.newCall(request) }
        assertEquals(StreamConnections.MAX_TRACKED, c.closeAll())
        assertTrue(calls.takeLast(StreamConnections.MAX_TRACKED).all { it.isCanceled() })
    }

    /**
     * Wie beim Anbieter: Der Strom liefert erst Daten und hängt dann. Der Lese-Thread blockiert.
     * [StreamConnections.closeAll] muss die Verbindung sofort schließen, nicht erst nach dem Lese-Zeitlimit.
     */
    @Test fun closeAllClosesAHangingStreamImmediately() {
        ServerSocket(0).use { server ->
            val serverSawClose = CountDownLatch(1)
            val serverThread = Thread {
                server.accept().use { s ->
                    val input = s.getInputStream()
                    // Anfrage lesen bis Leerzeile.
                    val buf = StringBuilder()
                    while (!buf.endsWith("\r\n\r\n")) {
                        val b = input.read()
                        if (b < 0) break
                        buf.append(b.toChar())
                    }
                    val out = s.getOutputStream()
                    out.write("HTTP/1.1 200 OK\r\nContent-Type: video/mp2t\r\n\r\n".toByteArray())
                    out.write(ByteArray(188 * 10))
                    out.flush()
                    // Hängen: nichts mehr senden, bis der Client schließt.
                    if (input.read() < 0) serverSawClose.countDown()
                }
            }.apply { isDaemon = true; start() }

            val client = OkHttpClient.Builder().readTimeout(15, TimeUnit.SECONDS).build()
            val c = StreamConnections(client)
            val call = c.newCall(Request.Builder().url("http://127.0.0.1:${server.localPort}/live/1.ts").build())
            val readerDone = CountDownLatch(1)
            Thread {
                try {
                    call.execute().use { r -> r.body!!.source().let { src -> while (src.read(okio.Buffer(), 8192) >= 0) Unit } }
                } catch (_: Exception) {
                }
                readerDone.countDown()
            }.apply { isDaemon = true; start() }

            Thread.sleep(500) // Leser hängt jetzt im Socket-Lesen
            val started = System.nanoTime()
            assertEquals(1, c.closeAll())
            assertTrue("Lese-Thread endet sofort", readerDone.await(2, TimeUnit.SECONDS))
            assertTrue("Anbieter sieht die Verbindung geschlossen", serverSawClose.await(2, TimeUnit.SECONDS))
            assertTrue((System.nanoTime() - started) / 1_000_000 < 2_000)
            serverThread.join(1_000)
        }
    }
}
