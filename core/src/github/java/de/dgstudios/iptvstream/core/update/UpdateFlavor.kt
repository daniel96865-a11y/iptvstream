package de.dgstudios.iptvstream.core.update

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.ContextWrapper
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext

/**
 * GitHub-Variante: öffnet den System-Installer für die geladene APK und die Einstellung
 * „Unbekannte Apps“. Liefert die Aktion für den Knopf „Erlauben“.
 */
@Composable
internal fun rememberFlavorUpdateActions(checker: UpdateChecker): () -> Unit {
    val context = LocalContext.current
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        checker.onPermissionReturned()
    }

    LaunchedEffect(checker) {
        checker.installs.collect { file ->
            val activity = context.findActivity()
            val intent = checker.installIntent(file)
            try {
                if (activity != null) activity.startActivity(intent) else context.startActivity(intent)
            } catch (e: ActivityNotFoundException) {
                checker.installUnavailable("kein Installer gefunden")
            } catch (e: SecurityException) {
                checker.installUnavailable("Zugriff verweigert")
            }
        }
    }

    return {
        try {
            permissionLauncher.launch(checker.permissionIntent())
        } catch (_: ActivityNotFoundException) {
            checker.installUnavailable("Einstellung für unbekannte Apps fehlt")
        } catch (_: SecurityException) {
            checker.installUnavailable("Einstellung nicht erreichbar")
        }
    }
}

private fun Context.findActivity(): Activity? {
    var c: Context? = this
    while (c is ContextWrapper) {
        if (c is Activity) return c
        c = c.baseContext
    }
    return null
}
