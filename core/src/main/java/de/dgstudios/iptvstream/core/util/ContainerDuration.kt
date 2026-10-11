package de.dgstudios.iptvstream.core.util

import java.nio.ByteBuffer
import kotlin.math.min

/**
 * Liest die Laufzeit aus den ersten oder letzten Bytes einer MP4- oder MKV-Datei.
 * Es wird nichts geschätzt: ohne timescale und duration kommt null zurück.
 */
object ContainerDuration {
    fun read(data: ByteArray): Long? {
        mp4(data)?.let { return it }
        return mkv(data)
    }

    private fun plausible(ms: Long): Long? =
        if (ms in 5_000L..(12L * 60L * 60L * 1000L)) ms else null

    private fun mp4(data: ByteArray): Long? {
        var i = 0
        val limit = data.size - 3
        while (i < limit) {
            if (data[i] == 0x6d.toByte() && data[i + 1] == 0x76.toByte() &&
                data[i + 2] == 0x68.toByte() && data[i + 3] == 0x64.toByte()
            ) {
                parseMvhd(data, i)?.let { return it }
            }
            i++
        }
        return null
    }

    /** [at] zeigt auf die vier Zeichen „mvhd“. */
    private fun parseMvhd(data: ByteArray, at: Int): Long? {
        val payload = at + 4
        if (payload >= data.size) return null
        val version = data[payload].toInt() and 0xFF
        val timescale: Long
        val duration: Long
        if (version == 0) {
            if (payload + 20 > data.size) return null
            timescale = u32(data, payload + 12)
            duration = u32(data, payload + 16)
        } else if (version == 1) {
            if (payload + 32 > data.size) return null
            timescale = u32(data, payload + 20)
            duration = u64(data, payload + 24)
        } else {
            return null
        }
        if (timescale <= 0L || duration <= 0L) return null
        return plausible(duration * 1000L / timescale)
    }

    private fun mkv(data: ByteArray): Long? {
        var scale = 1_000_000L
        var duration: Double? = null
        fun walk(start: Int, end: Int, depth: Int) {
            if (depth > 6) return
            var pos = start
            var guard = 0
            while (pos + 2 < end && guard++ < 10_000) {
                val id = readId(data, pos) ?: return
                val size = readSize(data, id.next) ?: return
                val payload = size.next
                val known = size.value >= 0 && payload <= end && size.value <= (end - payload).toLong()
                val childEnd = if (known) payload + size.value.toInt() else end
                when (id.value) {
                    ID_TIMECODE_SCALE -> readUint(data, payload, min(8, childEnd - payload))?.let { scale = it }
                    ID_DURATION -> readFloat(data, payload, childEnd - payload)?.let { duration = it }
                    ID_EBML, ID_SEGMENT, ID_INFO -> if (childEnd > payload) walk(payload, childEnd, depth + 1)
                }
                if (!known) return
                if (childEnd <= pos) return
                pos = childEnd
            }
        }
        walk(0, data.size, 0)
        val value = duration ?: return null
        if (scale <= 0L || value <= 0.0) return null
        return plausible((value * scale / 1_000_000.0).toLong())
    }

    private fun readId(data: ByteArray, pos: Int): Cursor? {
        if (pos >= data.size) return null
        val first = data[pos].toInt() and 0xFF
        val len = vintLength(first)
        if (len !in 1..4 || pos + len > data.size) return null
        var value = 0L
        for (i in 0 until len) value = (value shl 8) or (data[pos + i].toLong() and 0xFF)
        return Cursor(value, pos + len)
    }

    private fun readSize(data: ByteArray, pos: Int): Cursor? {
        if (pos >= data.size) return null
        val first = data[pos].toInt() and 0xFF
        val len = vintLength(first)
        if (len !in 1..8 || pos + len > data.size) return null
        val mask = if (len >= 8) 0 else (1 shl (8 - len)) - 1
        var value = (first and mask).toLong()
        var allOnes = value == mask.toLong()
        for (i in 1 until len) {
            val b = data[pos + i].toLong() and 0xFF
            value = (value shl 8) or b
            if (b != 0xFFL) allOnes = false
        }
        if (allOnes && mask != 0) return Cursor(-1, pos + len)
        return Cursor(value, pos + len)
    }

    private fun vintLength(first: Int): Int {
        var len = 1
        var bit = 0x80
        while (len <= 8 && first and bit == 0) {
            len++
            bit = bit ushr 1
        }
        return if (first == 0) 9 else len
    }

    private fun readUint(data: ByteArray, pos: Int, len: Int): Long? {
        if (len <= 0 || pos < 0 || pos + len > data.size) return null
        var value = 0L
        for (i in 0 until len) value = (value shl 8) or (data[pos + i].toLong() and 0xFF)
        return value
    }

    private fun readFloat(data: ByteArray, pos: Int, len: Int): Double? {
        if (len == 4 && pos + 4 <= data.size) {
            val bits = readUint(data, pos, 4)?.toInt() ?: return null
            return Float.fromBits(bits).toDouble()
        }
        if (len >= 8 && pos + 8 <= data.size) {
            val bits = readUint(data, pos, 8) ?: return null
            return Double.fromBits(bits)
        }
        return null
    }

    private fun u32(data: ByteArray, pos: Int): Long =
        ByteBuffer.wrap(data, pos, 4).int.toLong() and 0xFFFF_FFFFL

    private fun u64(data: ByteArray, pos: Int): Long = ByteBuffer.wrap(data, pos, 8).long

    private data class Cursor(val value: Long, val next: Int)

    private const val ID_EBML = 0x1A45DFA3L
    private const val ID_SEGMENT = 0x18538067L
    private const val ID_INFO = 0x1549A966L
    private const val ID_TIMECODE_SCALE = 0x2AD7B1L
    private const val ID_DURATION = 0x4489L
}
