package de.dgstudios.iptvstream.core.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FlowMeterTest {
    @Test fun supplyBelowPlaybackShowsWhyTheBufferDrains() {
        // Anbieter liefert 0,95× Echtzeit, Player spielt 1,0×: Puffer sinkt stetig.
        val m = FlowMeter()
        for (s in 0..20) m.add(s * 1_000L, 10_000L + s * 950L, s * 1_000L)
        assertEquals(0.95f, m.supplyRate()!!, 0.001f)
        assertEquals(1.0f, m.playRate()!!, 0.001f)
    }

    @Test fun slowerPlaybackIsMeasured() {
        val m = FlowMeter()
        for (s in 0..10) m.add(s * 1_000L, s * 1_000L, s * 970L)
        assertEquals(0.97f, m.playRate()!!, 0.001f)
    }

    @Test fun needsFiveSecondsAndRestartsOnJumpsBack() {
        val m = FlowMeter()
        m.add(0, 5_000, 0)
        m.add(3_000, 8_000, 3_000)
        assertNull(m.supplyRate())
        m.add(6_000, 11_000, 6_000)
        assertEquals(1.0f, m.supplyRate()!!, 0.001f)
        // Neuverbindung: Positionen beginnen wieder bei 0.
        m.add(7_000, 500, 0)
        assertNull(m.supplyRate())
    }

    @Test fun usesASlidingWindow() {
        val m = FlowMeter(windowMs = 10_000)
        for (s in 0..10) m.add(s * 1_000L, s * 1_000L, s * 1_000L) // erst Echtzeit
        for (s in 11..30) m.add(s * 1_000L, 10_000L + (s - 10) * 500L, s * 1_000L) // dann halbe
        assertEquals(0.5f, m.supplyRate()!!, 0.05f)
    }
}
