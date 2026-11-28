package de.dgstudios.iptvstream.core.player

import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.RenderersFactory
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.test.utils.FakeClock
import androidx.media3.test.utils.FakeRenderer
import androidx.media3.test.utils.robolectric.TestPlayerRunHelper
import java.io.File
import java.io.IOException
import java.net.ServerSocket
import java.net.URLConnection
import okhttp3.OkHttpClient
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Der PTS-Filter darf Filme (MP4/MKV) und HLS nicht zerlegen. Wiedergabe über denselben
 * Fabrik-Weg wie in der App, mit Filter aus (kein Live-TS).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NonTsPassthroughPlaybackTest {
    @Test fun playsMp4WhenFilterDisabled() = play("clip2s.mp4")

    @Test fun playsMkvWhenFilterDisabled() = play("clip2s.mkv")

    @Test fun playsHlsWhenFilterDisabled() = play("hls/index.m3u8")

    private fun play(resourcePath: String) {
        val root = extractResources()
        FileServer(root).use { server ->
            val join = LiveTsJoin()
            val dataSource = DataSource.Factory {
                TsOverlapSkipDataSource.wrap(
                    OkHttpDataSource.Factory(OkHttpClient()).createDataSource(),
                    join,
                    liveTs = false,
                )
            }
            val renderers = RenderersFactory { _, _, _, _, _ ->
                arrayOf(FakeRenderer(C.TRACK_TYPE_VIDEO), FakeRenderer(C.TRACK_TYPE_AUDIO))
            }
            val player = ExoPlayer.Builder(RuntimeEnvironment.getApplication(), renderers)
                .setClock(FakeClock(true))
                .setStuckBufferingDetectionTimeoutMs(Int.MAX_VALUE)
                .setStuckPlayingDetectionTimeoutMs(Int.MAX_VALUE)
                .setMediaSourceFactory(DefaultMediaSourceFactory(dataSource, DefaultExtractorsFactory()))
                .build()
            player.setMediaItem(MediaItem.fromUri("${server.base}/$resourcePath"))
            player.prepare()
            player.play()
            TestPlayerRunHelper.run(player).untilState(Player.STATE_ENDED)
            assertNull("$resourcePath: ${player.playerError}", player.playerError)
            assertTrue("$resourcePath soll durchgespielt werden (Position ${player.currentPosition} ms)", player.currentPosition >= 1_000L)
            player.release()
        }
    }

    /** Ressourcen aus dem Klassenpfad in ein temp-Verzeichnis, damit der HTTP-Server Dateien findet. */
    private fun extractResources(): File {
        val dir = File(System.getProperty("java.io.tmpdir"), "iptv-nonts-${System.nanoTime()}").apply { mkdirs() }
        for (path in listOf("clip2s.mp4", "clip2s.mkv", "hls/index.m3u8", "hls/seg0.ts")) {
            val dest = File(dir, path)
            dest.parentFile.mkdirs()
            javaClass.getResourceAsStream("/$path")!!.use { input -> dest.outputStream().use { input.copyTo(it) } }
        }
        return dir
    }

    private class FileServer(val root: File) : AutoCloseable {
        private val socket = ServerSocket(0)
        val base get() = "http://127.0.0.1:${socket.localPort}"
        private val thread = Thread {
            try {
                while (true) {
                    val c = socket.accept()
                    Thread {
                        try {
                            val req = c.getInputStream().bufferedReader()
                            val line = req.readLine() ?: return@Thread
                            while (req.readLine()?.isNotEmpty() == true) {}
                            val path = line.split(" ").getOrNull(1)?.removePrefix("/") ?: ""
                            val file = File(root, path).canonicalFile
                            if (!file.path.startsWith(root.canonicalPath) || !file.isFile) {
                                c.getOutputStream().write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                            } else {
                                val mime = when {
                                    file.name.endsWith(".m3u8") -> "application/vnd.apple.mpegurl"
                                    file.name.endsWith(".ts") -> "video/mp2t"
                                    file.name.endsWith(".mp4") -> "video/mp4"
                                    file.name.endsWith(".mkv") -> "video/x-matroska"
                                    else -> URLConnection.guessContentTypeFromName(file.name) ?: "application/octet-stream"
                                }
                                val body = file.readBytes()
                                c.getOutputStream().write(
                                    "HTTP/1.1 200 OK\r\nContent-Type: $mime\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray(),
                                )
                                c.getOutputStream().write(body)
                            }
                            c.close()
                        } catch (_: Exception) {}
                    }.apply { isDaemon = true; start() }
                }
            } catch (_: IOException) {}
        }.apply { isDaemon = true; start() }

        override fun close() {
            socket.close()
        }
    }
}
