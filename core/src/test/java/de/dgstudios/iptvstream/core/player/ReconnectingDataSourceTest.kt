package de.dgstudios.iptvstream.core.player

import android.net.Uri
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.TransferListener
import java.io.IOException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ReconnectingDataSourceTest {
    /** Eine Verbindung: liefert [data], danach wirft sie (wie ein abgebrochener Socket). */
    private class Conn(val data: ByteArray, val openError: IOException? = null) : DataSource {
        var pos = 0
        var closed = false
        override fun addTransferListener(transferListener: TransferListener) {}
        override fun open(dataSpec: DataSpec): Long {
            openError?.let { throw it }
            return -1
        }
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (pos >= data.size) throw IOException("Canceled")
            buffer[offset] = data[pos++]
            return 1
        }
        override fun getUri(): Uri? = null
        override fun close() { closed = true }
    }

    private val spec = DataSpec(Uri.parse("http://example.invalid/live/1.ts"))

    private fun source(conns: List<Conn>, kick: () -> Boolean, log: MutableList<String>, sleeps: MutableList<Long>): ReconnectingDataSource {
        val it = conns.iterator()
        return ReconnectingDataSource(
            factory = { it.next() },
            enabled = { false },
            onReconnect = { _, _ -> },
            stallEnabled = { true },
            takeStallKick = kick,
            onStallReconnect = { n, ok -> log += "$n:$ok" },
            sleep = { sleeps += it },
        )
    }

    private fun readN(s: DataSource, n: Int): ByteArray {
        val out = ByteArray(n)
        for (i in 0 until n) assertEquals(1, s.read(out, i, 1))
        return out
    }

    @Test fun kickedConnectionIsReplacedWithoutError() {
        val a = Conn(byteArrayOf(1, 2))
        val b = Conn(byteArrayOf(3, 4))
        val log = mutableListOf<String>()
        var kicked = true
        val s = source(listOf(a, b), { kicked.also { kicked = false } }, log, mutableListOf())
        s.open(spec)
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), readN(s, 4))
        assertEquals(listOf("1:true"), log)
        assertEquals(true, a.closed)
    }

    @Test fun unkickedErrorGoesToPlayer() {
        val s = source(listOf(Conn(byteArrayOf(1)), Conn(byteArrayOf(2))), { false }, mutableListOf(), mutableListOf())
        s.open(spec)
        readN(s, 1)
        assertThrows(IOException::class.java) { s.read(ByteArray(1), 0, 1) }
    }

    @Test fun connectionLimitWaitsAndRetries() {
        val busy = HttpDataSource.InvalidResponseCodeException(406, null, null, emptyMap(), spec, ByteArray(0))
        val log = mutableListOf<String>()
        val sleeps = mutableListOf<Long>()
        var kicked = true
        val s = source(
            listOf(Conn(byteArrayOf(1)), Conn(ByteArray(0), busy), Conn(byteArrayOf(9))),
            { kicked.also { kicked = false } },
            log,
            sleeps,
        )
        s.open(spec)
        assertArrayEquals(byteArrayOf(1, 9), readN(s, 2))
        assertEquals(listOf(2_000L), sleeps)
        assertEquals(listOf("2:true"), log)
    }

    @Test fun givesUpAfterThreeOpens() {
        val busy = HttpDataSource.InvalidResponseCodeException(406, null, null, emptyMap(), spec, ByteArray(0))
        val log = mutableListOf<String>()
        var kicked = true
        val conns = listOf(Conn(byteArrayOf(1))) + List(3) { Conn(ByteArray(0), busy) }
        val s = source(conns, { kicked.also { kicked = false } }, log, mutableListOf())
        s.open(spec)
        readN(s, 1)
        assertThrows(HttpDataSource.InvalidResponseCodeException::class.java) { s.read(ByteArray(1), 0, 1) }
        assertEquals(listOf("3:false"), log)
    }

    /** Echte OkHttp-Verbindung, die nach 3 Bytes hängt: Abbruch über [StreamConnections] ersetzt sie ohne Fehler. */
    @Test fun realHangingSocketIsReplaced() {
        val server = java.net.ServerSocket(0)
        val sockets = mutableListOf<java.net.Socket>()
        val serverThread = Thread {
            try {
                var n = 0
                while (true) {
                    val c = server.accept()
                    synchronized(sockets) { sockets += c }
                    n++
                    val r = c.getInputStream().bufferedReader()
                    while (r.readLine()?.isNotEmpty() == true) {}
                    val out = c.getOutputStream()
                    out.write("HTTP/1.1 200 OK\r\nContent-Type: video/mp2t\r\n\r\n".toByteArray())
                    out.write(byteArrayOf((n * 10 + 1).toByte(), (n * 10 + 2).toByte(), (n * 10 + 3).toByte()))
                    out.flush() // danach Stille, Verbindung bleibt offen
                }
            } catch (_: IOException) {}
        }.apply { isDaemon = true; start() }
        val connections = StreamConnections(okhttp3.OkHttpClient.Builder().readTimeout(30, java.util.concurrent.TimeUnit.SECONDS).build())
        val kick = java.util.concurrent.atomic.AtomicBoolean(false)
        val log = mutableListOf<String>()
        val s = ReconnectingDataSource(
            factory = { androidx.media3.datasource.okhttp.OkHttpDataSource.Factory(connections).createDataSource() },
            enabled = { false },
            onReconnect = { _, _ -> },
            stallEnabled = { true },
            takeStallKick = { kick.getAndSet(false) },
            onStallReconnect = { n, ok -> synchronized(log) { log += "$n:$ok" } },
        )
        s.open(DataSpec(Uri.parse("http://127.0.0.1:${server.localPort}/live/1.ts")))
        assertArrayEquals(byteArrayOf(11, 12, 13), readN(s, 3))
        val started = System.currentTimeMillis()
        Thread {
            Thread.sleep(300)
            kick.set(true)
            connections.closeAll()
        }.start()
        assertArrayEquals(byteArrayOf(21, 22, 23), readN(s, 3))
        assert(System.currentTimeMillis() - started < 5_000)
        assertEquals(listOf("1:true"), log)
        s.close()
        server.close()
        synchronized(sockets) { sockets.forEach { it.close() } }
        serverThread.join(1_000)
    }
}
