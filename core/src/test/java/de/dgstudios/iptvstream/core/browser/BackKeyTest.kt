package de.dgstudios.iptvstream.core.browser

import de.dgstudios.iptvstream.core.browser.BackKey.Action
import org.junit.Assert.assertEquals
import org.junit.Test

class BackKeyTest {
    @Test
    fun shortPressGoesBackOnRelease() {
        assertEquals(Action.NONE, BackKey.decide(down = true, repeatCount = 0, heldMs = 0, longFlag = false, closed = false))
        assertEquals(Action.BACK, BackKey.decide(down = false, repeatCount = 0, heldMs = 120, longFlag = false, closed = false))
    }

    @Test
    fun holdingClosesOnceAndReleaseDoesNothing() {
        // Wiederholung, aber noch zu kurz gehalten
        assertEquals(Action.NONE, BackKey.decide(true, 1, 300, longFlag = false, closed = false))
        // System meldet langes Drücken
        assertEquals(Action.CLOSE, BackKey.decide(true, 10, 500, longFlag = true, closed = false))
        // oder lange genug gehalten
        assertEquals(Action.CLOSE, BackKey.decide(true, 12, BackKey.LONG_MS, longFlag = false, closed = false))
        // danach nicht noch einmal schließen und beim Loslassen nicht zurückgehen
        assertEquals(Action.NONE, BackKey.decide(true, 13, 900, longFlag = true, closed = true))
        assertEquals(Action.NONE, BackKey.decide(false, 0, 1000, longFlag = false, closed = true))
    }

    @Test
    fun canceledReleaseDoesNothing() {
        assertEquals(Action.NONE, BackKey.decide(false, 0, 100, longFlag = false, closed = false, canceled = true))
    }
}
