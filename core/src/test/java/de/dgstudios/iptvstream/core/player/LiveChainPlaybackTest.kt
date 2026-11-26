package de.dgstudios.iptvstream.core.player

import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.RenderersFactory
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.LoadEventInfo
import androidx.media3.exoplayer.source.MediaLoadData
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.test.utils.FakeClock
import androidx.media3.test.utils.FakeRenderer
import androidx.media3.test.utils.robolectric.TestPlayerRunHelper
import java.io.IOException
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
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
 * Echter Media3-Player (Fake-Renderer) gegen einen lokalen TS-Server: Eine endende bzw. hängende Live-Verbindung
 * wird als nächstes Stück angeschlossen, und der Player spielt ohne Fehler weiter.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LiveChainPlaybackTest {
    private val ts = javaClass.getResource("/live10s.ts")!!.readBytes()

    /** Verbindung 1: halbe Datei, dann [hang] (offen, nichts mehr) bzw. schließen. Danach: ganze Datei. */
    private inner class Server(val hang: Boolean) : AutoCloseable {
        val socket = ServerSocket(0)
        val firstSent = CountDownLatch(1)
        val connections = AtomicInteger()
        private val open = mutableListOf<Socket>()
        private val thread = Thread {
            try {
                while (true) {
                    val c = socket.accept()
                    synchronized(open) { open += c }
                    val n = connections.incrementAndGet()
                    Thread {
                        try {
                            val r = c.getInputStream().bufferedReader()
                            while (r.readLine()?.isNotEmpty() == true) {}
                            val out = c.getOutputStream()
                            out.write("HTTP/1.1 200 OK\r\nContent-Type: video/mp2t\r\nConnection: close\r\n\r\n".toByteArray())
                            if (n == 1) {
                                val half = ts.size / 2 / 188 * 188
                                out.write(ts, 0, half)
                                out.flush()
                                firstSent.countDown()
                                if (hang) Thread.sleep(60_000)
                            } else {
                                out.write(ts)
                                out.flush()
                            }
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

    private fun play(hang: Boolean) {
        Server(hang).use { server ->
            val connections = StreamConnections(OkHttpClient())
            val kick = AtomicBoolean(false)
            val dataSource = DataSource.Factory {
                ReconnectingDataSource(
                    OkHttpDataSource.Factory(connections),
                    enabled = { false },
                    onReconnect = { _, _ -> },
                    stallEnabled = { true },
                    takeStallKick = { kick.getAndSet(false) },
                )
            }
            val renderers = RenderersFactory { _, _, _, _, _ ->
                arrayOf(FakeRenderer(C.TRACK_TYPE_VIDEO), FakeRenderer(C.TRACK_TYPE_AUDIO))
            }
            val player = ExoPlayer.Builder(RuntimeEnvironment.getApplication(), renderers)
                .setClock(FakeClock(true))
                // Die Fake-Uhr läuft schneller als das echte Netz: Media3s Hänger-Erkennung hier aus.
                .setStuckBufferingDetectionTimeoutMs(Int.MAX_VALUE)
                .setStuckPlayingDetectionTimeoutMs(Int.MAX_VALUE)
                .setMediaSourceFactory(DefaultMediaSourceFactory(dataSource, DefaultExtractorsFactory()))
                .build()
            val item = MediaItem.fromUri(server.url)
            val transitions = AtomicInteger()
            var rebuffers = 0
            player.addListener(object : Player.Listener {
                override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                    if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) {
                        transitions.incrementAndGet()
                        player.removeMediaItems(0, player.currentMediaItemIndex)
                    }
                }
            })
            player.addAnalyticsListener(object : AnalyticsListener {
                override fun onLoadCompleted(eventTime: AnalyticsListener.EventTime, loadEventInfo: LoadEventInfo, mediaLoadData: MediaLoadData) {
                    // Wie chainNextLive: nur ans letzte Stück anschließen, hier höchstens einmal.
                    if (mediaLoadData.dataType == C.DATA_TYPE_MEDIA && eventTime.windowIndex == player.mediaItemCount - 1 &&
                        server.connections.get() < 2
                    ) {
                        player.addMediaItem(item)
                    }
                }
            })
            if (hang) {
                Thread {
                    server.firstSent.await(10, TimeUnit.SECONDS)
                    Thread.sleep(500)
                    kick.set(true)
                    connections.closeAll()
                }.apply { isDaemon = true; start() }
            }
            player.setMediaItem(item)
            player.prepare()
            player.play()
            TestPlayerRunHelper.run(player).untilState(Player.STATE_ENDED)
            assertNull(player.playerError)
            assertEquals(1, transitions.get())
            assertEquals(2, server.connections.get())
            assertTrue(player.currentPosition > 8_000)
            player.release()
        }
    }

    @Test fun providerEndIsChainedWithoutError() = play(hang = false)

    @Test fun stalledConnectionIsCutAndChainedWithoutError() = play(hang = true)
}
