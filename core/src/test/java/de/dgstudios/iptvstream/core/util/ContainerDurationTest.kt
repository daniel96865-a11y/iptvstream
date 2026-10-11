package de.dgstudios.iptvstream.core.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.nio.ByteBuffer

class ContainerDurationTest {
    @Test
    fun mp4MvhdIsMilliseconds() {
        val ms = ContainerDuration.read(mp4(timescale = 1000, duration = 5_592_000))
        assertEquals(5_592_000L, ms)
    }

    @Test
    fun mkvDurationUsesTimestampScale() {
        val ms = ContainerDuration.read(mkv(scale = 1_000_000L, durationMs = 5_592_000.0))
        assertEquals(5_592_000L, ms)
    }

    @Test
    fun emptyBytesHaveNoRuntime() {
        assertNull(ContainerDuration.read(ByteArray(0)))
        assertNull(ContainerDuration.read("keine datei".toByteArray()))
    }

    private fun mp4(timescale: Int, duration: Int): ByteArray {
        val payload = ByteBuffer.allocate(20)
        payload.putInt(0)
        payload.putInt(0)
        payload.putInt(0)
        payload.putInt(timescale)
        payload.putInt(duration)
        return atom("moov", atom("mvhd", payload.array()))
    }

    private fun atom(type: String, payload: ByteArray): ByteArray {
        val out = ByteBuffer.allocate(8 + payload.size)
        out.putInt(8 + payload.size)
        out.put(type.toByteArray(Charsets.US_ASCII))
        out.put(payload)
        return out.array()
    }

    private fun mkv(scale: Long, durationMs: Double): ByteArray {
        val scaleEl = element(byteArrayOf(0x2A, 0xD7.toByte(), 0xB1.toByte()), uint(scale, 4))
        val durationEl = element(byteArrayOf(0x44, 0x89.toByte()), double(durationMs))
        val info = element(byteArrayOf(0x15, 0x49, 0xA9.toByte(), 0x66), scaleEl + durationEl)
        return element(byteArrayOf(0x18, 0x53, 0x80.toByte(), 0x67), info)
    }

    private fun element(id: ByteArray, payload: ByteArray): ByteArray = id + size(payload.size) + payload

    private fun size(len: Int): ByteArray = byteArrayOf((0x80 or len).toByte())

    private fun uint(value: Long, len: Int): ByteArray {
        val out = ByteArray(len)
        var v = value
        for (i in len - 1 downTo 0) {
            out[i] = (v and 0xFF).toByte()
            v = v ushr 8
        }
        return out
    }

    private fun double(value: Double): ByteArray {
        val out = ByteArray(8)
        ByteBuffer.wrap(out).putDouble(value)
        return out
    }
}
