package de.dgstudios.iptvstream.core.settings

import de.dgstudios.iptvstream.core.data.remote.Net
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GithubVariantSettingsTest {
    private fun choice(id: String) =
        SettingsSchema.sections.flatMap { it.items }.filterIsInstance<ChoiceDef>().first { it.id == id }

    @Test
    fun smartersOptionStaysInGithubBuild() {
        assertTrue(choice("user_agent").options.any { it.id == "smarters" })
        assertEquals("IPTVSmartersPro", Net.userAgentFor("smarters"))
    }

    @Test
    fun germanEpgFallbackByDefault() {
        assertEquals("fallback", SettingsSchema.defaultOf("epg_de"))
    }
}
