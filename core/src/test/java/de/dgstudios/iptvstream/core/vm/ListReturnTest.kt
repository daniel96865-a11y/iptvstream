package de.dgstudios.iptvstream.core.vm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ListReturnTest {
    @Test
    fun findsLastPlayedChannel() {
        assertEquals(2, ListReturn.indexOf(listOf("a", "b", "c"), "c"))
    }

    @Test
    fun unknownOrMissingChannelKeepsSavedPosition() {
        assertNull(ListReturn.indexOf(listOf("a", "b"), "x"))
        assertNull(ListReturn.indexOf(listOf("a", "b"), null))
    }
}
