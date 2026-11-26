package de.dgstudios.iptvstream.core.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StreamStatsTest {
    @Test
    fun normalizesMeasuredFps() {
        assertEquals(25f, StreamStats.normalizeFps(24.7f))
        assertEquals(50f, StreamStats.normalizeFps(49.2f))
        assertEquals(29.97f, StreamStats.normalizeFps(29.9f))
        assertNull(StreamStats.normalizeFps(0f))
        assertNull(StreamStats.normalizeFps(40f))
    }

    @Test
    fun picksMatchingRefreshRate() {
        val modes = listOf(60f, 59.94f, 50f, 24f, 23.976f)
        assertEquals(50f, StreamStats.pickRefreshRate(25f, modes))
        assertEquals(50f, StreamStats.pickRefreshRate(50f, modes))
        assertEquals(59.94f, StreamStats.pickRefreshRate(29.97f, modes))
        assertEquals(23.976f, StreamStats.pickRefreshRate(23.976f, modes))
        assertNull(StreamStats.pickRefreshRate(25f, listOf(60f)))
    }

    @Test
    fun ratesAndMedian() {
        assertEquals(8_000, StreamStats.kbps(1_000_000, 1_000))
        assertNull(StreamStats.kbps(10, 0))
        assertEquals(400L, StreamStats.median(listOf(900L, 300L, 400L)))
        assertEquals(350L, StreamStats.median(listOf(300L, 400L)))
        assertNull(StreamStats.median(emptyList()))
        assertEquals("8,0 Mbit/s", StreamStats.kbpsLabel(8_000))
        assertEquals("25 fps", StreamStats.fpsLabel(25f))
    }

    @Test fun infoShowsBufferTargetRebuffersAndSpeed() {
        val lines = StreamInfo(
            bufferedMs = 7_420, bufferTargetMs = 15_000, bufferMaxMs = 45_000,
            rebuffers = 1, reconnects = 2, speed = 0.96f,
        ).lines()
        org.junit.Assert.assertTrue(lines.contains("Puffer: 7,4 s · Ziel 15,0 s · max. 45,0 s"))
        org.junit.Assert.assertTrue(lines.contains("Puffer leer: 1× · Neu verbunden: 2× · verworfene Bilder: 0"))
        org.junit.Assert.assertTrue(lines.contains("Tempo 96 % – Vorlauf wird aufgebaut"))
        org.junit.Assert.assertTrue(StreamInfo(bufferedMs = 1_000).lines().none { it.startsWith("Tempo") })
    }
}
