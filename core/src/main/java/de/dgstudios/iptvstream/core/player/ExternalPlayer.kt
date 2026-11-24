package de.dgstudios.iptvstream.core.player

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri

/**
 * Öffnet den laufenden Stream in einem anderen Player (VLC, MX Player …) als Ausweg, wenn ein Sender
 * bei uns nicht läuft. Es wird nur die Adresse übergeben, die der Nutzer selbst eingetragen hat.
 */
object ExternalPlayer {
    /** True, wenn ein Player geöffnet wurde. */
    fun open(context: Context, url: String, title: String?, userAgent: String?): Boolean {
        val uri = runCatching { Uri.parse(url) }.getOrNull() ?: return false
        val view = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "video/*")
            // Von VLC und MX Player ausgewertet; andere Player ignorieren die Felder.
            if (!title.isNullOrBlank()) putExtra("title", title)
            if (!userAgent.isNullOrBlank()) putExtra("headers", arrayOf("User-Agent", userAgent))
        }
        val chooser = Intent.createChooser(view, "Öffnen mit …")
        if (context !is Activity) chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (view.resolveActivity(context.packageManager) == null) return false
        return try {
            context.startActivity(chooser)
            true
        } catch (_: ActivityNotFoundException) {
            false
        } catch (_: SecurityException) {
            false
        }
    }
}
