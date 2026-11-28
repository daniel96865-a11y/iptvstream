package de.dgstudios.iptvstream.core.player

import de.dgstudios.iptvstream.core.player.LiveTsJoin.Companion.PTS_HZ
import de.dgstudios.iptvstream.core.player.LiveTsJoin.Companion.PTS_MASK
import de.dgstudios.iptvstream.core.player.TsOverlapSkipDataSource.Companion.TS_PACKET
import de.dgstudios.iptvstream.core.player.TsOverlapSkipDataSource.Companion.TS_SYNC
import de.dgstudios.iptvstream.core.player.TsOverlapSkipDataSource.Companion.readPesPts

/**
 * Gleicht eine neue PTS-Basis an die laufende Zeitachse an: Jeder Zeitstempel (PTS, DTS, PCR)
 * wird um denselben Offset verschoben, damit Extraktor, Decoder und AudioSink im selben Strom
 * weiterlaufen können, ohne neu zu starten.
 */
object TsClockAlign {
    /** Kleiner Abstand hinter der letzten ausgegebenen PTS (etwa ein AC-3-Rahmen), kein Überlapp. */
    const val JOIN_GAP_PTS = 32L * PTS_HZ / 1000L

    fun offsetToContinue(lastPts: Long, newPts: Long): Long =
        (lastPts + JOIN_GAP_PTS - newPts) and PTS_MASK

    fun apply(pts: Long, offset: Long): Long = (pts + offset) and PTS_MASK

    /**
     * Schreibt PTS, DTS und PCR in einem TS-Paket um. Liefert die neue PTS, oder [LiveTsJoin.UNSET].
     * [start] zeigt auf den Paketanfang in [data].
     */
    fun rewritePacket(data: ByteArray, offset: Long, start: Int = 0): Long {
        if (offset == 0L) return readPesPtsAt(data, start)
        if (start + TS_PACKET > data.size || data[start] != TS_SYNC) return LiveTsJoin.UNSET
        rewritePcr(data, start, offset)
        return rewritePesTimestamps(data, start, offset)
    }

    /** Verschiebt alle Zeitstempel in einem kompletten TS-Puffer (Tests und künstliche Zeitachsen). */
    fun shiftAll(ts: ByteArray, offset: Long): ByteArray {
        val out = ts.copyOf()
        var i = 0
        while (i + TS_PACKET <= out.size) {
            if (out[i] == TS_SYNC) rewritePacket(out, offset, i)
            i += TS_PACKET
        }
        return out
    }

    fun readTimestamp5(data: ByteArray, p: Int): Long {
        fun u(i: Int) = data[p + i].toLong() and 0xFFL
        return (((u(0) shr 1) and 0x07L) shl 30) or
            (u(1) shl 22) or
            (((u(2) shr 1) and 0x7FL) shl 15) or
            (u(3) shl 7) or
            ((u(4) shr 1) and 0x7FL)
    }

    fun writeTimestamp5(data: ByteArray, p: Int, pts: Long) {
        val prefix = data[p].toInt() and 0xF0
        val v = pts and PTS_MASK
        data[p] = (prefix or ((((v shr 30) and 0x07L).toInt() shl 1) or 1)).toByte()
        data[p + 1] = ((v shr 22) and 0xFFL).toByte()
        data[p + 2] = (((((v shr 15) and 0x7FL).toInt() shl 1) or 1)).toByte()
        data[p + 3] = ((v shr 7) and 0xFFL).toByte()
        data[p + 4] = ((((v and 0x7FL).toInt() shl 1) or 1)).toByte()
    }

    private fun readPesPtsAt(data: ByteArray, start: Int): Long {
        if (start == 0) return readPesPts(data)
        val pkt = data.copyOfRange(start, start + TS_PACKET)
        return readPesPts(pkt)
    }

