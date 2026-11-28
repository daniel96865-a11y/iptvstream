package de.dgstudios.iptvstream.core.player

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.datasource.ByteArrayDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.RenderersFactory
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.test.utils.FakeClock
import androidx.media3.test.utils.FakeRenderer
import androidx.media3.test.utils.robolectric.TestPlayerRunHelper
import java.io.IOException
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicInteger
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Nachstellung des sw5-Kaltstarts: Die Drossel darf den Player nicht vom Lade-Thread aus
 * anfassen, und ein Fehler in Filter oder Drossel darf die Wiedergabe nicht verhindern.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LiveDataSourceChainTest {
    private val ts = javaClass.getResource("/live10s.ts")!!.readBytes()

    @Test fun shouldPaceWrongThreadDoesNotFailTheFirstRead() {
        val src = paced(ByteArrayDataSource(ts), shouldPace = {
            throw IllegalStateException("Player is accessed on the wrong thread.")
        })
        src.open(DataSpec(Uri.EMPTY))
        val n = src.read(ByteArray(188 * 8), 0, 188 * 8)
        assertTrue("erster Read muss Bytes liefern, nicht mit IllegalStateException sterben (war $n)", n > 0)
        src.close()
    }

    @Test fun openCloseOpenOnTheSamePacerAndSkipperInstance() {
        val join = LiveTsJoin()
        val src = LiveReadPacer.wrap(
            TsOverlapSkipDataSource(ByteArrayDataSource(ts), join),
            shouldPace = { false },
            bytesPerSec = { LiveReadPacer.DEFAULT_BYTES_PER_SEC },
        )
        repeat(3) { i ->
            src.open(DataSpec(Uri.EMPTY))
            val out = drain(src)
            src.close()
            assertEquals("Öffnung ${i + 1} muss den ganzen Strom liefern", ts.size, out.size)
        }
    }

    @Test fun filterErrorPassesThePacketThrough() {
        // Pakete mit Sync, aber unvollständigem Rest: der Filter darf nicht werfen.
        val junk = ByteArray(188 * 4) { 0x11 }
        for (i in junk.indices step 188) junk[i] = 0x47
        val src = TsOverlapSkipDataSource(ByteArrayDataSource(junk), LiveTsJoin())
        src.open(DataSpec(Uri.EMPTY))
        val out = drain(src)
        src.close()
        assertEquals(junk.size, out.size)
    }

    @Test fun coldStartWithPacerPlayerAccessPlaysToTheEnd() {
        val errors = AtomicInteger()
        Server(ts).use { server ->
            val player = playerFor(server) { exo ->
                LiveReadPacer.wrap(
                    TsOverlapSkipDataSource.wrap(
                        OkHttpDataSource.Factory(OkHttpClient()).createDataSource(),
                        LiveTsJoin(),
                        liveTs = true,
                    ),
                    shouldPace = {
                        // Genau der sw5-Zugriff, der auf dem Lade-Thread IllegalStateException wirft.
                        exo.totalBufferedDuration
                        false
                    },
                    bytesPerSec = { LiveReadPacer.DEFAULT_BYTES_PER_SEC },
                )
            }
            player.addListener(object : Player.Listener {
                override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                    errors.incrementAndGet()
                }
            })
            try {
                player.setMediaItem(MediaItem.fromUri(server.url))
                player.prepare()
                player.play()
                TestPlayerRunHelper.run(player).untilState(Player.STATE_ENDED)
                assertNull("Kaltstart darf nicht mit IO_UNSPECIFIED / IllegalStateException sterben", player.playerError)
                assertEquals(0, errors.get())
                assertTrue("Position ${player.currentPosition} ms", player.currentPosition in 7_000..12_000)
            } finally {
                player.release()
            }
        }
    }

    @Test fun channelSwitchPlaysTheSecondItem() {
        Server(ts).use { first ->
            Server(ts).use { second ->
                val player = playerFor(first) {
                    LiveReadPacer.wrap(
                        TsOverlapSkipDataSource.wrap(
                            OkHttpDataSource.Factory(OkHttpClient()).createDataSource(),
                            LiveTsJoin(),
                            liveTs = true,
                        ),
                        shouldPace = { false },
                        bytesPerSec = { LiveReadPacer.DEFAULT_BYTES_PER_SEC },
                    )
                }
                try {
                        player.setMediaItem(MediaItem.fromUri(first.url))
                    player.prepare()
                    player.play()
                    TestPlayerRunHelper.run(player).untilState(Player.STATE_ENDED)
                    assertNull(player.playerError)
                    player.setMediaItem(MediaItem.fromUri(second.url))
                    player.prepare()
                    player.play()
                    TestPlayerRunHelper.run(player).untilState(Player.STATE_ENDED)
                    assertNull("Senderwechsel darf nicht mit einem DataSource-Fehler sterben", player.playerError)
                    assertTrue("zweites Stück Position ${player.currentPosition} ms", player.currentPosition in 7_000..12_000)
                } finally {
                    player.release()
                }
            }
        }
    }

    private fun paced(upstream: DataSource, shouldPace: () -> Boolean): DataSource =
        LiveReadPacer.wrap(upstream, shouldPace, { LiveReadPacer.DEFAULT_BYTES_PER_SEC })

    private fun drain(src: DataSource): ByteArray {
        val out = ArrayList<Byte>()
        val buf = ByteArray(4096)
        while (true) {
            val n = src.read(buf, 0, buf.size)
            if (n == C.RESULT_END_OF_INPUT) break
            if (n > 0) repeat(n) { i -> out.add(buf[i]) }
        }
        return out.toByteArray()
    }

    private fun playerFor(server: Server, factory: (ExoPlayer) -> DataSource): ExoPlayer {
        // Player-Referenz für shouldPace: die Fabrik läuft erst nach dem Bau, beim ersten open.
        val holder = arrayOfNulls<ExoPlayer>(1)
        val renderers = RenderersFactory { _, _, _, _, _ ->
            arrayOf(FakeRenderer(C.TRACK_TYPE_VIDEO), FakeRenderer(C.TRACK_TYPE_AUDIO))
        }
        val player = ExoPlayer.Builder(RuntimeEnvironment.getApplication(), renderers)
            .setClock(FakeClock(true))
            .setStuckBufferingDetectionTimeoutMs(Int.MAX_VALUE)
            .setStuckPlayingDetectionTimeoutMs(Int.MAX_VALUE)
            .setMediaSourceFactory(
                DefaultMediaSourceFactory(
                    DataSource.Factory { factory(holder[0]!!) },
                    DefaultExtractorsFactory(),
                ),
            )
            .build()
        holder[0] = player
        return player
    }

    private class Server(private val body: ByteArray) : AutoCloseable {
        val socket = ServerSocket(0)
        private val open = mutableListOf<Socket>()
        private val thread = Thread {
            try {
                while (true) {
                    val c = socket.accept()
                    synchronized(open) { open += c }
                    Thread {
                        try {
                            val r = c.getInputStream().bufferedReader()
                            while (r.readLine()?.isNotEmpty() == true) {}
                            val out = c.getOutputStream()
                            out.write("HTTP/1.1 200 OK\r\nContent-Type: video/mp2t\r\nConnection: close\r\n\r\n".toByteArray())
                            out.write(body)
                            out.flush()
                            c.close()
                        } catch (_: Exception) {}
                    }.apply { isDaemon = true; start() }
                }
            } catch (_: IOException) {}
        }.apply { isDaemon = true; start() }

        val url get() = "http://127.0.0.1:${socket.localPort}/live/1.ts"

        override fun close() {
            socket.close()
            synchronized(open) { open.forEach { runCatching { it.close() } } }
        }
    }
}
