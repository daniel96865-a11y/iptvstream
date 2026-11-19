package de.dgstudios.iptvstream.core.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class FilterEngineTest {
    private val sample = FilterEngine.parse(
        listOf(
            """
            [Adblock Plus 2.0]
            ! Kommentar
            ||ads.example.com^
            ||tracker.example^${'$'}third-party
            ||popads.example^${'$'}popup
            /banner/*/ad_
            &adtype=video
            |https://cdn.example.org/ads.js|
            @@||good.ads.example.com^
            ||site.example^${'$'}domain=other.example
            ##.ad-box
            news.example##.sponsor
            """.trimIndent(),
        ),
    )

    @Test
    fun blocksHostRulesIncludingSubdomains() {
        assertTrue(sample.shouldBlock("https://ads.example.com/x.js", "news.example"))
        assertTrue(sample.shouldBlock("https://img.ads.example.com/x.png", "news.example"))
        assertFalse(sample.shouldBlock("https://example.com/ads", "news.example"))
    }

    @Test
    fun exceptionWins() {
        assertFalse(sample.shouldBlock("https://good.ads.example.com/x.js", "news.example"))
    }

    @Test
    fun thirdPartyOnlyRule() {
        assertTrue(sample.shouldBlock("https://tracker.example/p.gif", "news.example"))
        assertFalse(sample.shouldBlock("https://tracker.example/p.gif", "www.tracker.example"))
    }

    @Test
    fun patternRules() {
        assertTrue(sample.shouldBlock("https://x.org/banner/123/ad_top.png", "news.example"))
        assertTrue(sample.shouldBlock("https://x.org/v?id=1&adtype=video", "news.example"))
        assertTrue(sample.shouldBlock("https://cdn.example.org/ads.js", "news.example"))
        assertFalse(sample.shouldBlock("https://cdn.example.org/ads.js?v=2", "news.example"))
        assertFalse(sample.shouldBlock("https://x.org/bannerx/ad_top.png", "news.example"))
    }

    @Test
    fun domainRestrictedRulesAreSkipped() {
        assertFalse(sample.shouldBlock("https://site.example/a.js", "news.example"))
    }

    @Test
    fun popupRules() {
        assertTrue(sample.shouldBlockPopup("https://popads.example/go", "news.example"))
        assertFalse(sample.shouldBlock("https://popads.example/go", "news.example"))
        assertTrue(sample.shouldBlockPopup("https://ads.example.com/landing", "news.example"))
        assertFalse(sample.shouldBlockPopup("https://wikipedia.org/", "news.example"))
    }

    @Test
    fun hideCss() {
        val css = sample.hideCss("www.news.example")
        assertTrue(css.contains(".ad-box"))
        assertTrue(css.contains(".sponsor"))
        assertFalse(sample.hideCss("other.example").contains(".sponsor"))
    }

    @Test
    fun hostAndSite() {
        assertEquals("a.b.example.com", FilterEngine.hostOf("https://user@a.b.example.com:8080/x?y"))
        assertEquals("example.co.uk", FilterEngine.siteOf("www.example.co.uk"))
        assertEquals("example.de", FilterEngine.siteOf("cdn.example.de"))
    }

    @Test
    fun realListsBlockKnownAdsAndKeepNormalPages() {
        val dir = File("src/main/assets/adblock")
        val texts = listOf("easylist.txt", "easylistgermany.txt", "peterlowe.txt").map { File(dir, it).readText() }
        val t0 = System.nanoTime()
        val e = FilterEngine.parse(texts)
        val parseMs = (System.nanoTime() - t0) / 1_000_000
        println("Regeln: ${e.ruleCount} (Hosts ${e.hostRuleCount}, Muster ${e.patternRuleCount}, ohne Token ${e.untokenizedCount}, CSS ${e.hideRuleCount}), ${parseMs} ms")
        assertTrue(e.hostRuleCount > 20_000)
        assertTrue(e.shouldBlock("https://securepubads.g.doubleclick.net/tag/js/gpt.js", "www.spiegel.de"))
        assertTrue(e.shouldBlock("https://pagead2.googlesyndication.com/pagead/js/adsbygoogle.js", "example.org"))
        assertFalse(e.shouldBlock("https://www.wikipedia.org/static/logo.png", "www.wikipedia.org"))
        assertFalse(e.shouldBlock("https://duckduckgo.com/dist/app.js", "duckduckgo.com"))
        assertFalse(e.shouldBlock("https://www.tagesschau.de/resources/assets/js/main.js", "www.tagesschau.de"))
        // http und LAN werden nicht pauschal blockiert (weder Navigation noch Inhalte)
        for (u in listOf("http://neverssl.com/", "http://192.168.1.1/", "http://192.168.178.20:8080/web/index.html", "http://fritz.box/", "http://example.com:8080/")) {
            assertFalse(u, e.shouldBlockPopup(u, "duckduckgo.com"))
            assertFalse(u, e.shouldBlockPopup(u, null))
        }
        assertFalse(e.shouldBlock("http://192.168.1.10:8080/live/stream.m3u8", "example.org"))
        assertFalse(e.shouldBlock("http://neverssl.com/style.css", "neverssl.com"))
        // Geschwindigkeit: 2000 Prüfungen deutlich unter 1 s
        val urls = listOf(
            "https://www.tagesschau.de/multimedia/video/index.html?x=1",
            "https://cdn.jsdelivr.net/npm/jquery@3/dist/jquery.min.js",
            "https://fonts.gstatic.com/s/roboto/v30/abc.woff2",
            "https://ad.doubleclick.net/ddm/trackclk/N123",
        )
        e.shouldBlock(urls[0], "a.de")
        val t1 = System.nanoTime()
        repeat(500) { for (u in urls) e.shouldBlock(u, "www.tagesschau.de") }
        val checkMs = (System.nanoTime() - t1) / 1_000_000
        println("2000 Prüfungen: $checkMs ms")
        assertTrue(checkMs < 2_000)
    }
}

