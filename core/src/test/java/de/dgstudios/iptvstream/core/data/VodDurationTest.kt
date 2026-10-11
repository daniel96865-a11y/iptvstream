package de.dgstudios.iptvstream.core.data

import org.junit.Assert.assertEquals
import org.junit.Test

class VodDurationTest {
    @Test
    fun clockWithoutSecondsIsHoursAndMinutes() {
        assertEquals(93, VodDuration.minutes(null, "01:33"))
        assertEquals(93, VodDuration.minutes(null, "1:33:12"))
    }

    @Test
    fun minutesStoredInDurationSecsAreNotDividedAgain() {
        assertEquals(93, VodDuration.minutes("93", "01:33:12"))
        assertEquals(93, VodDuration.minutes("93", null))
    }

    @Test
    fun realSecondCountStaysAFeatureLength() {
        assertEquals(93, VodDuration.minutes("5592", "01:33:12"))
        assertEquals(93, VodDuration.minutes("5592", null))
    }

    @Test
    fun oneMinuteFilmStaysOneMinute() {
        assertEquals(1, VodDuration.minutes("65", "00:01:05"))
        assertEquals(1, VodDuration.minutes(null, "00:01"))
    }

    @Test
    fun runtimeReadsAsHoursAndMinutes() {
        assertEquals("1 Std. 33 Min.", VodDuration.formatRuntime(93))
        assertEquals("2 Std.", VodDuration.formatRuntime(120))
        assertEquals("45 Min.", VodDuration.formatRuntime(45))
        assertEquals("", VodDuration.formatRuntime(0))
    }
}
