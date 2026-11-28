package de.dgstudios.iptvstream.core.player

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import de.dgstudios.iptvstream.core.player.LiveTsJoin.Companion.KEYFRAME_WAIT_PTS
import de.dgstudios.iptvstream.core.player.LiveTsJoin.Companion.MAX_OVERLAP_PTS
import de.dgstudios.iptvstream.core.player.LiveTsJoin.Companion.UNSET
import de.dgstudios.iptvstream.core.player.LiveTsJoin.Companion.ptsAhead

/**
 * Liest einen MPEG-TS-Strom und verwirft am Anfang einer neuen Verbindung schon gesehenen Vorlauf:
 * Je Spur (PID) werden PES-Pakete mit PTS ≤ der letzten schon geladenen PTS dieser Spur nicht
 * an den Player weitergegeben. PAT und PMT bleiben immer erhalten. Bilder und Ton der neuen
 * Verbindung beginnen erst ab dem Anschluss (Video: am nächsten Schlüsselbild knapp danach).
 * So zählt der Puffer nur neues Material, und schon Gesehenes wird nicht noch einmal gespielt.
 *
 * Passen die Zeitstempel nicht zur alten Verbindung, wird nichts verworfen; der Player soll dann
 * an die Live-Kante springen statt den Vorlauf noch einmal zu zeigen.
 */
