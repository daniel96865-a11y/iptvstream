package de.dgstudios.iptvstream.core.player

import androidx.media3.common.C
import androidx.media3.datasource.ByteArrayDataSource
import androidx.media3.datasource.DataSpec
import android.net.Uri
import de.dgstudios.iptvstream.core.player.LiveTsJoin.Companion.UNSET
import de.dgstudios.iptvstream.core.player.LiveTsJoin.Companion.ptsAhead
import de.dgstudios.iptvstream.core.player.LiveTsJoin.Companion.ptsMs
import de.dgstudios.iptvstream.core.player.TsOverlapSkipDataSource.Companion.TS_PACKET
import de.dgstudios.iptvstream.core.player.TsOverlapSkipDataSource.Companion.TS_SYNC
import de.dgstudios.iptvstream.core.player.TsOverlapSkipDataSource.Companion.readPesPts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
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
        assertNoPtsRewind(join)
    }

    @Test fun deliveredPtsMustNotGoBackwardsAcrossAChain() {
        val half = ts.size / 2 / TS_PACKET * TS_PACKET
        val join = LiveTsJoin()
        readAll(ts.copyOf(half), join)
        join.arm()
        readAll(ts, join)
        assertTrue(join.joined())
        assertNoPtsRewind(join)
    }

    @Test fun replayWithoutSkipWouldRewindPts() {
        val half = ts.size / 2 / TS_PACKET * TS_PACKET
        val join = LiveTsJoin()
        readAll(ts.copyOf(half), join)
        // absichtlich nicht arm(): die zweite Lieferung beginnt vorn – der Test muss das erkennen.
        readAll(ts, join)
        assertTrue("ohne Überspringen muss ein PTS-Rücksprung erkennbar sein", hasPtsRewind(join))
    }

    @Test fun newDecoderWaitsForKeyframeAndReportsGap() {
        val half = ts.size / 2 / TS_PACKET * TS_PACKET
        val join = LiveTsJoin()
        readAll(ts.copyOf(half), join)
        val last = join.lastPts()
        join.arm(sameStream = false)
        val src = TsOverlapSkipDataSource(ByteArrayDataSource(ts), join)
        src.open(DataSpec(Uri.EMPTY))
        drain(src)
        src.close()
        assertTrue(join.joined())
        val gap = join.keyframeGapMs()
        assertTrue(
            "neuer Decoder wartet aufs Schlüsselbild (Lücke $gap ms, typisch 0,2–1 s bei 1-s-GOP)",
            gap in 200L..1_100L,
        )
        assertTrue(src.firstDeliveredPts != UNSET && ptsAhead(src.firstDeliveredPts, last) >= 0)
        assertNoPtsRewind(join)
    }

    @Test fun sameStreamDoesNotWaitForNextKeyframe() {
        val half = ts.size / 2 / TS_PACKET * TS_PACKET
        val join = LiveTsJoin()
        readAll(ts.copyOf(half), join)
        val last = join.lastPts()
        join.arm(sameStream = true)
        val src = TsOverlapSkipDataSource(ByteArrayDataSource(ts), join)
        src.open(DataSpec(Uri.EMPTY))
        drain(src)
        src.close()
        assertTrue(join.joined())
        assertEquals(0L, join.keyframeGapMs())
        val gap = ptsMs(ptsAhead(src.firstDeliveredPts, last))
        assertTrue(
            "im selben Strom sofort nach der letzten PTS weiter (Lücke $gap ms, nicht bis zum nächsten Schlüsselbild)",
            src.firstDeliveredPts != UNSET && gap in 0L..200L,
        )
        assertNoPtsRewind(join)
    }


    @Test fun droppedOverlapDoesNotMoveLastPts() {
        val half = ts.size / 2 / TS_PACKET * TS_PACKET
        val join = LiveTsJoin()
        readAll(ts.copyOf(half), join)
        val last = join.lastPts()
        // Nur der frühe Vorlauf (deutlich vor dem alten Ende): darf lastPts nicht zurücksetzen.
        val head = ts.copyOf((ts.size / 10) / TS_PACKET * TS_PACKET)
        join.arm(sameStream = true)
        val src = TsOverlapSkipDataSource(ByteArrayDataSource(head), join)
        src.open(DataSpec(Uri.EMPTY))
        drain(src)
        src.close()
        assertEquals("verworfener Vorlauf darf die gemerkte PTS nicht verschieben", last, join.lastPts())
        assertEquals(UNSET, src.firstDeliveredPts)
    }

    @Test fun sameStreamMismatchAbortsWithoutDelivering() {
        val join = LiveTsJoin()
        readAll(ts, join)
        join.shiftLastPts(3L * 3600 * 90_000)
        val shifted = join.lastPts()
        join.arm(sameStream = true)
        val src = TsOverlapSkipDataSource(ByteArrayDataSource(ts), join)
        src.open(DataSpec(Uri.EMPTY))
        try {
            drain(src)
            fail("bei anderer Zeitachse im selben Extraktor muss TsJoinMismatchException fliegen")
        } catch (_: TsJoinMismatchException) {
            assertTrue(join.mismatch())
            assertEquals(UNSET, src.firstDeliveredPts)
            assertEquals("fremde PTS dürfen lastPts nicht überschreiben", shifted, join.lastPts())
        } finally {
            src.close()
        }
    }

    @Test fun ac3PrivateStreamPtsIsRead() {
        val pts = 90_000L * 12 + 1234
        val pkt = pesPacket(streamId = 0xBD, pts = pts)
        assertEquals(pts, readPesPts(pkt))
    }

    @Test fun wrapPassesNonTsThroughUnchanged() {
        val mp4 = javaClass.getResource("/clip2s.mp4")!!.readBytes()
        val playlist = javaClass.getResource("/hls/index.m3u8")!!.readBytes()
        val mkv = javaClass.getResource("/clip2s.mkv")!!.readBytes()
        val join = LiveTsJoin()
        for ((name, bytes) in listOf("mp4" to mp4, "mkv" to mkv, "hls" to playlist)) {
            val pass = TsOverlapSkipDataSource.wrap(ByteArrayDataSource(bytes), join, liveTs = false)
            pass.open(DataSpec(Uri.EMPTY))
            val out = drainAny(pass)
            pass.close()
            assertEquals("$name muss unverändert durch den Filter-Schalter", bytes.toList(), out.toList())
        }
    }

    @Test fun skipperWouldDestroyNonTs() {
        val playlist = "#EXTM3U\n#EXT-X-TARGETDURATION:4\n".toByteArray()
        val join = LiveTsJoin()
        val src = TsOverlapSkipDataSource(ByteArrayDataSource(playlist), join)
        src.open(DataSpec(Uri.EMPTY))
        val out = drain(src)
        src.close()
        assertTrue("ohne Schalter würde der Filter die Playlist zerlegen", out.isEmpty() || !out.contentEquals(playlist))
    }

    @Test fun unrelatedPtsAreNotSkipped() {
        val join = LiveTsJoin()
        readAll(ts, join)
        // So tun, als käme die nächste Verbindung von einer ganz anderen Zeitachse
        val last = join.lastPts()
        join.shiftLastPts(3L * 3600 * 90_000)
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

    private fun drain(src: TsOverlapSkipDataSource): ByteArray = drainAny(src)

    private fun drainAny(src: androidx.media3.datasource.DataSource): ByteArray {
        val buf = ByteArray(16 * 1024)
        val out = java.io.ByteArrayOutputStream()
        while (true) {
            val n = src.read(buf, 0, buf.size)
            if (n == C.RESULT_END_OF_INPUT) break
            if (n > 0) out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    private fun pesPacket(streamId: Int, pts: Long, pid: Int = 0x110): ByteArray {
        val pkt = ByteArray(TS_PACKET) { 0xFF.toByte() }
        pkt[0] = TS_SYNC
        pkt[1] = (0x40 or ((pid shr 8) and 0x1F)).toByte()
        pkt[2] = (pid and 0xFF).toByte()
        pkt[3] = 0x10
        pkt[4] = 0; pkt[5] = 0; pkt[6] = 1
        pkt[7] = streamId.toByte()
        pkt[8] = 0; pkt[9] = 8
        pkt[10] = 0x80.toByte()
        pkt[11] = 0x80.toByte()
        pkt[12] = 5
        fun u(v: Long) = (v and 0xFF).toByte()
        pkt[13] = u(((pts shr 30) and 0x07L) shl 1 or 1)
        pkt[14] = u(pts shr 22)
        pkt[15] = u(((pts shr 15) and 0x7FL) shl 1 or 1)
        pkt[16] = u(pts shr 7)
        pkt[17] = u((pts and 0x7FL) shl 1 or 1)
        return pkt
    }

    private fun pid(data: ByteArray, i: Int): Int =
        ((data[i + 1].toInt() and 0x1F) shl 8) or (data[i + 2].toInt() and 0xFF)

    private fun visibleOutcome(join: LiveTsJoin): Int = when {
        join.joined() -> LiveTsJoin.JOINED
        join.mismatch() -> LiveTsJoin.MISMATCH
        join.shouldSeekToLiveEdge() -> LiveTsJoin.ARMED
        else -> LiveTsJoin.NONE
    }

    companion object {
        fun hasPtsRewind(join: LiveTsJoin): Boolean {
            val last = HashMap<Int, Long>()
            for ((pid, pts) in join.delivered()) {
                val prev = last[pid]
                if (prev != null && ptsAhead(pts, prev) < -LiveTsJoin.PTS_HZ / 10) return true
                last[pid] = pts
            }
            return false
        }

        fun assertNoPtsRewind(join: LiveTsJoin) {
            val last = HashMap<Int, Long>()
            for ((pid, pts) in join.delivered()) {
                val prev = last[pid]
                if (prev != null) {
                    val ahead = ptsAhead(pts, prev)
                    assertTrue(
                        "PTS springt zurück pid=$pid um ${ptsMs(-ahead)} ms (von $prev nach $pts)",
                        ahead >= -LiveTsJoin.PTS_HZ / 10,
                    )
                }
                last[pid] = pts
            }
            assertTrue("es müssen PTS angekommen sein", last.isNotEmpty())
        }
    }
}
