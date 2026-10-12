package de.dgstudios.iptvstream.core.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackBufferTest {
    @Test
    fun largeTrackIsLongerThanSmall() {
        val small = PlaybackBuffer.spec("small").second.toLong()
        val medium = PlaybackBuffer.spec("medium").second.toLong()
        val large = PlaybackBuffer.spec("large").second.toLong()
        assertEquals(15_000, small.toInt())
        assertEquals(40_000, medium.toInt())
        assertEquals(90_000, large.toInt())
        assertEquals(1f, PlaybackBuffer.trackFraction(large))
        assertTrue(PlaybackBuffer.trackFraction(small) < PlaybackBuffer.trackFraction(medium))
        assertTrue(PlaybackBuffer.trackFraction(medium) < PlaybackBuffer.trackFraction(large))
    }

    @Test
    fun fillUsesTheChosenCapacity() {
        assertEquals(1f, PlaybackBuffer.fill(15_000, 15_000))
        assertEquals(15_000f / 90_000f, PlaybackBuffer.fill(15_000, 90_000))
        assertEquals(1f, PlaybackBuffer.fill(120_000, 90_000))
        assertEquals(0f, PlaybackBuffer.fill(0, 0))
    }

    @Test
    fun labelShowsSecondsAgainstTheSetting() {
        assertEquals("13 / 40 s", PlaybackBuffer.label(12_500, 40_000))
        assertEquals("15 / 15 s", PlaybackBuffer.label(20_000, 15_000))
    }
}
