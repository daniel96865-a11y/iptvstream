package de.dgstudios.iptvstream.core.update

import org.junit.Assert.assertEquals
import org.junit.Test

class WhatsNewTest {
    @Test
    fun parsesBulletsAndSkipsBlankLines() {
        assertEquals(listOf("Eins", "Zwei"), WhatsNew.parse("• Eins\n\n- Zwei\n"))
    }
}
