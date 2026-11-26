package de.dgstudios.iptvstream.core.player

import de.dgstudios.iptvstream.core.player.LiveCushion.Companion.GENTLE
import de.dgstudios.iptvstream.core.player.LiveCushion.Companion.NORMAL
import de.dgstudios.iptvstream.core.player.LiveCushion.Companion.STRONG
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveCushionTest {
    private fun medium() = LiveCushion(baseTargetMs = 10_000, stepMs = 5_000, capMs = 25_000)

    @Test fun afterAFastZapPlaysSlowerUntilTheTargetIsReached() {
        val c = medium()
        assertEquals(STRONG, c.speedFor(800, NORMAL))
        assertEquals(GENTLE, c.speedFor(5_000, STRONG))
        assertEquals(GENTLE, c.speedFor(9_500, GENTLE))
        assertEquals(NORMAL, c.speedFor(10_000, GENTLE))
    }

    @Test fun hysteresisAvoidsFlappingAroundTheTarget() {
        val c = medium()
        // Ziel erreicht: kleine Schwankungen unter dem Ziel ändern das Tempo nicht.
        assertEquals(NORMAL, c.speedFor(9_000, NORMAL))
        assertEquals(NORMAL, c.speedFor(8_100, NORMAL))
        assertEquals(GENTLE, c.speedFor(7_900, NORMAL))
        // Aus der Gefahrenzone erst mit etwas Abstand zurück auf sanft.
        assertEquals(STRONG, c.speedFor(2_500, STRONG))
        assertEquals(GENTLE, c.speedFor(3_100, STRONG))
    }

    @Test fun lowBufferAlwaysSlowsDownStrongly() {
        val c = medium()
        assertEquals(STRONG, c.speedFor(1_500, NORMAL))
        assertEquals(STRONG, c.speedFor(0, GENTLE))
    }

    @Test fun everyRebufferRaisesTheTargetUpToTheCap() {
        val c = medium()
        assertEquals(10_000, c.targetMs)
        c.onRebuffer()
        assertEquals(15_000, c.targetMs)
        assertEquals(GENTLE, c.speedFor(12_000, NORMAL))
        repeat(10) { c.onRebuffer() }
        assertEquals(25_000, c.targetMs)
        c.reset()
        assertEquals(0, c.rebuffers)
        assertEquals(10_000, c.targetMs)
    }

    @Test fun slowSpeedsReallyGrowTheBufferWithinMinutes() {
        // Echtzeit-Strom: Vorlauf wächst pro Sekunde um (1 - Tempo).
        val c = medium()
        var buffered = 1_000.0
        var speed = NORMAL
        var seconds = 0
        while (buffered < c.targetMs && seconds < 600) {
            speed = c.speedFor(buffered.toLong(), speed)
            buffered += (1 - speed) * 1_000
            seconds++
        }
        assertTrue("10 s Vorlauf nach $seconds s", seconds in 120..300)
        assertEquals(NORMAL, c.speedFor(buffered.toLong(), speed))
    }

    @Test fun speedsStayCloseToNormal() {
        assertTrue(GENTLE in 0.95f..0.99f)
        assertTrue(STRONG in 0.9f..GENTLE)
    }
}
