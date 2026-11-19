package de.dgstudios.iptvstream.core.browser

import android.webkit.WebSettings
import android.webkit.WebView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BrowserWebSettingsTest {
    @Test
    fun allowsHttpContentOnHttpsPagesAndKeepsSafeDefaults() {
        val web = WebView(RuntimeEnvironment.getApplication())
        BrowserWebSettings.apply(web)
        val s = web.settings
        assertEquals(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW, s.mixedContentMode)
        assertTrue(s.javaScriptEnabled)
        assertTrue(s.domStorageEnabled)
        assertFalse(s.mediaPlaybackRequiresUserGesture)
        assertFalse(s.javaScriptCanOpenWindowsAutomatically)
        assertTrue(s.supportMultipleWindows())
        assertFalse(s.allowFileAccess)
        assertFalse(s.allowContentAccess)
    }
}
