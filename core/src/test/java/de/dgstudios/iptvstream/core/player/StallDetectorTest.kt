package de.dgstudios.iptvstream.core.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StallDetectorTest {
    @Test
    fun movingPositionNeverStalls() {
        val d = StallDetector(6_000)
        var t = 0L
        repeat(40) {
            assertFalse(d.observe(t, true, t))
            t += 500
        }
    }

    @Test
    fun frozenPositionFiresAfterTheThresholdAndThenAgain() {
        val d = StallDetector(6_000)
        assertFalse(d.observe(0, true, 1_000))
        assertFalse(d.observe(5_500, true, 1_000))
        assertTrue(d.observe(6_000, true, 1_000))
        assertFalse(d.observe(8_000, true, 1_000))
        assertTrue(d.observe(12_000, true, 1_000))
    }

    @Test
    fun pausedOrBufferingDoesNotCount() {
        val d = StallDetector(6_000)
        assertFalse(d.observe(0, true, 500))
        assertFalse(d.observe(10_000, false, 500))
        assertFalse(d.observe(10_500, true, 500))
        assertFalse(d.observe(16_000, true, 500))
        assertTrue(d.observe(16_500, true, 500))
    }

    @Test
    fun recoveryResetsTheClock() {
        val d = StallDetector(6_000)
        d.observe(0, true, 100)
        assertTrue(d.observe(6_000, true, 100))
        assertFalse(d.observe(6_500, true, 700))
        assertFalse(d.observe(12_000, true, 700))
        assertTrue(d.observe(12_500, true, 700))
    }

    @Test
    fun smallHeapLimitsTheBufferButNotBelowTheFloor() {
        val big = 384 * 1024 * 1024
        assertEquals(big, PlaybackBuffer.capBytes(big, 0))
        assertEquals(60_397_977, PlaybackBuffer.capBytes(big, 192))
        assertEquals(24 * 1024 * 1024, PlaybackBuffer.capBytes(big, 64))
        assertEquals(20 * 1024 * 1024, PlaybackBuffer.capBytes(20 * 1024 * 1024, 64))
    }
}
