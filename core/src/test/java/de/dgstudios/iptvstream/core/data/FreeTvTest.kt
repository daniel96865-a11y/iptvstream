package de.dgstudios.iptvstream.core.data

import de.dgstudios.iptvstream.core.util.M3uParser
import de.dgstudios.iptvstream.core.util.StreamHeaders
import de.dgstudios.iptvstream.core.util.Urls
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.BufferedReader
import java.io.StringReader
import java.net.URL

class FreeTvTest {
    private val ita = """
        #EXTM3U
        #EXTINF:-1 tvg-id="Rai1.it@SD" tvg-logo="https://x/rai1.png" group-title="Undefined",Rai 1 (1080p)
        https://rai.example/1.m3u8
        #EXTINF:-1 tvg-id="Rai2.it@SD" http-user-agent="Mozilla/5.0 Test" group-title="Undefined",Rai 2 (1080p)
        https://rai.example/2.m3u8
        #EXTINF:-1 tvg-id="RaiNews24.it@SD" group-title="News",Rai News 24 (720p)
        #EXTVLCOPT:http-referrer=https://www.raiplay.it/
        https://rai.example/news.m3u8
        #EXTINF:-1 tvg-id="RaiScuola.it@SD" group-title="Education",Rai Scuola (576p) [Geo-blocked]
        https://rai.example/scuola.m3u8
        #EXTINF:-1 tvg-id="X.it@SD" group-title="Movies;Series",Film Kanal
        https://film.example/live.m3u8
    """.trimIndent()

    private val deu = """
        #EXTM3U
        #EXTINF:-1 tvg-id="3sat.de@SD" group-title="General",3sat
        https://3sat.example/a.m3u8
        #EXTINF:-1 tvg-id="Rai1.it@SD" group-title="General",Doppelt
        https://rai.example/1.m3u8
    """.trimIndent()

    private fun parse(text: String) = M3uParser().entries(BufferedReader(StringReader(text))).toList()

    @Test
    fun parserReadsUserAgentAndReferrer() {
        val e = parse(ita)
        assertEquals("Mozilla/5.0 Test", e[1].userAgent)
        assertEquals("https://www.raiplay.it/", e[2].referrer)
        assertEquals("", e[0].userAgent)
        assertEquals("", e[3].referrer) // Option gilt nur für den eigenen Sender
    }

    @Test
    fun mergeGroupsByLanguageAndCategoryAndDeduplicates() {
        val out = FreeTv.merge(listOf("deu" to parse(deu), "ita" to parse(ita)), hideGeo = false)
        // Rai 1 steht in beiden Listen, zählt aber nur einmal und bleibt bei der ersten Sprache.
        assertEquals(1, out.count { it.entry.url == "https://rai.example/1.m3u8" })
        assertEquals("Deutsch · Allgemein", out.first { it.entry.name == "3sat" }.group)
        assertEquals("Italienisch · Nachrichten", out.first { it.entry.name.startsWith("Rai News") }.group)
        assertEquals("Italienisch · Filme / Serien", out.first { it.entry.name == "Film Kanal" }.group)
        assertEquals("Deutsch · Sonstige", FreeTv.categoryLabel("Undefined").let { "Deutsch · $it" })
        // Deutsch kommt vor Italienisch (Reihenfolge der Auswahl).
        assertEquals("3sat", out.first().entry.name)
    }

    @Test
    fun singleLanguageHasNoLanguagePrefix() {
        val out = FreeTv.merge(listOf("ita" to parse(ita)), hideGeo = false)
        assertEquals("Nachrichten", out.first { it.entry.name.startsWith("Rai News") }.group)
    }

    @Test
    fun geoBlockedCanBeHidden() {
        val shown = FreeTv.merge(listOf("ita" to parse(ita)), hideGeo = false)
        val hidden = FreeTv.merge(listOf("ita" to parse(ita)), hideGeo = true)
        assertTrue(shown.any { it.entry.name.contains("Scuola") })
        assertFalse(hidden.any { it.entry.name.contains("Scuola") })
    }

