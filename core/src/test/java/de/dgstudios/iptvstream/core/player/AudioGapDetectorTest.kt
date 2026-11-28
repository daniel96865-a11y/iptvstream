package de.dgstudios.iptvstream.core.player

import de.dgstudios.iptvstream.core.player.LiveTsJoin.Companion.PTS_HZ
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioGapDetectorTest {
    private val audio = 257

    @Test fun tightAudioFramesAreFine() {
        val frame = PTS_HZ * 32 / 1000
        val delivered = (0..20).map { audio to it * frame }
        assertFalse(AudioGapDetector.hasGap(delivered, setOf(audio)))
        assertTrue(AudioGapDetector.largestGapMs(delivered, setOf(audio)) < 50)
    }

    @Test fun oneSecondAudioHoleIsDetected() {
        val frame = PTS_HZ * 32 / 1000
        val delivered = listOf(
            audio to 0L,
            audio to frame,
            audio to frame + PTS_HZ, // 1 s Lücke
        )
        assertTrue(AudioGapDetector.hasGap(delivered, setOf(audio)))
        assertEquals(1000L, AudioGapDetector.largestGapMs(delivered, setOf(audio)))
    }

    @Test fun rewindIsDetectedAsAGap() {
        val delivered = listOf(audio to 90_000L, audio to 1_000L)
        assertTrue(AudioGapDetector.hasGap(delivered, setOf(audio)))
    }

    @Test fun regularMpegAudioSpacingIsFine() {
        val frame = PTS_HZ * 348 / 1000
        val delivered = (0..20).map { audio to it * frame }
        assertFalse(AudioGapDetector.hasGap(delivered, setOf(audio)))
        // Eine zusätzliche Sekunde über dem normalen Abstand ist eine Lücke.
        val withHole = delivered + listOf(audio to 21 * frame + PTS_HZ)
        assertTrue(AudioGapDetector.hasGap(withHole, setOf(audio)))
    }
}
