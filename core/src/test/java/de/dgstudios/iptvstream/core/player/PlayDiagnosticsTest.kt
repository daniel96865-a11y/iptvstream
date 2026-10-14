package de.dgstudios.iptvstream.core.player

import androidx.media3.common.Player
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayDiagnosticsTest {
    @Test
    fun lineNamesVersionStateDecodersAndFrames() {
        val d = PlayDiagnostics()
        d.videoDecoder = "OMX.amlogic.avc.decoder"
        d.audioDecoder = "OMX.dolby.ac3.decoder"
        d.dropped = 4
        d.underruns = 2
        d.issue("Bild steht")
        val line = d.line("1.0.45", Player.STATE_READY, 3_400, 25, "1280×720 · H.264", "AC3 · 5.1")
        assertEquals(
            "v1.0.45 · Bereit · Puffer 3 s · Bilder 25 (verworfen 4) · Video 1280×720 · H.264 [OMX.amlogic.avc.decoder] · " +
                "Ton AC3 · 5.1 [OMX.dolby.ac3.decoder] · Ton-Aussetzer 2 · Bild steht",
            line,
        )
    }

    @Test
    fun missingValuesAreSpelledOutAndResetClears() {
        val d = PlayDiagnostics()
        d.issue("x".repeat(500))
        assertEquals(120, d.lastIssue?.length)
        val line = d.line("", Player.STATE_BUFFERING, -5, null, null, null)
        assertEquals("v? · Puffern · Puffer 0 s · Video keins · Ton keiner · " + "x".repeat(120), line)
        d.reset()
        assertFalse(d.line("1", Player.STATE_IDLE, 0, null, null, null).contains("x"))
        assertTrue(d.line("1", Player.STATE_IDLE, 0, null, null, null).contains("Leerlauf"))
    }
}
