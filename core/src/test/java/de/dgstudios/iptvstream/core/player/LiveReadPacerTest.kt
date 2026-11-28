package de.dgstudios.iptvstream.core.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveReadPacerTest {
    @Test fun noDelayWhenUnderTheRate() {
        assertEquals(0L, LiveReadPacer.delayMs(50_000, 1_000, 100_000))
    }

    @Test fun delaysWhenFasterThanRealtime() {
        val d = LiveReadPacer.delayMs(200_000, 100, 100_000)
        assertTrue("erwartet eine Drossel, war $d ms", d in 50L..200L)
    }

    @Test fun pacesNearTheMaxBuffer() {
        assertFalse(LiveReadPacer.shouldPace(30_000, 40_000))
        assertTrue(LiveReadPacer.shouldPace(38_000, 40_000))
        assertTrue(LiveReadPacer.shouldPace(40_000, 40_000))
        assertFalse(LiveReadPacer.shouldPace(10_000, 0))
    }
}
