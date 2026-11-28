package de.dgstudios.iptvstream.core.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SupplyStallGuardTest {
    @Test fun kicksAfterThreeSecondsWithoutBytes() {
        val g = SupplyStallGuard()
        assertFalse(g.observe(0, true, 100))
        assertFalse(g.observe(2_500, true, 100))
        assertEquals(2_500, g.stalledMs(2_500))
        assertTrue(g.observe(3_000, true, 100))
        assertEquals(1, g.kicks)
    }

    @Test fun flowingBytesNeverKick() {
        val g = SupplyStallGuard()
        for (t in 0L..20_000L step 500L) assertFalse(g.observe(t, true, t * 100))
    }

    @Test fun fullBufferPauseIsNotAStall() {
        val g = SupplyStallGuard()
        g.observe(0, true, 100)
        for (t in 500L..20_000L step 500L) assertFalse(g.observe(t, false, 100))
        assertEquals(0, g.stalledMs(20_000))
        assertFalse(g.observe(20_500, true, 100))
        assertFalse(g.observe(23_000, true, 100))
        assertTrue(g.observe(23_500, true, 100))
    }

    @Test fun backsOffAndStopsAfterMaxKicks() {
        val g = SupplyStallGuard(3_000, 3)
        g.observe(0, true, 1)
        assertTrue(g.observe(3_000, true, 1))
        assertFalse(g.observe(8_500, true, 1)) // zweiter erst 6 s nach dem ersten
        assertTrue(g.observe(9_000, true, 1))
        assertFalse(g.observe(17_500, true, 1))
        assertTrue(g.observe(18_000, true, 1))
        for (t in 18_500L..60_000L step 500L) assertFalse(g.observe(t, true, 1))
    }

    @Test fun dataResetsKicks() {
        val g = SupplyStallGuard()
        g.observe(0, true, 1)
        assertTrue(g.observe(3_000, true, 1))
        assertFalse(g.observe(3_500, true, 10_000))
        assertEquals(0, g.kicks)
        assertTrue(g.observe(6_500, true, 10_000))
    }

    @Test fun doesNotKickWhileBufferIsComfortable() {
        val g = SupplyStallGuard()
        g.observe(0, true, 100, bufferedMs = 30_000, kickBelowMs = 6_000)
        assertFalse(g.observe(3_000, true, 100, bufferedMs = 27_000, kickBelowMs = 6_000))
        assertFalse(g.observe(10_000, true, 100, bufferedMs = 20_000, kickBelowMs = 6_000))
        assertEquals(10_000, g.stalledMs(10_000))
        assertEquals(0, g.kicks)
        // Sobald der Puffer knapp ist und schon lange nichts mehr kam: sofort eingreifen.
        assertTrue(g.observe(20_000, true, 100, bufferedMs = 5_000, kickBelowMs = 6_000))
        assertEquals(1, g.kicks)
    }

    @Test fun tricklingFillBytesAreStillAStall() {
        val g = SupplyStallGuard()
        g.observe(0, true, 0)
        assertFalse(g.observe(1_000, true, 188))
        assertFalse(g.observe(2_000, true, 376))
        assertTrue(g.observe(3_000, true, 564))
    }
}
