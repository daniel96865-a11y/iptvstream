package de.dgstudios.iptvstream.core.player

import androidx.media3.common.C
import androidx.media3.datasource.ByteArrayDataSource
import androidx.media3.datasource.DataSpec
import android.net.Uri
import de.dgstudios.iptvstream.core.player.LiveTsJoin.Companion.UNSET
import de.dgstudios.iptvstream.core.player.LiveTsJoin.Companion.ptsAhead
import de.dgstudios.iptvstream.core.player.LiveTsJoin.Companion.ptsMs
import de.dgstudios.iptvstream.core.player.TsOverlapSkipDataSource.Companion.TS_PACKET
import de.dgstudios.iptvstream.core.player.TsOverlapSkipDataSource.Companion.readPesPts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TsOverlapSkipDataSourceTest {
    private val ts = javaClass.getResource("/live10s.ts")!!.readBytes()

    @Test fun ptsMatchesTheFile() {
        val first = ts.copyOfRange(3 * TS_PACKET, 4 * TS_PACKET)
        assertEquals(128090L, readPesPts(first))
    }

    @Test fun ptsAheadHandlesWrap() {
        val mask = LiveTsJoin.PTS_MASK
        assertEquals(100L, ptsAhead(100, 0))
        assertEquals(-100L, ptsAhead(0, 100))
        assertEquals(10L, ptsAhead(9, mask - 0)) // 9 is ahead of wrap-1? 9 - (2^33-1)
        // last PTS and first after wrap
        assertEquals(5L, ptsAhead(4, mask))
        assertEquals(-5L, ptsAhead(mask - 4, 0))
    }

    @Test fun withoutArmTheWholeFileIsPassedThrough() {
        val join = LiveTsJoin()
        val out = readAll(ts, join)
        assertEquals(ts.size, out.size)
        assertEquals(ts.toList(), out.toList())
        assertEquals(LiveTsJoin.NONE, visibleOutcome(join))
    }

    @Test fun overlappingStartIsDroppedUntilPreviousEnd() {
        val half = ts.size / 2 / TS_PACKET * TS_PACKET
        val join = LiveTsJoin()
        readAll(ts.copyOf(half), join)
        val last = join.lastPts()
        assertTrue("erste Hälfte hat PTS", last != UNSET)
        join.arm()
        val src = TsOverlapSkipDataSource(ByteArrayDataSource(ts), join)
        src.open(DataSpec(Uri.EMPTY))
        val out = drain(src)
        src.close()
        assertTrue(join.joined())
        assertTrue("etwas übersprungen", src.skippedPackets > 10)
        assertTrue(
            "erster gelieferter Inhalt liegt am oder hinter dem alten Ende (first=${src.firstDeliveredPts}, last=$last, ${ptsMs(ptsAhead(src.firstDeliveredPts, last))} ms)",
            src.firstDeliveredPts != UNSET && ptsAhead(src.firstDeliveredPts, last) >= -90_000 / 10,
        )
        // Ausgabe kürzer als die ganze Datei, aber länger als ein kurzes Reststück
        assertTrue("Ausgabe ${out.size} nach Hälfte ${half}", out.size in (ts.size / 5)..(ts.size * 3 / 4))
        // Beginn der Ausgabe ist ein gültiges TS
        assertEquals(0x47, out[0].toInt() and 0xFF)
        assertTrue("PAT bleibt erhalten", (0 until out.size step TS_PACKET).any { pid(out, it) == 0 })
    }

    @Test fun unrelatedPtsAreNotSkipped() {
        val join = LiveTsJoin()
        readAll(ts, join)
        // So tun, als käme die nächste Verbindung von einer ganz anderen Zeitachse
        val last = join.lastPts()
        join.onPts(last + 3L * 3600 * 90_000)
        join.arm()
        val src = TsOverlapSkipDataSource(ByteArrayDataSource(ts), join)
        src.open(DataSpec(Uri.EMPTY))
        val out = drain(src)
        src.close()
        assertTrue(join.mismatch())
        assertEquals(ts.size, out.size)
        assertEquals(0, src.skippedPackets)
    }

    private fun readAll(bytes: ByteArray, join: LiveTsJoin): ByteArray {
        val src = TsOverlapSkipDataSource(ByteArrayDataSource(bytes), join)
        src.open(DataSpec(Uri.EMPTY))
        val out = drain(src)
        src.close()
        return out
    }

    private fun drain(src: TsOverlapSkipDataSource): ByteArray {
        val buf = ByteArray(16 * 1024)
        val out = java.io.ByteArrayOutputStream()
        while (true) {
            val n = src.read(buf, 0, buf.size)
            if (n == C.RESULT_END_OF_INPUT) break
            if (n > 0) out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    private fun pid(data: ByteArray, i: Int): Int =
        ((data[i + 1].toInt() and 0x1F) shl 8) or (data[i + 2].toInt() and 0xFF)

    private fun visibleOutcome(join: LiveTsJoin): Int = when {
        join.joined() -> LiveTsJoin.JOINED
        join.mismatch() -> LiveTsJoin.MISMATCH
        join.shouldSeekToLiveEdge() -> LiveTsJoin.ARMED
        else -> LiveTsJoin.NONE
    }
}
