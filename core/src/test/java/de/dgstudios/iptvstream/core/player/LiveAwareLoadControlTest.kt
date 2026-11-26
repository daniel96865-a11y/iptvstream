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

    @Test fun zapStartsWithUnderOneSecondOfData() {
        for (b in listOf("small", "medium", "large")) {
            val (zap, after) = PlaybackBuffer.liveStart(b)
            assertTrue(LiveAwareLoadControl.liveStartReached(zap * 1_000L, false, zap, after))
            assertTrue(LiveAwareLoadControl.liveStartReached(999_000, false, zap, after))
            assertFalse(LiveAwareLoadControl.liveStartReached(zap * 1_000L - 1, false, zap, after))
        }
    }

    @Test fun afterAStallMoreDataIsNeeded() {
        val (zap, after) = PlaybackBuffer.liveStart("medium")
        assertFalse(LiveAwareLoadControl.liveStartReached(1_000_000, true, zap, after))
        assertTrue(LiveAwareLoadControl.liveStartReached(4_000_000, true, zap, after))
    }

    @Test fun memoryLimitNeverBlocksTheStart() {
        // Unter dem Minimum wird immer geladen, auch wenn die Byte-Grenze schon erreicht ist –
        // sonst würde die Startschwelle nie erreicht.
        val (zap, after) = PlaybackBuffer.liveStart("large")
        assertTrue(LiveAwareLoadControl.shouldLoadLive(after * 1_000L - 1, 500 * mb, after, 90_000, (8 * mb).toInt()))
        assertTrue(LiveAwareLoadControl.shouldLoadLive(zap * 1_000L - 1, 500 * mb, 5_000, 90_000, (8 * mb).toInt()))
    }
}
