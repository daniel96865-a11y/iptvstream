package de.dgstudios.iptvstream.core.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DevicePlaybackTest {
    @Test
    fun fireTvUsesTheConservativeDecoderPath() {
        assertTrue(DevicePlayback.conservativeVideo("Amazon", "AFTMM"))
        assertTrue(DevicePlayback.conservativeVideo("amazon", "AFTT"))
        assertTrue(DevicePlayback.conservativeVideo("Other", "AFTSSS"))
        assertFalse(DevicePlayback.conservativeVideo("Google", "Chromecast"))
        assertFalse(DevicePlayback.conservativeVideo(null, null))
        assertFalse(DevicePlayback.conservativeVideo(" Samsung ", "TV"))
    }

    @Test
    fun aRejectedDecoderIsSkippedOnlyForThatFormat() {
        val choice = VideoDecoderChoice()
        val codecs = listOf("omx.hevc", "c2.hevc", "omx.avc")
        assertEquals(codecs, choice.keep("video/hevc", codecs) { it })
        assertTrue(choice.reject("video/hevc", "omx.hevc"))
        assertFalse(choice.reject("video/hevc", "omx.hevc"))
        assertEquals(listOf("c2.hevc", "omx.avc"), choice.keep("video/hevc", codecs) { it })
        assertEquals(codecs, choice.keep("video/avc", codecs) { it })
        assertTrue(choice.reject(" video/hevc ", "c2.hevc"))
        assertEquals(listOf("omx.avc"), choice.keep("video/hevc", codecs) { it })
        assertTrue(choice.reject("video/hevc", "omx.avc"))
        assertEquals(codecs, choice.keep("video/hevc", codecs) { it })
        assertFalse(choice.reject(null, "omx.hevc"))
        assertFalse(choice.reject("video/hevc", " "))
    }
}
