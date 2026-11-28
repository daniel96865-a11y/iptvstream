package de.dgstudios.iptvstream.core.player

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener

/**
 * Hält bei vollem Live-Puffer die TCP-Verbindung in Bewegung, ohne den Puffer ungebremst
 * wachsen zu lassen: Es wird etwa in Echtzeit weitergelesen (kein „Laden pausiert“).
 */
@UnstableApi
class LiveReadPacer(
    private val upstream: DataSource,
    private val shouldPace: () -> Boolean,
    private val bytesPerSec: () -> Int,
    private val clock: () -> Long = System::currentTimeMillis,
    private val sleep: (Long) -> Unit = {
        try {
            Thread.sleep(it)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    },
) : DataSource {
    private var paceStartedAt = 0L
    private var pacedBytes = 0L

    override fun addTransferListener(transferListener: TransferListener) {
        upstream.addTransferListener(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long {
        resetPace()
        return upstream.open(dataSpec)
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        val n = upstream.read(buffer, offset, length)
        if (n > 0) onRead(n)
        return n
    }

    override fun getUri(): Uri? = upstream.uri

    override fun getResponseHeaders(): Map<String, List<String>> = upstream.responseHeaders

    override fun close() {
        resetPace()
        upstream.close()
    }

    private fun onRead(n: Int) {
        if (!shouldPace()) {
            resetPace()
            return
        }
        val now = clock()
        if (paceStartedAt == 0L) {
            paceStartedAt = now
            pacedBytes = 0
        }
        pacedBytes += n
        val delay = delayMs(pacedBytes, now - paceStartedAt, bytesPerSec().coerceAtLeast(MIN_BYTES_PER_SEC))
        if (delay > 0) sleep(delay)
    }

    private fun resetPace() {
        paceStartedAt = 0L
        pacedBytes = 0L
    }

    companion object {
        const val PACE_HEADROOM_MS = 2_000L
        const val MIN_BYTES_PER_SEC = 32 * 1024
        const val DEFAULT_BYTES_PER_SEC = 1_000_000 // 8 Mbit/s, typischer HD-Sender
        private const val MAX_SLEEP_MS = 200L

        /**
         * Wie lange warten, damit [bytes] nicht schneller als [bytesPerSec] angekommen sind.
         * Öffentlich, damit die Rechnung ohne Android testbar ist.
         */
        fun delayMs(bytes: Long, elapsedMs: Long, bytesPerSec: Int): Long {
            if (bytesPerSec <= 0 || bytes <= 0) return 0L
            val allowed = bytesPerSec.toLong() * elapsedMs.coerceAtLeast(0L) / 1000L
            if (bytes <= allowed) return 0L
            return ((bytes - allowed) * 1000L / bytesPerSec).coerceIn(1L, MAX_SLEEP_MS)
        }

        fun shouldPace(bufferedMs: Long, liveMaxMs: Long): Boolean =
            liveMaxMs > 0L && bufferedMs >= (liveMaxMs - PACE_HEADROOM_MS)

        fun wrap(
            upstream: DataSource,
            shouldPace: () -> Boolean,
            bytesPerSec: () -> Int,
        ): DataSource = LiveReadPacer(upstream, shouldPace, bytesPerSec)
    }
}
