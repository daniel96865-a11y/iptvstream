package de.dgstudios.iptvstream.core.update

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

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
