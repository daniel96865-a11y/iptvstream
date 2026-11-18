package de.dgstudios.iptvstream.tv

import org.junit.Assert.assertEquals
import org.junit.Test

class TvOkKeyTest {
    @Test
    fun firstOkOnlyShowsInfo() {
        assertEquals(OkAction.SHOW_INFO, TvOkKey.action(numberPending = false, repeatCount = 0, infoVisible = false))
    }

    @Test
    fun secondOkTogglesPause() {
        assertEquals(OkAction.TOGGLE_PLAY, TvOkKey.action(numberPending = false, repeatCount = 0, infoVisible = true))
    }

    @Test
    fun heldOkDoesNothing() {
        assertEquals(OkAction.NONE, TvOkKey.action(numberPending = false, repeatCount = 1, infoVisible = true))
        assertEquals(OkAction.NONE, TvOkKey.action(numberPending = false, repeatCount = 3, infoVisible = false))
    }

    @Test
    fun okConfirmsChannelNumberFirst() {
        assertEquals(OkAction.COMMIT_NUMBER, TvOkKey.action(numberPending = true, repeatCount = 0, infoVisible = true))
        assertEquals(OkAction.COMMIT_NUMBER, TvOkKey.action(numberPending = true, repeatCount = 0, infoVisible = false))
    }
}
