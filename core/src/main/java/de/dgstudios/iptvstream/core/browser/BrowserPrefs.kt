package de.dgstudios.iptvstream.core.browser

import android.content.Context

/** Einstellungen des eingebauten Browsers. */
class BrowserPrefs(ctx: Context) {
    private val sp = ctx.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    var adblock: Boolean
        get() = sp.getBoolean("adblock", true)
        set(v) = sp.edit().putBoolean("adblock", v).apply()

    /** Angetipptes Video sofort im Vollbild. */
    var autoFullscreen: Boolean
        get() = sp.getBoolean("auto_fullscreen", true)
        set(v) = sp.edit().putBoolean("auto_fullscreen", v).apply()

    var lastUrl: String
        get() = sp.getString("last_url", "") ?: ""
        set(v) = sp.edit().putString("last_url", v).apply()

    companion object {
        const val FILE = "browser"
    }
}
