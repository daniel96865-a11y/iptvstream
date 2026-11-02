package de.dgstudios.iptvstream.core.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.BufferedReader
import java.io.StringReader

class SyncSafetyTest {
    private fun r(s: String) = BufferedReader(StringReader(s))

    @Test
    fun htmlIsNotAPlaylist() {
        assertFalse(M3uParser.looksLikePlaylist(r("<!DOCTYPE html><html><body>Wartung</body></html>")))
        assertFalse(M3uParser.looksLikePlaylist(r("")))
        assertFalse(M3uParser.looksLikePlaylist(r("[]")))
        assertTrue(M3uParser.looksLikePlaylist(r("\uFEFF\n#EXTM3U\n#EXTINF:-1,A\nhttp://x/1.ts")))
    }

    @Test
    fun markResetKeepsFirstLines() {
        val br = r("#EXTM3U\n#EXTINF:-1 group-title=\"G\",Sender A\nhttp://example.com/1.ts\n")
        br.mark(256 * 1024)
        assertTrue(M3uParser.looksLikePlaylist(br))
        br.reset()
        val list = M3uParser().entries(br).toList()
        assertEquals(1, list.size)
        assertEquals("Sender A", list[0].name)
    }

    @Test
    fun onlyHttpUrlsCount() {
        assertNull(Urls.httpUrl("file:///sdcard/x.ts"))
        assertNull(Urls.httpUrl("<a href=x>"))
        assertEquals("http://example.com/1.ts", Urls.httpUrl("http://example.com/1.ts"))
    }

    @Test
    fun likeEscape() {
        assertEquals("100\\%", SqlLike.escape("100%"))
        assertEquals("a\\_b", SqlLike.escape("a_b"))
        assertEquals("c\\\\d", SqlLike.escape("c\\d"))
        assertEquals("ARD", SqlLike.escape("ARD"))
    }
}
