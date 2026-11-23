package de.dgstudios.iptvstream.core.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DecoderModeTest {
    private data class C(val name: String, val sw: Boolean)

    private val list = listOf(C("hw1", false), C("sw1", true), C("hw2", false), C("sw2", true))
    private fun names(mode: String, l: List<C> = list) = DecoderMode.order(mode, l) { it.sw }.map { it.name }

    @Test fun unknownIsHardwarePreferred() {
        assertEquals(DecoderMode.HW, DecoderMode.normalize(null))
        assertEquals(DecoderMode.HW, DecoderMode.normalize("quatsch"))
        assertEquals(DecoderMode.SW_ONLY, DecoderMode.normalize(" SW_ONLY "))
    }

    @Test fun hardwarePreferredKeepsPlatformOrder() {
        assertEquals(listOf("hw1", "sw1", "hw2", "sw2"), names(DecoderMode.HW))
    }

    @Test fun softwarePreferredPutsSoftwareFirst() {
        assertEquals(listOf("sw1", "sw2", "hw1", "hw2"), names(DecoderMode.SW))
    }

    @Test fun onlyModesFilterButNeverLeaveNothing() {
        assertEquals(listOf("hw1", "hw2"), names(DecoderMode.HW_ONLY))
        assertEquals(listOf("sw1", "sw2"), names(DecoderMode.SW_ONLY))
        val hwOnly = listOf(C("hw1", false))
        assertEquals(listOf("hw1"), names(DecoderMode.SW_ONLY, hwOnly))
    }

    @Test fun extensionModes() {
        assertEquals(DecoderMode.EXTENSION_OFF, DecoderMode.extensionMode(DecoderMode.HW_ONLY))
        assertEquals(DecoderMode.EXTENSION_ON, DecoderMode.extensionMode(DecoderMode.HW))
        assertEquals(DecoderMode.EXTENSION_PREFER, DecoderMode.extensionMode(DecoderMode.SW))
        assertEquals(DecoderMode.EXTENSION_PREFER, DecoderMode.extensionMode(DecoderMode.SW_ONLY))
        assertFalse(DecoderMode.ffmpegAllowed(DecoderMode.HW_ONLY))
        assertTrue(DecoderMode.ffmpegAllowed(DecoderMode.HW))
        assertTrue(DecoderMode.audioFfmpegOnly(DecoderMode.SW_ONLY, ffmpegSupports = true))
        assertFalse(DecoderMode.audioFfmpegOnly(DecoderMode.SW_ONLY, ffmpegSupports = false))
        assertFalse(DecoderMode.audioFfmpegOnly(DecoderMode.SW, ffmpegSupports = true))
    }
}
