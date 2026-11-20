package de.dgstudios.iptvstream.core.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlaybackUrlTest {
    @Test
    fun onlyHttpAndHttpsPlay() {
        assertEquals("http://192.168.1.2/live/a.ts", Urls.httpUrl("http://192.168.1.2/live/a.ts"))
        assertEquals("https://cdn.example/a.m3u8", Urls.httpUrl(" https://cdn.example/a.m3u8 "))
        assertNull(Urls.httpUrl("file:///sdcard/film.mp4"))
        assertNull(Urls.httpUrl("content://media/1"))
        assertNull(Urls.httpUrl("javascript:alert(1)"))
        assertNull(Urls.httpUrl(""))
        assertNull(Urls.httpUrl(null))
    }
}
