package de.dgstudios.iptvstream.core.browser

import android.webkit.WebView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BrowserSessionTest {
    private val app get() = RuntimeEnvironment.getApplication()

    @Before
    fun reset() {
        BrowserSession.clear(app)
        BrowserHolder.discard()
    }

    @Test
    fun remembersLastPageAcrossRestart() {
        val w = WebView(app)
        w.loadUrl("http://192.168.1.20:8080/web/")
        BrowserSession.save(app, w)
        assertTrue(BrowserSession.hasSaved(app))
        assertEquals("http://192.168.1.20:8080/web/", BrowserPrefs(app).lastUrl)

        // „App-Neustart“: neue WebView öffnet dieselbe Seite
        val again = WebView(app)
        assertTrue(BrowserSession.restore(app, again))
        assertEquals("http://192.168.1.20:8080/web/", again.url)
    }

    @Test
    fun startPageAndClearForget() {
        val w = WebView(app)
        w.loadUrl("https://example.org/")
        BrowserSession.save(app, w)
        BrowserSession.clear(app)
        assertFalse(BrowserSession.hasSaved(app))
        assertFalse(BrowserSession.restore(app, WebView(app)))
    }

    @Test
    fun startPageIsNotSaved() {
        BrowserSession.save(app, WebView(app))
        assertFalse(BrowserSession.hasSaved(app))
    }

    @Test
    fun webViewIsKeptBetweenOpenings() {
        val first = Any()
        val (a, reusedA) = BrowserHolder.obtain(app)
        assertFalse(reusedA)
        BrowserHolder.release(app)
        val (b, reusedB) = BrowserHolder.obtain(app)
        assertTrue(reusedB)
        assertSame(a, b)
        BrowserHolder.discard()
        val (c, reusedC) = BrowserHolder.obtain(app)
        assertFalse(reusedC)
        assertNotSame(a, c)
        assertTrue(first !== c)
    }
}
