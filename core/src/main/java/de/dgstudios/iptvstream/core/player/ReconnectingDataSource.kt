package de.dgstudios.iptvstream.core.player

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import java.io.IOException

/**
 * Datenquelle für Live-Streams ohne Ende (MPEG-TS über HTTP). Schließt der Anbieter die Verbindung
 * sauber (Ende der Daten), öffnet sie sofort eine neue und liest weiter. Der Player merkt davon nichts:
 * Puffer, Bild und Ton laufen ohne Neustart weiter. Ein TS-Strom ist selbstsynchronisierend, der
 * Extraktor findet nach der neuen Verbindung den nächsten Paketanfang selbst.
 *
 * Kein Dauerlauf bei kaputtem Sender: liefert eine neue Verbindung sofort wieder nichts,
 * endet der Strom normal und die Wiederverbindung des Players greift.
 */
@UnstableApi
class ReconnectingDataSource(
    private val factory: DataSource.Factory,
    private val enabled: () -> Boolean,
    private val onReconnect: (bytes: Long, millis: Long) -> Unit,
    private val clock: () -> Long = System::currentTimeMillis,
    private val sleep: (Long) -> Unit = {
        try {
            Thread.sleep(it)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    },
) : DataSource {
    private val listeners = mutableListOf<TransferListener>()
    private var upstream: DataSource? = null
    private var spec: DataSpec? = null
    private var active = false
    private var openedAt = 0L
    private var bytesThisConnection = 0L
    private var emptyReopens = 0

    override fun addTransferListener(transferListener: TransferListener) {
        listeners += transferListener
        upstream?.addTransferListener(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long {
        spec = dataSpec
        active = enabled() && dataSpec.position == 0L && dataSpec.length == C.LENGTH_UNSET.toLong()
        emptyReopens = 0
        return openUpstream(dataSpec)
    }

    private fun openUpstream(dataSpec: DataSpec): Long {
        val source = factory.createDataSource()
        listeners.forEach(source::addTransferListener)
        upstream = source
        openedAt = clock()
        bytesThisConnection = 0L
        return try {
            source.open(dataSpec)
        } catch (e: IOException) {
            upstream = null
            try { source.close() } catch (_: IOException) {}
            throw e
        }
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        while (true) {
            val source = upstream ?: return C.RESULT_END_OF_INPUT
            val n = source.read(buffer, offset, length)
            if (n > 0) {
                bytesThisConnection += n
                return n
            }
            if (n == 0) return 0
            // Ende der Daten.
            if (!active) return C.RESULT_END_OF_INPUT
            if (bytesThisConnection == 0L) {
                emptyReopens++
                if (emptyReopens > MAX_EMPTY_REOPENS) return C.RESULT_END_OF_INPUT
            } else {
                emptyReopens = 0
            }
            val bytes = bytesThisConnection
            val millis = clock() - openedAt
            try { source.close() } catch (_: IOException) {}
            upstream = null
            if (Thread.currentThread().isInterrupted) return C.RESULT_END_OF_INPUT
            if (bytes == 0L) sleep(RETRY_DELAY_MS)
            onReconnect(bytes, millis)
            openUpstream(spec ?: return C.RESULT_END_OF_INPUT)
        }
    }

    override fun getUri(): Uri? = upstream?.uri ?: spec?.uri

    override fun getResponseHeaders(): Map<String, List<String>> = upstream?.responseHeaders ?: emptyMap()

    override fun close() {
        active = false
        val source = upstream
        upstream = null
        source?.close()
    }

    companion object {
        const val MAX_EMPTY_REOPENS = 2
        const val RETRY_DELAY_MS = 500L
    }
}
