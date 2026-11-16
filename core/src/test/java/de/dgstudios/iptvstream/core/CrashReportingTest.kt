package de.dgstudios.iptvstream.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class CrashReportingTest {
    @Test
    fun removesXtreamQueryCredentials() {
        val out = CrashReporting.clean("http://srv.tv:8080/player_api.php?username=max&password=geheim&action=get_live")!!
        assertFalse(out.contains("max"))
        assertFalse(out.contains("geheim"))
        assertEquals("http://srv.tv:8080/player_api.php?username=[Filtered]&password=[Filtered]&action=get_live", out)
    }

    @Test
    fun removesXtreamPathCredentials() {
        val out = CrashReporting.clean("Failed: http://srv.tv/live/max/geheim/123.ts")!!
        assertEquals("Failed: http://srv.tv/live/[Filtered]/[Filtered]/123.ts", out)
    }

    @Test
    fun removesUserInfo() {
        assertEquals("rtsp://[Filtered]@1.2.3.4/x", CrashReporting.clean("rtsp://a:b@1.2.3.4/x"))
    }
}