    private fun rewritePesTimestamps(data: ByteArray, start: Int, offset: Long): Long {
        if ((data[start + 1].toInt() and 0x40) == 0) return LiveTsJoin.UNSET
        val payload = payloadStart(data, start) ?: return LiveTsJoin.UNSET
        if (payload + 14 > start + TS_PACKET) return LiveTsJoin.UNSET
        if (data[payload] != 0.toByte() || data[payload + 1] != 0.toByte() || data[payload + 2] != 1.toByte()) {
            return LiveTsJoin.UNSET
        }
        val flags = data[payload + 7].toInt() and 0xFF
        var pts = LiveTsJoin.UNSET
        if (flags and 0x80 != 0 && payload + 14 <= start + TS_PACKET) {
            val raw = readTimestamp5(data, payload + 9)
            val neu = apply(raw, offset)
            writeTimestamp5(data, payload + 9, neu)
            pts = neu
        }
        if (flags and 0x40 != 0 && payload + 19 <= start + TS_PACKET) {
            val raw = readTimestamp5(data, payload + 14)
            writeTimestamp5(data, payload + 14, apply(raw, offset))
        }
        return pts
    }

    private fun rewritePcr(data: ByteArray, start: Int, offset: Long) {
        val afc = (data[start + 3].toInt() shr 4) and 0x03
        if (afc != 2 && afc != 3) return
        val afl = data[start + 4].toInt() and 0xFF
        if (afl < 7) return
        val flags = data[start + 5].toInt() and 0xFF
        var i = start + 6
        if (flags and 0x10 != 0) {
            shiftPcrAt(data, i, offset)
            i += 6
        }
        if (flags and 0x08 != 0 && i + 6 <= start + TS_PACKET) {
            shiftPcrAt(data, i, offset)
        }
    }

    private fun shiftPcrAt(data: ByteArray, p: Int, offsetPts: Long) {
        val (base, ext) = readPcr(data, p)
        val total = base * 300L + ext + (offsetPts and PTS_MASK) * 300L
        // PCR-Basis ist 33 Bit wie PTS; der 27-MHz-Anteil bleibt im gültigen Bereich 0..299.
        val wrap = 1L shl 33
        var newTotal = total % (wrap * 300L)
        if (newTotal < 0) newTotal += wrap * 300L
        writePcr(data, p, (newTotal / 300L) and PTS_MASK, newTotal % 300L)
    }

    fun readPcr(data: ByteArray, p: Int): Pair<Long, Long> {
        fun u(i: Int) = data[p + i].toLong() and 0xFFL
        val base = (u(0) shl 25) or (u(1) shl 17) or (u(2) shl 9) or (u(3) shl 1) or (u(4) shr 7)
        val ext = ((u(4) and 0x01L) shl 8) or u(5)
        return base to ext
    }

    fun writePcr(data: ByteArray, p: Int, base: Long, ext: Long) {
        val b = base and PTS_MASK
        val e = ext and 0x1FFL
        data[p] = ((b shr 25) and 0xFFL).toByte()
        data[p + 1] = ((b shr 17) and 0xFFL).toByte()
        data[p + 2] = ((b shr 9) and 0xFFL).toByte()
        data[p + 3] = ((b shr 1) and 0xFFL).toByte()
        // Bit 7 = base LSB, Bits 6–1 reserviert (0b011111), Bit 0 = ext MSB
        data[p + 4] = ((((b and 1L) shl 7) or (0x3EL) or ((e shr 8) and 1L))).toByte()
        data[p + 5] = (e and 0xFFL).toByte()
    }

    private fun payloadStart(data: ByteArray, start: Int): Int? {
        val afc = (data[start + 3].toInt() shr 4) and 0x03
        var off = start + 4
        if (afc == 2 || afc == 3) {
            val afl = data[start + 4].toInt() and 0xFF
            off = start + 5 + afl
        }
        if (afc == 2) return null
        if (off >= start + TS_PACKET) return null
        return off
    }
}
