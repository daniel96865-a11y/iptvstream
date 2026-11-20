package de.dgstudios.iptvstream.core.data

import org.junit.Assert.assertEquals
import org.junit.Test

class ListOrderTest {
    @Test
    fun applyOrdersAndHides() {
        val items = listOf("a", "b", "c", "d")
        assertEquals(listOf("c", "a", "b", "d"), ListOrder.apply(items, { it }, listOf("c", "a"), emptySet()))
        assertEquals(listOf("a", "c"), ListOrder.apply(items, { it }, listOf("a", "b", "c"), setOf("b", "d")))
    }

    @Test
    fun moveSwapsNeighbors() {
        assertEquals(listOf("b", "a", "c"), ListOrder.move(listOf("a", "b", "c"), "a", 1))
        assertEquals(listOf("a", "c", "b"), ListOrder.move(listOf("a", "b", "c"), "c", -1))
        assertEquals(listOf("a", "b", "c"), ListOrder.move(listOf("a", "b", "c"), "a", -1))
    }

    @Test
    fun encodeRoundTrip() {
        val ids = listOf("x", "y z", "1")
        assertEquals(ids, ListOrder.decode(ListOrder.encode(ids)))
    }
}
