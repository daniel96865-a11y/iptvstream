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

    /** Sicheres DNS: off, cloudflare, quad9, google oder custom (siehe [SecureDns]). */
    var secureDns: String
        get() = sp.getString("secure_dns", SecureDns.OFF) ?: SecureDns.OFF
        set(v) = sp.edit().putString("secure_dns", v).apply()

    /** Eigene DoH-Adresse (https://…/dns-query) für „Eigener Anbieter“. */
    var secureDnsUrl: String
        get() = sp.getString("secure_dns_url", "") ?: ""
        set(v) = sp.edit().putString("secure_dns_url", v).apply()

    var lastUrl: String
        get() = sp.getString("last_url", "") ?: ""
        set(v) = sp.edit().putString("last_url", v).apply()

    companion object {
        const val FILE = "browser"
    }
}
