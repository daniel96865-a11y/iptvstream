package de.dgstudios.iptvstream.core.settings

import de.dgstudios.iptvstream.core.data.remote.Net
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayVariantSettingsTest {
    private fun choice(id: String) =
        SettingsSchema.sections.flatMap { it.items }.filterIsInstance<ChoiceDef>().first { it.id == id }

    @Test
    fun smartersOptionIsHidden() {
        assertFalse(choice("user_agent").options.any { it.id == "smarters" })
        assertTrue(choice("user_agent").options.any { it.id == "chrome" })
    }

    @Test
    fun oldSmartersChoiceFallsBackToBrowserAgent() {
        assertEquals(Net.USER_AGENT, Net.userAgentFor("smarters"))
    }

    @Test
    fun germanEpgIsOffByDefault() {
        assertEquals("off", SettingsSchema.defaultOf("epg_de"))
        assertEquals("off", AppSettings().epgGerman)
    }
}
