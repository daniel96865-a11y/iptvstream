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

    @Test
    fun moveToStartAndEnd() {
        assertEquals(listOf("c", "a", "b"), ListOrder.moveToStart(listOf("a", "b", "c"), "c"))
        assertEquals(listOf("b", "c", "a"), ListOrder.moveToEnd(listOf("a", "b", "c"), "a"))
        assertEquals(listOf("a", "c", "b"), ListOrder.moveTo(listOf("a", "b", "c"), "c", 1))
    }

    @Test
    fun sortByLabel() {
        val labels = mapOf("a" to "Zebra", "b" to "Affe", "c" to "Bär")
        assertEquals(listOf("b", "c", "a"), ListOrder.sortBy(listOf("a", "b", "c")) { labels.getValue(it) })
    }

    @Test
    fun moveByKeyAndReplaceVisible() {
        assertEquals(listOf("b", "a", "c"), ListOrder.moveByKey(listOf("a", "b", "c"), "a", "b"))
        assertEquals(listOf("a", "c", "b"), ListOrder.moveByKey(listOf("a", "b", "c"), "b", "c"))
        assertEquals(listOf("a", "b", "c"), ListOrder.moveByKey(listOf("a", "b", "c"), "a", "a"))
        val items = listOf("x" to 1, "y" to 2, "z" to 3)
        assertEquals(listOf("y" to 2, "x" to 1, "z" to 3), ListOrder.moveByKey(items, { it.first }, "x", "y"))
        assertEquals(listOf("b", "c", "a", "hidden"), ListOrder.replaceVisible(listOf("a", "b", "c", "hidden"), listOf("b", "c", "a")))
    }
}