class BrowserUrlTest {
    @Test
    fun resolvesAddressesAndSearches() {
        assertEquals("https://example.org", BrowserUrl.resolve("example.org"))
        assertEquals("https://example.org/a?b=1", BrowserUrl.resolve("example.org/a?b=1"))
        assertEquals("https://x.de/", BrowserUrl.resolve(" https://x.de/ "))
        assertEquals("https://duckduckgo.com/?q=wetter+morgen", BrowserUrl.resolve("wetter morgen"))
        assertTrue(BrowserUrl.resolve("javascript:alert(1)")!!.startsWith(BrowserUrl.SEARCH))
        assertEquals(null, BrowserUrl.resolve("file:///sdcard/x"))
        assertEquals(null, BrowserUrl.resolve("   "))
    }

    @Test
    fun explicitHttpIsNeverRewritten() {
        assertEquals(BrowserUrl.Target("http://example.com/x"), BrowserUrl.target("http://example.com/x"))
        assertEquals(BrowserUrl.Target("HTTP://Example.com"), BrowserUrl.target("HTTP://Example.com"))
        assertEquals(BrowserUrl.Target("http://192.168.1.20:8080/web/"), BrowserUrl.target("http://192.168.1.20:8080/web/"))
    }

    @Test
    fun lanAddressesOpenWithHttp() {
        assertEquals(BrowserUrl.Target("http://192.168.1.1"), BrowserUrl.target("192.168.1.1"))
        assertEquals(BrowserUrl.Target("http://192.168.178.10:8080/status"), BrowserUrl.target("192.168.178.10:8080/status"))
        assertEquals(BrowserUrl.Target("http://localhost:3000"), BrowserUrl.target("localhost:3000"))
        assertEquals(BrowserUrl.Target("http://nas:5000"), BrowserUrl.target("nas:5000"))
        assertEquals(BrowserUrl.Target("http://fritz.box"), BrowserUrl.target("fritz.box"))
        assertEquals(BrowserUrl.Target("http://drucker.local/"), BrowserUrl.target("drucker.local/"))
        assertEquals(BrowserUrl.Target("http://[fe80::1]:8080"), BrowserUrl.target("[fe80::1]:8080"))
    }

    @Test
    fun domainsTryHttpsThenHttp() {
        assertEquals(BrowserUrl.Target("https://example.com:8080", "http://example.com:8080"), BrowserUrl.target("example.com:8080"))
        assertEquals(BrowserUrl.Target("https://neverssl.com", "http://neverssl.com"), BrowserUrl.target("neverssl.com"))
    }

    @Test
    fun sameUrl() {
        assertTrue(BrowserUrl.same("https://a.de/", "HTTPS://a.de"))
        assertFalse(BrowserUrl.same("https://a.de", "http://a.de"))
    }
}

class CursorNavTest {
    @Test
    fun movesAndAccelerates() {
        val s = CursorNav.move(100f, 300f, 1, 0, 20f, 1000f, 600f, 40f, pageAtTop = true)
        assertEquals(CursorNav.Kind.MOVE, s.kind)
        assertEquals(120f, s.x)
        assertTrue(CursorNav.stepPx(10f, 10) > CursorNav.stepPx(10f, 0))
    }

    @Test
    fun scrollsAtEdges() {
        val down = CursorNav.move(100f, 570f, 0, 1, 20f, 1000f, 600f, 40f, pageAtTop = true)
        assertEquals(CursorNav.Kind.SCROLL, down.kind)
        assertTrue(down.scrollDy > 0)
        val up = CursorNav.move(100f, 45f, 0, -1, 20f, 1000f, 600f, 40f, pageAtTop = false)
        assertEquals(CursorNav.Kind.SCROLL, up.kind)
        assertTrue(up.scrollDy < 0)
    }

    @Test
    fun topOfPageGoesToToolbar() {
        val s = CursorNav.move(100f, 30f, 0, -1, 20f, 1000f, 600f, 40f, pageAtTop = true)
        assertEquals(CursorNav.Kind.TO_TOOLBAR, s.kind)
    }
}
