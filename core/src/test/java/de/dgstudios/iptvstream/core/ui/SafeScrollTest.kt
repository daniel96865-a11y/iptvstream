package de.dgstudios.iptvstream.core.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Absturz IPTVSTREAM-3 (1.0.75+83): Beim Hin- und Herschalten zwischen „Meine Sender“ und
 * „Freie Sender“ war die Liste kurz leer; die gespeicherte Position wurde mit
 * `coerceAtMost(items.lastIndex)` = -1 begrenzt und `scrollToItem(-1)` warf
 * „Index should be non-negative (-1)“. (Compose selbst lässt sich ohne Layout nicht scrollen,
 * deshalb wird hier die Index-Berechnung geprüft; scrollToItemSafe fängt negative Werte zusätzlich ab.)
 */
class SafeScrollTest {
    @Test
    fun oldExpressionWasNegativeForEmptyList() {
        val items = emptyList<String>()
        assertEquals(-1, 5.coerceAtMost(items.lastIndex))
    }

    @Test
    fun clampIndexNeverNegative() {
        assertEquals(0, clampIndex(7, 0))
        assertEquals(4, clampIndex(7, 5))
        assertEquals(3, clampIndex(3, 5))
        assertEquals(0, clampIndex(-2, 5))
    }

    @Test
    fun repeatedProfileSwitchPattern() {
        // Viele Wechsel: Liste abwechselnd leer und mit 342 Sendern, Position bleibt gemerkt.
        var remembered = 120
        repeat(200) { i ->
            val size = if (i % 2 == 0) 0 else 342
            val idx = clampIndex(remembered, size)
            assertTrue(idx >= 0 && (size == 0 || idx < size))
            remembered = (remembered + 37) % 400
        }
    }
}
