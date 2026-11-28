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
 * sauber (Ende der Daten) oder wird sie wegen Stillstands abgebrochen, öffnet sie eine neue und liest weiter. Der Player merkt davon nichts:
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
    private val onReconnect: (bytes: Long, millis: Long, kicked: Boolean) -> Unit,
    /** Live-TS: ein absichtlich abgebrochener Stillstand darf als Ende gelten (unabhängig von [enabled]). */
    private val stallEnabled: () -> Boolean = { false },
    /** Wurde die laufende Verbindung wegen Daten-Stillstand abgebrochen? True genau einmal pro Abbruch. */
    private val takeStallKick: () -> Boolean = { false },
    /** Vor einer neuen Verbindung im selben Strom (nahtlose Neuöffnung). */
    private val onReopen: () -> Unit = {},
    /**
     * Nach einem Ende oder Stillstand-Abbruch: höchstens so lange warten, bevor die nächste
     * Verbindung im selben Strom geöffnet wird (gegen 406). 0 = nicht warten, außer bei leerer Antwort.
     */
    private val reopenMaxDelayMs: () -> Long = { 0L },
    /** Wartezeit vor der Neuöffnung verkürzen, sobald der Puffer knapp wird. */
    private val shouldWaitBeforeReopen: () -> Boolean = { false },
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
    private var stallActive = false
    private var openedAt = 0L
    private var bytesThisConnection = 0L
    private var emptyReopens = 0

    override fun addTransferListener(transferListener: TransferListener) {
        listeners += transferListener
        upstream?.addTransferListener(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long {
        spec = dataSpec
        val endless = dataSpec.position == 0L && dataSpec.length == C.LENGTH_UNSET.toLong()
        active = enabled() && endless
        stallActive = stallEnabled() && endless
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
            val n = try {
                source.read(buffer, offset, length)
            } catch (e: TsJoinMismatchException) {
                // Neue Verbindung hat eine andere Zeitachse: nicht in denselben Extraktor mischen,
                // sondern den Strom beenden. Der Player hängt ein neues Stück an.
                try { source.close() } catch (_: IOException) {}
                upstream = null
                active = false
                return C.RESULT_END_OF_INPUT
            } catch (e: IOException) {
                // Absichtlich abgebrochen, weil der Anbieter nichts mehr schickte.
                // Wenn nahtlos aktiv ist, im selben Extraktor neu öffnen (Decoder bleibt, kein Renderer-Reset).
                // Sonst als Ende melden: der Player kann ein neues Stück anschließen.
                if (!stallActive || !takeStallKick()) throw e
                try { source.close() } catch (_: IOException) {}
                upstream = null
                if (reopen(kicked = true)) continue
                return C.RESULT_END_OF_INPUT
            }
            if (n > 0) {
                bytesThisConnection += n
                return n
            }
            if (n == 0) return 0
            // Ende der Daten.
            try { source.close() } catch (_: IOException) {}
            upstream = null
            if (reopen(kicked = false)) continue
            return C.RESULT_END_OF_INPUT
        }
    }

    /**
     * Öffnet eine neue Verbindung im selben Strom. False: der Strom endet (Fallback: Anschluss
     * als nächstes Stück oder normale Wiederverbindung).
     */
    private fun reopen(kicked: Boolean): Boolean {
        if (!active || !enabled()) return false
        if (bytesThisConnection == 0L) {
            emptyReopens++
            if (emptyReopens > MAX_EMPTY_REOPENS) return false
        } else {
            emptyReopens = 0
        }
        if (Thread.currentThread().isInterrupted) return false
        val bytes = bytesThisConnection
        val millis = clock() - openedAt
        waitBeforeReopen(empty = bytes == 0L)
        if (Thread.currentThread().isInterrupted) return false
        onReconnect(bytes, millis, kicked)
        onReopen()
        val spec = this.spec ?: return false
        openUpstream(spec)
        return true
    }

    private fun waitBeforeReopen(empty: Boolean) {
        val start = clock()
        val minWait = if (empty) RETRY_DELAY_MS else 0L
        val maxWait = reopenMaxDelayMs().coerceAtLeast(0L)
        val until = start + maxOf(minWait, maxWait)
        while (clock() < until) {
            if (clock() - start >= minWait && !shouldWaitBeforeReopen()) break
            sleep(100)
            if (Thread.currentThread().isInterrupted) return
        }
    }

    override fun getUri(): Uri? = upstream?.uri ?: spec?.uri

    override fun getResponseHeaders(): Map<String, List<String>> = upstream?.responseHeaders ?: emptyMap()

    override fun close() {
        active = false
        stallActive = false
        val source = upstream
        upstream = null
        source?.close()
    }

    companion object {
        const val MAX_EMPTY_REOPENS = 2
        const val RETRY_DELAY_MS = 500L
    }
}
