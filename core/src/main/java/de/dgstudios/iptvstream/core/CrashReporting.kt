package de.dgstudios.iptvstream.core

import android.app.Application
import io.sentry.Breadcrumb
import io.sentry.SentryEvent
import io.sentry.android.core.SentryAndroid

/**
 * Fehler- und Absturzmeldungen an Sentry. Nur aktiv, wenn beim Bauen ein DSN gesetzt war.
 * Keine personenbezogenen Daten, keine Screenshots/Replays, kaum Performance-Daten.
 * Zugangsdaten in Stream-/API-Adressen (Xtream: /live/<user>/<pass>/…, ?username=&password=)
 * werden vor dem Senden entfernt.
 */
internal object CrashReporting {
    fun init(app: Application) {
        val dsn = BuildConfig.SENTRY_DSN
        if (dsn.isBlank()) return
        runCatching {
            SentryAndroid.init(app) { o ->
                o.dsn = dsn
                o.environment = if (BuildConfig.DEBUG) "debug" else "production"
                // Keine personenbezogenen Daten: sendDefaultPii ist seit Sentry 8.59 veraltet und ab Werk aus.
                // dataCollection bewusst NICHT setzen – sonst würden dessen (großzügigere) Standards gelten.
                o.tracesSampleRate = 0.01
                o.isAttachScreenshot = false
                o.isAttachViewHierarchy = false
                o.isEnableUserInteractionBreadcrumbs = false
                o.isEnableNetworkEventBreadcrumbs = false
                o.setBeforeSend { event, _ -> scrub(event) }
                o.setBeforeBreadcrumb { crumb, _ -> scrub(crumb) }
            }
        }
    }

    private val queryCreds = Regex("(?i)\\b(username|password|user|pass|pwd|token|key)=([^&\\s\"']+)")
    private val pathCreds = Regex("(?i)/(live|movie|series|timeshift|xmltv\\.php|get\\.php)/([^/\\s?]+)/([^/\\s?]+)/")
    private val userInfo = Regex("://[^/@\\s]+@")

    internal fun clean(text: String?): String? = text
        ?.replace(queryCreds) { "${it.groupValues[1]}=[Filtered]" }
        ?.replace(pathCreds) { "/${it.groupValues[1]}/[Filtered]/[Filtered]/" }
        ?.replace(userInfo, "://[Filtered]@")

    private fun scrub(event: SentryEvent): SentryEvent {
        event.user = null
        event.message?.let { m ->
            m.message = clean(m.message)
            m.formatted = clean(m.formatted)
        }
        event.exceptions?.forEach { it.value = clean(it.value) }
        event.breadcrumbs?.forEach { scrub(it) }
        event.request = null
        return event
    }

    private fun scrub(crumb: Breadcrumb): Breadcrumb {
        crumb.message = clean(crumb.message)
        crumb.data.keys.toList().forEach { k ->
            val v = crumb.data[k]
            if (v is String) crumb.setData(k, clean(v) ?: "")
        }
        return crumb
    }
}