    @Test
    fun headersTravelAsFragmentAndAreStrippedForPlayback() {
        val out = FreeTv.merge(listOf("ita" to parse(ita)), hideGeo = false)
        val news = out.first { it.entry.name.startsWith("Rai News") }
        val parsed = StreamHeaders.parse(news.playUrl)
        assertEquals("https://rai.example/news.m3u8", parsed.url)
        assertEquals("https://www.raiplay.it/", parsed.referrer)
        assertNull(parsed.userAgent)
        // Ohne Kennung bleibt die URL unverändert.
        assertEquals("https://rai.example/1.m3u8", out.first { it.entry.name.startsWith("Rai 1") }.playUrl)
        // HLS wird auch mit Fragment erkannt, und die URL bleibt eine gültige http-URL.
        assertTrue(Urls.isHls(news.playUrl))
        assertTrue(Urls.httpUrl(news.playUrl) != null)
    }

    @Test
    fun configRoundTrip() {
        assertEquals("deu,ita,eng", FreeTv.encodeLanguages(listOf("deu", "ITA", "eng", "deu", "x1", "toolong")))
        val c = FreeTv.decode("deu,ita", FreeTv.NO_GEO)
        assertEquals(listOf("deu", "ita"), c.languages)
        assertTrue(c.hideGeo)
        assertFalse(FreeTv.decode("deu", "").hideGeo)
    }

    @Test
    fun languageCatalogCoversDeutschAndItalienisch() {
        assertTrue(FreeLanguages.ALL.size > 150)
        assertEquals("Deutsch", FreeLanguages.find("deu")?.english?.let { "Deutsch" })
        assertEquals(listOf("deu", "ita"), FreeLanguages.FOR_PICKER.take(2).map { it.code })
        assertEquals(FreeLanguages.ALL.size, FreeLanguages.ALL.map { it.code }.toSet().size)
    }

    /** Echte Listen von iptv-org laden. Nur mit IPTV_LIVE=1 (braucht Internet). */
    @Test
    fun liveListsLoadAndContainRai() {
        assumeTrue(System.getenv("IPTV_LIVE") == "1")
        fun load(code: String) = URL(FreeTv.listUrl(code)).openStream().bufferedReader().use { br ->
            M3uParser().entries(br).filter { Urls.httpUrl(it.url) != null }.toList()
        }
        val d = load("deu")
        val i = load("ita")
        val rai = i.filter { it.name.startsWith("Rai", ignoreCase = true) }
        println("LIVE deu=${d.size} ita=${i.size} rai=${rai.size}")
        println("LIVE rai: " + rai.joinToString { it.name })
        val merged = FreeTv.merge(listOf("deu" to d, "ita" to i), hideGeo = false)
        println("LIVE merged=${merged.size} groups=${merged.map { it.group }.toSet().size} withHeaders=${merged.count { '#' in it.playUrl }}")
        assertTrue(d.size > 200)
        assertTrue(i.size > 200)
        assertTrue(rai.any { it.name.startsWith("Rai 1") })
        assertTrue(rai.any { it.name.startsWith("Rai News") })
    }

    @Test
    fun epgPlanForDeutschAndItalienisch() {
        val plan = FreeTv.epgPlan(listOf("deu", "ita"))
        assertTrue(plan.wantGermanGuide)
        // Deutsch: AT/CH für österreichische und Schweizer Sender, dazu IT.
        assertEquals(3, plan.providerUrls.size)
        assertTrue(plan.providerUrls.any { it.contains("IT1") })
        val onlyDe = FreeTv.epgPlan(listOf("deu"))
        assertTrue(onlyDe.wantGermanGuide)
        assertEquals(FreeTv.GERMAN_EXTRA_EPG, onlyDe.providerUrls)
        val onlyIt = FreeTv.epgPlan(listOf("ita"))
        assertFalse(onlyIt.wantGermanGuide)
        assertTrue(onlyIt.providerUrls[0].contains("IT1"))
    }

    @Test
    fun tvDefaultIsGermanOnlyPhoneKeepsItalian() {
        assertEquals("deu", FreeTv.defaultLanguages(isTv = true))
        assertEquals("deu,ita", FreeTv.defaultLanguages(isTv = false))
        assertTrue(FreeTv.isTvPackage("de.dgstudios.iptvstream.tv"))
        assertFalse(FreeTv.isTvPackage("de.dgstudios.iptvstream"))
    }

    @Test
    fun tvMigrationOnlyTouchesOldDefault() {
        assertEquals("deu", FreeTv.tvMigration("deu,ita"))
        assertEquals(null, FreeTv.tvMigration("deu"))
        assertEquals(null, FreeTv.tvMigration("ita"))
        assertEquals(null, FreeTv.tvMigration("deu,ita,eng"))
        assertEquals(null, FreeTv.tvMigration("ita,deu"))
    }
}
