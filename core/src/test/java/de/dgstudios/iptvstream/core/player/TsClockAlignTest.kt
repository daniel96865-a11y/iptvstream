package de.dgstudios.iptvstream.core.player

import de.dgstudios.iptvstream.core.player.LiveTsJoin.Companion.PTS_HZ
import de.dgstudios.iptvstream.core.player.LiveTsJoin.Companion.PTS_MASK
import de.dgstudios.iptvstream.core.player.TsOverlapSkipDataSource.Companion.TS_PACKET
import de.dgstudios.iptvstream.core.player.TsOverlapSkipDataSource.Companion.readPesPts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TsClockAlignTest {
    @Test fun timestamp5Roundtrips() {
        val buf = ByteArray(5)
        buf[0] = 0x20.toByte() // PTS-only prefix 0010
        val values = listOf(0L, 1L, 90_000L, 128_090L, 0x1FFFFFFFFL, 12L * PTS_HZ + 1234)
        for (v in values) {
            TsClockAlign.writeTimestamp5(buf, 0, v)
            assertEquals(v and PTS_MASK, TsClockAlign.readTimestamp5(buf, 0))
        }
    }

    @Test fun pcrRoundtrips() {
        val buf = ByteArray(6)
        val cases = listOf(0L to 0L, 1000L to 0L, 128_090L to 42L, PTS_MASK to 299L)
        for ((base, ext) in cases) {
            TsClockAlign.writePcr(buf, 0, base, ext)
            val (b, e) = TsClockAlign.readPcr(buf, 0)
            assertEquals("base $base", base, b)
            assertEquals("ext $ext", ext, e)
        }
    }

    @Test fun offsetPlacesNewPtsJustAfterLast() {
        val last = 617_690L
        val neu = 1_000L
        val off = TsClockAlign.offsetToContinue(last, neu)
        assertEquals((last + TsClockAlign.JOIN_GAP_PTS) and PTS_MASK, TsClockAlign.apply(neu, off))
    }

    @Test fun shiftAllRewritesPesPts() {
        val ts = javaClass.getResource("/live10s.ts")!!.readBytes()
        val first = readPesPts(ts.copyOfRange(3 * TS_PACKET, 4 * TS_PACKET))
        val off = 3L * 3600 * PTS_HZ
        val shifted = TsClockAlign.shiftAll(ts, off)
        val neu = readPesPts(shifted.copyOfRange(3 * TS_PACKET, 4 * TS_PACKET))
        assertEquals(TsClockAlign.apply(first, off), neu)
        assertTrue(first != neu)
    }
}
