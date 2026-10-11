package de.dgstudios.iptvstream.core.player

import de.dgstudios.iptvstream.core.util.Urls
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UhdPlaybackTest {
    @Test
    fun uhdIsFourKNotHd() {
        assertTrue(UhdPlayback.isUhd(3840, 2160))
        assertTrue(UhdPlayback.isUhd(2160, 3840))
        assertTrue(UhdPlayback.isUhd(4096, 2160))
        assertFalse(UhdPlayback.isUhd(1920, 1080))
        assertFalse(UhdPlayback.isUhd(2560, 1440))
        assertFalse(UhdPlayback.isUhd(0, 2160))
        assertFalse(UhdPlayback.isUhd(-1, -1))
    }

    @Test
    fun inputFloorOnlyForUhd() {
        assertEquals(UhdPlayback.INPUT_FLOOR_BYTES, UhdPlayback.inputFloor(3840, 2160))
        assertEquals(0, UhdPlayback.inputFloor(1920, 1080))
    }

    @Test
    fun raisedInputKeepsUnknownAndLargerValues() {
        assertEquals(-1, UhdPlayback.raisedInputSize(-1, UhdPlayback.INPUT_FLOOR_BYTES))
        assertEquals(20 * 1024 * 1024, UhdPlayback.raisedInputSize(20 * 1024 * 1024, UhdPlayback.INPUT_FLOOR_BYTES))
        assertEquals(UhdPlayback.INPUT_FLOOR_BYTES, UhdPlayback.raisedInputSize(6 * 1024 * 1024, UhdPlayback.INPUT_FLOOR_BYTES))
        assertEquals(6 * 1024 * 1024, UhdPlayback.raisedInputSize(6 * 1024 * 1024, 0))
    }

    @Test
    fun fourKDropsSoftwareWhenHardwareExists() {
        val codecs = listOf("soft" to true, "hw" to false)
        assertEquals(listOf("hw" to false), UhdPlayback.decodersFor(codecs, uhd = true) { it.second })
    }

    @Test
    fun fourKKeepsSoftwareWhenItIsTheOnlyDecoder() {
        val codecs = listOf("soft" to true)
        assertEquals(codecs, UhdPlayback.decodersFor(codecs, uhd = true) { it.second })
    }

    @Test
    fun hdKeepsEveryDecoder() {
        val codecs = listOf("soft" to true, "hw" to false)
        assertEquals(codecs, UhdPlayback.decodersFor(codecs, uhd = false) { it.second })
    }

    @Test
    fun originalFrameShrinksFourKToTheScreen() {
        assertEquals(1920 to 1080, UhdPlayback.fitInside(3840, 2160, 1920, 1080))
    }

    @Test
    fun originalFrameKeepsASmallerPicture() {
        assertEquals(1280 to 720, UhdPlayback.fitInside(1280, 720, 1920, 1080))
    }

    @Test
    fun originalFrameIgnoresAnUnknownScreen() {
        assertEquals(3840 to 2160, UhdPlayback.fitInside(3840, 2160, 0, 0))
        assertEquals(3840 to 2160, UhdPlayback.fitInside(3840, 2160, Int.MAX_VALUE, 1080))
    }

    @Test
    fun liveContainerSwapsOnlyNumericXtreamIds() {
        assertEquals(
            "http://h/live/u/p/12.m3u8",
            Urls.alternateLiveContainer("http://h/live/u/p/12.ts"),
        )
        assertEquals(
            "http://h/live/u/p/12.ts?x=1",
            Urls.alternateLiveContainer("http://h/live/u/p/12.m3u8?x=1"),
        )
        assertNull(Urls.alternateLiveContainer("http://h/movie/u/p/12.mkv"))
        assertNull(Urls.alternateLiveContainer("http://h/live/u/p/archive-1-2.ts"))
        assertNull(Urls.alternateLiveContainer("http://cdn.example/channel/index.m3u8"))
    }
}
