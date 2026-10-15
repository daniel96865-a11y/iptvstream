package de.dgstudios.iptvstream.core.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioDecoderChoiceTest {
    private val aac = "audio/mp4a-latm"
    private val all = listOf("c2.android.aac.decoder", "OMX.google.aac.decoder")

    @Test fun skipsRejectedDecoder() {
        val c = AudioDecoderChoice()
        assertTrue(c.reject(aac, "c2.android.aac.decoder"))
        assertEquals(listOf("OMX.google.aac.decoder"), c.select(aac, all) { it })
        assertFalse(c.reject(aac, "c2.android.aac.decoder"))
    }

    @Test fun keepsListWhenAllRejected() {
        val c = AudioDecoderChoice()
        all.forEach { c.reject(aac, it) }
        assertEquals(all, c.select(aac, all) { it })
    }

    @Test fun ffmpegOnlyRemovesMediaCodec() {
        val c = AudioDecoderChoice()
        c.useFfmpegOnly(aac)
        assertTrue(c.select(aac, all) { it }.isEmpty())
        c.clearFfmpegOnly(aac)
        assertEquals(all, c.select(aac, all) { it })
    }

    @Test fun otherMimesUntouched() {
        val c = AudioDecoderChoice()
        c.reject(aac, "c2.android.aac.decoder")
        assertEquals(all, c.select("audio/ac3", all) { it })
    }
}
