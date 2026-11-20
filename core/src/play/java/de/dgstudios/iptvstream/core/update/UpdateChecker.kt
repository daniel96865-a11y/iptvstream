package de.dgstudios.iptvstream.core.update

import android.content.Context
import androidx.compose.runtime.Composable
import kotlinx.coroutines.CoroutineScope
import okhttp3.OkHttpClient

/**
 * Google-Play-Ausgabe: Updates kommen ausschließlich über Google Play.
 * Kein Update-Feed, kein Herunterladen oder Installieren von APKs (Richtlinie „Geräte- und Netzwerkmissbrauch“).
 * Gleiche Schnittstelle wie die GitHub-Ausgabe, damit Handy- und TV-Code unverändert bleiben.
 */
@Suppress("UNUSED_PARAMETER")
class UpdateChecker(context: Context, http: OkHttpClient, scope: CoroutineScope) {
    fun maybeCheck() = Unit
    fun onStartScreenVisible() = Unit
    fun onStartScreenHidden() = Unit
    fun onStartScreenResume() = Unit
    fun onAppBackground() = Unit
    fun checkNow() = Unit
}

/** Kein Update-Dialog in der Play-Ausgabe. */
@Suppress("UNUSED_PARAMETER")
@Composable
fun UpdateHost(checker: UpdateChecker, television: Boolean = false) = Unit