@UnstableApi
class TsOverlapSkipDataSource(
    private val upstream: DataSource,
    private val join: LiveTsJoin,
) : DataSource {
    private val packet = ByteArray(TS_PACKET)
    private var packetFill = 0
    private var unsynced = true
    private val out = ByteArray(TS_PACKET * 64)
    private var outBuf = out
    private var outStart = 0
    private var outEnd = 0
    private var skipUntil = emptyMap<Int, Long>()
    private val droppingPid = HashSet<Int>()
    private var skipMode = SkipMode.PASS
    private val psiPids = HashSet<Int>().apply { addAll(ALWAYS_PSI) }
    private val videoPids = HashSet<Int>()
    private val held = ArrayList<ByteArray>(32)
    private var heldFromPts = UNSET
    /** Erste an den Player abgegebene Medien-PTS dieser Öffnung (Tests). */
    var firstDeliveredPts: Long = UNSET
        private set
    var skippedPackets: Int = 0
        private set

    override fun addTransferListener(transferListener: TransferListener) {
        upstream.addTransferListener(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long {
        packetFill = 0
        unsynced = true
        outBuf = out
        outStart = 0
        outEnd = 0
        held.clear()
        heldFromPts = UNSET
        firstDeliveredPts = UNSET
        skippedPackets = 0
        psiPids.clear()
        psiPids.addAll(ALWAYS_PSI)
        videoPids.clear()
        droppingPid.clear()
        skipUntil = join.takeSkipUntil()
        skipMode = if (skipUntil.isEmpty()) SkipMode.PASS else SkipMode.DECIDE
        return upstream.open(dataSpec)
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        while (outEnd - outStart == 0) {
            val n = fillPacket()
            if (n == C.RESULT_END_OF_INPUT) {
                flushHeld(keepFromKeyframe = false)
                if (outEnd - outStart == 0) return C.RESULT_END_OF_INPUT
                break
            }
            if (n == 0) return 0
            processPacket()
        }
        val n = minOf(length, outEnd - outStart)
        System.arraycopy(outBuf, outStart, buffer, offset, n)
        outStart += n
        if (outStart == outEnd) {
            outStart = 0
            outEnd = 0
        }
        return n
    }

    override fun getUri(): Uri? = upstream.uri

    override fun getResponseHeaders(): Map<String, List<String>> = upstream.responseHeaders

    override fun close() {
        packetFill = 0
        outStart = 0
        outEnd = 0
        held.clear()
        upstream.close()
    }

    private fun fillPacket(): Int {
        // Nach einem Sync-Verlust Bytes verwerfen, bis 0x47 an einer Paketgrenze sitzt.
        if (unsynced && packetFill == 0) {
            while (true) {
                val one = ByteArray(1)
                val r = upstream.read(one, 0, 1)
                if (r == C.RESULT_END_OF_INPUT) return C.RESULT_END_OF_INPUT
                if (r == 0) return 0
                if (one[0] == TS_SYNC) {
                    packet[0] = TS_SYNC
                    packetFill = 1
                    unsynced = false
                    break
                }
            }
        }
        while (packetFill < TS_PACKET) {
            val r = upstream.read(packet, packetFill, TS_PACKET - packetFill)
            if (r == C.RESULT_END_OF_INPUT) {
                packetFill = 0
                unsynced = true
                return C.RESULT_END_OF_INPUT
            }
            if (r == 0) return 0
            packetFill += r
        }
        if (packet[0] != TS_SYNC) {
            packetFill = 0
            unsynced = true
            return fillPacket()
        }
        packetFill = 0
        return TS_PACKET
    }

    private fun processPacket() {
        val pid = ((packet[1].toInt() and 0x1F) shl 8) or (packet[2].toInt() and 0xFF)
        if (pid == 0) readPat(packet)
        val pts = readPesPts(packet)
        val streamId = readStreamId(packet)
        if (streamId in 0xE0..0xEF) videoPids.add(((packet[1].toInt() and 0x1F) shl 8) or (packet[2].toInt() and 0xFF))
        val rai = hasRai(packet)
        if (pts != UNSET) join.onPts(pid, pts)

        when (skipMode) {
            SkipMode.PASS -> deliver(packet, pts)
            SkipMode.DECIDE -> decide(pid, pts)
            SkipMode.DROP -> dropOrHold(pid, pts, rai, streamId)
            SkipMode.KEYFRAME -> dropOrHold(pid, pts, rai, streamId)
        }
    }

    private fun decide(pid: Int, pts: Long) {
        if (pts == UNSET) {
            emit(packet)
            return
        }
        val target = skipUntil[pid]
        if (target == null) {
            // Spur ohne Vergleichswert: durchlassen, Entscheidung an einer bekannten Spur.
            emit(packet)
            return
        }
        val ahead = ptsAhead(pts, target)
        when {
            ahead > 0 -> {
                // Diese Spur beginnt hinter dem alten Ende.
                skipMode = SkipMode.KEYFRAME
                heldFromPts = pts
                dropOrHold(pid, pts, hasRai(packet), readStreamId(packet))
            }
            ahead == 0L || -ahead <= MAX_OVERLAP_PTS -> {
                skipMode = SkipMode.DROP
                if (isPsi(pid)) emit(packet) else { droppingPid.add(pid); skippedPackets++ }
            }
            else -> {
                // Sehr großer Rücksprung (Neustart / andere Zeitachse, nicht der 33-Bit-Umschlag): nichts verwerfen.
                skipMode = SkipMode.PASS
                join.markMismatch()
                deliver(packet, pts)
            }
        }
    }

    private fun dropOrHold(pid: Int, pts: Long, rai: Boolean, streamId: Int) {
        if (isPsi(pid)) {
            emit(packet)
            return
        }
        val target = skipUntil[pid]
        val pusi = (packet[1].toInt() and 0x40) != 0
        if (pts != UNSET && target != null && ptsAhead(pts, target) <= 0 && -ptsAhead(pts, target) <= MAX_OVERLAP_PTS) {
            droppingPid.add(pid)
            skippedPackets++
            return
        }
        if (pusi && pts != UNSET && (target == null || ptsAhead(pts, target) > 0)) {
            droppingPid.remove(pid)
        }
        if (!pusi && pid in droppingPid) {
            skippedPackets++
            return
        }
        if (pts != UNSET && skipMode == SkipMode.DROP && (target == null || ptsAhead(pts, target) > 0)) {
            skipMode = SkipMode.KEYFRAME
            if (heldFromPts == UNSET) heldFromPts = pts
        }
        if (skipMode == SkipMode.KEYFRAME) {
            val videoTarget = videoPids.firstOrNull()?.let { skipUntil[it] } ?: target
            val videoKey = rai && (pid in videoPids || streamId in 0xE0..0xEF) &&
                (pts == UNSET || videoTarget == null || ptsAhead(pts, videoTarget) >= 0)
            if (videoKey || (pts != UNSET && videoTarget != null && ptsAhead(pts, videoTarget) >= KEYFRAME_WAIT_PTS)) {
                held.clear()
                skipMode = SkipMode.PASS
                droppingPid.clear()
                join.markJoined()
                deliver(packet, pts)
                return
            }
            held += packet.copyOf()
            if (pts != UNSET && ptsAhead(pts, heldFromPts) >= KEYFRAME_WAIT_PTS) {
                flushHeld(keepFromKeyframe = false)
                skipMode = SkipMode.PASS
                droppingPid.clear()
                join.markJoined()
            }
            return
        }
        skippedPackets++
    }

    private fun flushHeld(keepFromKeyframe: Boolean) {
        if (held.isEmpty()) return
        var start = 0
        if (keepFromKeyframe) {
            start = held.indexOfFirst { hasRai(it) }.coerceAtLeast(0)
        }
        for (i in start until held.size) deliver(held[i], UNSET)
        held.clear()
    }

    private fun deliver(src: ByteArray, pts: Long) {
        val mediaPts = if (pts != UNSET) pts else readPesPts(src)
        if (mediaPts != UNSET && !isPsi(packetPid(src))) {
            if (firstDeliveredPts == UNSET) firstDeliveredPts = mediaPts
            join.onDelivered(packetPid(src), mediaPts)
        }
        emit(src)
    }

    private fun emit(src: ByteArray) {
        if (outEnd + TS_PACKET > outBuf.size) {
            val n = outEnd - outStart
            val next = ByteArray(maxOf(outBuf.size * 2, n + TS_PACKET * 8))
            System.arraycopy(outBuf, outStart, next, 0, n)
            outBuf = next
            outEnd = n
            outStart = 0
        }
        System.arraycopy(src, 0, outBuf, outEnd, TS_PACKET)
        outEnd += TS_PACKET
    }

    private fun isPsi(pid: Int): Boolean = pid in psiPids

    private fun readPat(pkt: ByteArray) {
        val payload = payloadStart(pkt) ?: return
        var i = payload
        if (hasPusi(pkt) && i < TS_PACKET) i += 1 + (pkt[i].toInt() and 0xFF)
        if (i + 8 >= TS_PACKET) return
        if (pkt[i].toInt() and 0xFF != 0x00) return
        val sectionLen = ((pkt[i + 1].toInt() and 0x0F) shl 8) or (pkt[i + 2].toInt() and 0xFF)
        var p = i + 8
        val end = minOf(TS_PACKET, i + 3 + sectionLen - 4)
        while (p + 4 <= end) {
            val program = ((pkt[p].toInt() and 0xFF) shl 8) or (pkt[p + 1].toInt() and 0xFF)
            val pmtPid = ((pkt[p + 2].toInt() and 0x1F) shl 8) or (pkt[p + 3].toInt() and 0xFF)
            if (program != 0) psiPids.add(pmtPid)
            p += 4
        }
    }

    companion object {
        const val TS_PACKET = 188
        const val TS_SYNC: Byte = 0x47
        private val ALWAYS_PSI = setOf(0, 1, 0x11)

        private enum class SkipMode { PASS, DECIDE, DROP, KEYFRAME }

        private fun hasPusi(pkt: ByteArray): Boolean = (pkt[1].toInt() and 0x40) != 0

        private fun packetPid(pkt: ByteArray): Int =
            ((pkt[1].toInt() and 0x1F) shl 8) or (pkt[2].toInt() and 0xFF)

        private fun payloadStart(pkt: ByteArray): Int? {
            val afc = (pkt[3].toInt() shr 4) and 0x03
            var off = 4
            if (afc == 2 || afc == 3) {
                val afl = pkt[4].toInt() and 0xFF
                off = 5 + afl
            }
            if (afc == 2) return null
            if (off >= TS_PACKET) return null
            return off
        }

        private fun hasRai(pkt: ByteArray): Boolean {
            val afc = (pkt[3].toInt() shr 4) and 0x03
            if (afc != 2 && afc != 3) return false
            val afl = pkt[4].toInt() and 0xFF
            if (afl < 1) return false
            return (pkt[5].toInt() and 0x40) != 0
        }

        /** PTS aus einem PES-Kopf am Paketanfang, sonst [UNSET]. */
        fun readStreamId(pkt: ByteArray): Int {
            if (!hasPusi(pkt)) return -1
            val off = payloadStart(pkt) ?: return -1
            if (off + 4 > TS_PACKET) return -1
            if (pkt[off] != 0.toByte() || pkt[off + 1] != 0.toByte() || pkt[off + 2] != 1.toByte()) return -1
            return pkt[off + 3].toInt() and 0xFF
        }

        fun readPesPts(pkt: ByteArray): Long {
            if (!hasPusi(pkt)) return UNSET
            val off = payloadStart(pkt) ?: return UNSET
            if (off + 14 > TS_PACKET) return UNSET
            if (pkt[off] != 0.toByte() || pkt[off + 1] != 0.toByte() || pkt[off + 2] != 1.toByte()) return UNSET
            val streamId = pkt[off + 3].toInt() and 0xFF
            if (streamId < 0xC0) return UNSET
            val flags = pkt[off + 7].toInt() and 0xFF
            if (flags and 0x80 == 0) return UNSET
            val p = off + 9
            fun u(i: Int) = pkt[p + i].toLong() and 0xFFL
            return (((u(0) shr 1) and 0x07L) shl 30) or
                (u(1) shl 22) or
                (((u(2) shr 1) and 0x7FL) shl 15) or
                (u(3) shl 7) or
                ((u(4) shr 1) and 0x7FL)
        }
    }
}
