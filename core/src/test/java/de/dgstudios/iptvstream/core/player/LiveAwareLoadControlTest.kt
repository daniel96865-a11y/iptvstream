package de.dgstudios.iptvstream.core.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveAwareLoadControlTest {
    private val mb = 1024L * 1024L

    @Test fun liveKeepsReadingUpToTheMaximumWithoutPause() {
        // Zwischen Minimum und Maximum wird weiter gelesen (kein Stau auf der Verbindung).
        assertTrue(LiveAwareLoadControl.shouldLoadLive(20_000_000, 10 * mb, 8_000, 45_000, (48 * mb).toInt()))
        assertTrue(LiveAwareLoadControl.shouldLoadLive(44_900_000, 10 * mb, 8_000, 45_000, (48 * mb).toInt()))
        assertFalse(LiveAwareLoadControl.shouldLoadLive(45_000_000, 10 * mb, 8_000, 45_000, (48 * mb).toInt()))
    }

    @Test fun memoryLimitStopsAboveTheMinimumOnly() {
        assertFalse(LiveAwareLoadControl.shouldLoadLive(20_000_000, 48 * mb, 8_000, 45_000, (48 * mb).toInt()))
        assertTrue(LiveAwareLoadControl.shouldLoadLive(5_000_000, 60 * mb, 8_000, 45_000, (48 * mb).toInt()))
    }
}
