package de.dgstudios.iptvstream.core.data

import de.dgstudios.iptvstream.core.data.db.EpgEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EpgGuideTest {
    @Test
    fun currentProgrammeAndTheOneAfter() {
        val rows = listOf(
            row("a", 0, 100, "jetzt"),
            row("a", 100, 200, "danach"),
            row("a", 200, 300, "später"),
            row("b", 150, 250, "nur danach"),
        )
        val guide = EpgGuide.group(rows, listOf("a", "b", "c"), now = 50)
        assertEquals("jetzt", guide.getValue("a").now?.title)
        assertEquals("danach", guide.getValue("a").next?.title)
        assertNull(guide.getValue("b").now)
        assertEquals("nur danach", guide.getValue("b").next?.title)
        assertNull(guide.getValue("c").now)
        assertNull(guide.getValue("c").next)
    }

    @Test
    fun freshUntilTheRunningProgrammeEnds() {
        val running = NowNext(row("a", 0, 5_000, "jetzt"), null)
        assertEquals(5_000L, EpgGuide.freshUntil(running, now = 1_000))
        assertEquals(1_000L + EpgGuide.EMPTY_TTL_MS, EpgGuide.freshUntil(NowNext(null, null), now = 1_000))
    }

    @Test
    fun emptyGuideCountsAsNoData() {
        assertEquals(false, EpgGuide.hasData(NowNext(null, null)))
        assertEquals(true, EpgGuide.hasData(NowNext(null, row("a", 10, 20, "danach"))))
        assertEquals(true, EpgGuide.hasData(NowNext(row("a", 0, 20, "jetzt"), null)))
    }

    private fun row(key: String, start: Long, stop: Long, title: String) =
        EpgEntity(profileId = 1, channelKey = key, start = start, stop = stop, title = title, description = null)
}
