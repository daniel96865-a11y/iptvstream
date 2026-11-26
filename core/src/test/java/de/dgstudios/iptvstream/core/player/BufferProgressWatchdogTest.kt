package de.dgstudios.iptvstream.core.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BufferProgressWatchdogTest {
    @Test fun slowBufferGrowthDoesNotRestartEvenAfterTenSeconds() {
        val watchdog = BufferProgressWatchdog()
        for (i in 0L..44L) {
            assertFalse(watchdog.observe(i * 500, true, 1_000 + i * 50))
        }
    }

    @Test fun tenSecondsWithoutProgressTriggersReconnect() {
        val watchdog = BufferProgressWatchdog()
        assertFalse(watchdog.observe(0, true, 500))
        assertFalse(watchdog.observe(9_999, true, 600))
        assertTrue(watchdog.observe(10_000, true, 510))
        assertFalse(watchdog.observe(10_500, true, 500))
    }

    @Test fun aSegmentRestartsTheTimeout() {
        val watchdog = BufferProgressWatchdog()
        assertFalse(watchdog.observe(0, true, 0))
        assertFalse(watchdog.observe(9_500, true, 2_000))
        assertFalse(watchdog.observe(10_000, true, 2_000))
        assertTrue(watchdog.observe(19_500, true, 2_000))
    }

    @Test fun pausingOrChangingStreamResetsTheClock() {
        val watchdog = BufferProgressWatchdog()
        assertFalse(watchdog.observe(0, true, 7_000))
        assertFalse(watchdog.observe(12_000, false, 7_000))
        assertFalse(watchdog.observe(24_000, true, 0))
        watchdog.reset()
        assertFalse(watchdog.observe(38_000, true, 0))
        assertTrue(watchdog.observe(48_000, true, 0))
    }
}
