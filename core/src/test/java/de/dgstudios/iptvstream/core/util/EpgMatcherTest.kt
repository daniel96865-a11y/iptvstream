package de.dgstudios.iptvstream.core.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EpgMatcherTest {
    @Test
    fun countryFromPrefixAndSuffix() {
        assertEquals("de", EpgMatcher.countryOf("[DE] Disney Channel HD"))
        assertEquals("de", EpgMatcher.countryOf("DE: RTL"))
        assertEquals("de", EpgMatcher.countryOf("|DE| ZDF"))
        assertEquals("us", EpgMatcher.countryOf("disney.channel.us"))
        assertNull(EpgMatcher.countryOf("Disney Channel"))
    }

    @Test
    fun foreignGuideEntryIsNotUsedForGermanChannel() {
        val byNorm = mapOf("disneychannel" to listOf("disney.channel.us"))
        assertNull(EpgMatcher.match("", "[DE] Disney Channel HD", byNorm))
    }

    @Test
    fun matchingCountryWinsOverOtherCountries() {
        val byNorm = mapOf("disneychannel" to listOf("disney.channel.us", "disney.channel.de"))
        assertEquals("disney.channel.de", EpgMatcher.match("", "[DE] Disney Channel HD", byNorm))
        assertEquals("disney.channel.us", EpgMatcher.match("", "[US] Disney Channel", byNorm))
    }

    @Test
    fun channelWithoutCountryTakesAnyEntry() {
        val byNorm = mapOf("zdf" to listOf("zdf.de"))
        assertEquals("zdf.de", EpgMatcher.match("", "ZDF HD", byNorm))
    }

    @Test
    fun germanGuideMatchesByNameAndAlias() {
        val ger = mapOf(
            "fixandfoxi" to "ger:fix.&.foxi.de",
            "rtlzwei" to "ger:rtlzwei.de",
            "daserste" to "ger:das.erste.de",
        )
        assertEquals("ger:fix.&.foxi.de", GermanGuide.match("", "[DE] FIX & FOX HD", ger))
        assertEquals("ger:rtlzwei.de", GermanGuide.match("", "RTL2 HD", ger))
        assertEquals("ger:das.erste.de", GermanGuide.match("", "DE: Das Erste HD", ger))
        assertNull(GermanGuide.match("", "Unbekannter Sender", ger))
    }

    @Test
    fun germanGuideEligibility() {
        assertTrue(GermanGuide.eligible("de", GermanGuide.PREFER))
        assertFalse(GermanGuide.eligible(null, GermanGuide.PREFER))
        assertTrue(GermanGuide.eligible(null, GermanGuide.FALLBACK))
        assertFalse(GermanGuide.eligible("us", GermanGuide.FALLBACK))
        assertFalse(GermanGuide.eligible("de", GermanGuide.OFF))
    }
}
