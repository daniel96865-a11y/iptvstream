package de.dgstudios.iptvstream.core.player

import de.dgstudios.iptvstream.core.data.PlayItem
import de.dgstudios.iptvstream.core.data.PlayKind
import org.junit.Assert.assertEquals
import org.junit.Test

class AudioBoostTest {
    @Test
    fun stepsStayInsideTheDeviceLimit() {
        assertEquals(0, AudioBoost.millibels(AudioBoost.OFF))
        assertEquals(0, AudioBoost.millibels("unbekannt"))
        assertEquals(700, AudioBoost.millibels(AudioBoost.LOW))
        assertEquals(1_200, AudioBoost.millibels(AudioBoost.MID))
        assertEquals(AudioBoost.MAX_MILLIBELS, AudioBoost.millibels(AudioBoost.HIGH))
    }

    @Test
    fun episodesOfOneSeriesShareTheLevel() {
        val first = item(PlayKind.EPISODE, "11", "7")
        val second = item(PlayKind.EPISODE, "12", "7")
        val other = item(PlayKind.EPISODE, "12", "8")
        assertEquals(AudioBoost.key(3, first), AudioBoost.key(3, second))
        assertEquals("audioboost_3_SERIES_7", AudioBoost.key(3, first))
        assertEquals("audioboost_3_SERIES_8", AudioBoost.key(3, other))
    }

    @Test
    fun liveChannelKeepsItsOwnLevel() {
        val channel = item(PlayKind.LIVE, "501", null)
        assertEquals("audioboost_3_LIVE_501", AudioBoost.key(3, channel))
    }

    private fun item(kind: PlayKind, id: String, parentId: String?) = PlayItem(
        kind = kind,
        id = id,
        title = "Test",
        url = "http://example.invalid/stream",
        parentId = parentId,
    )
}
