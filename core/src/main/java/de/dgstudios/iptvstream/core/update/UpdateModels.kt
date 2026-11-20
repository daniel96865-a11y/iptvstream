package de.dgstudios.iptvstream.core.update

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

/** Beschreibt ein verfügbares Update. Bei Google Play sind Name, Adresse und Prüfsumme leer. */
data class UpdateInfo(
    val versionCode: Int,
    val versionName: String,
    val apkUrl: String,
    val sha256: String,
    val changelog: String,
)

sealed interface UpdatePrompt {
    data object Hidden : UpdatePrompt

    data class Offer(
        val info: UpdateInfo,
        val downloading: Boolean,
        val progress: Float?,
        val needPermission: Boolean,
        /** Nur Play-Variante: Update läuft über den Play Store (Hinweis statt In-App-Update, z. B. am TV). */
        val viaStore: Boolean = false,
    ) : UpdatePrompt

    /** Nur Play-Variante: Update ist geladen und wartet auf den Neustart der App. */
    data object ReadyToRestart : UpdatePrompt

    data class Message(val text: String) : UpdatePrompt
}

fun installedVersionName(context: Context): String = try {
    context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: ""
} catch (_: PackageManager.NameNotFoundException) {
    ""
}

fun installedVersionCode(context: Context): Long {
    val info = context.packageManager.getPackageInfo(context.packageName, 0)
    return if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else {
        @Suppress("DEPRECATION")
        info.versionCode.toLong()
    }
}

