package de.dgstudios.iptvstream.core.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReconnectBackoffTest {
    @Test
    fun liveRetriesQuickly() {
        assertEquals(listOf(1_000L, 2_000L, 3_000L, 3_000L, 3_000L), (1..5).map { ReconnectBackoff.waitMs(it, live = true) })
    }

    @Test
    fun vodKeepsOldBackoff() {
        assertEquals(listOf(1_000L, 2_000L, 4_000L, 8_000L, 8_000L), (1..5).map { ReconnectBackoff.waitMs(it, live = false) })
    }

    @Test
    fun liveFirstAttemptsAreSilent() {
        assertFalse(ReconnectBackoff.showNotice(1, live = true))
        assertFalse(ReconnectBackoff.showNotice(2, live = true))
        assertTrue(ReconnectBackoff.showNotice(3, live = true))
        assertTrue(ReconnectBackoff.showNotice(1, live = false))
    }
}
